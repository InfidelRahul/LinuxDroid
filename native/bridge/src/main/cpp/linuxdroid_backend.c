#include "linuxdroid_backend.h"
#include "android_presentation.h"

#include <android/log.h>
#include <assert.h>
#include <drm_fourcc.h>
#include <errno.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define TAG "LinuxDroid/WestonBackend"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

const struct pixel_format_info *
pixel_format_get_info(uint32_t format);

static int
linuxdroid_output_start_repaint_loop(struct weston_output *output)
{
    struct timespec ts;
    weston_compositor_read_presentation_clock(output->compositor, &ts);
    weston_output_finish_frame(output, &ts, WP_PRESENTATION_FEEDBACK_INVALID);
    return 0;
}

static int
linuxdroid_output_repaint(struct weston_output *base)
{
    struct linuxdroid_output *output = (struct linuxdroid_output *)base;
    struct weston_renderer *renderer = base->compositor->renderer;
    struct timespec ts;

    if (!output->presentation || !android_presentation_is_enabled(output->presentation) ||
        !output->pixman_initialized || !renderer) {
        weston_compositor_read_presentation_clock(base->compositor, &ts);
        weston_output_finish_frame(base, &ts, WP_PRESENTATION_FEEDBACK_INVALID);
        return 0;
    }

    if (output->frame_count < 5) {
        LOGI("PIXMAN_REPAINT_BEGIN: frame=%u, output='%s' (%dx%d)",
             output->frame_count, base->name ? base->name : "(unnamed)",
             output->width, output->height);
    }

    // 1. Acquire available buffer from Android presentation pool
    int slot_index = -1;
    struct AHardwareBuffer *ahb = NULL;
    int err = android_presentation_acquire_buffer(output->presentation, &slot_index, &ahb, 50);
    if (err < 0) {
        LOGW("PIXMAN_BUFFER_ACQUIRE: no buffer available (err=%d), deferring repaint", err);
        weston_output_schedule_repaint(base);
        return 0;
    }

    // 2. Lock AHardwareBuffer for direct CPU write access
    void *pixels = NULL;
    int32_t stride_bytes = 0;
    err = android_presentation_lock_buffer(output->presentation, slot_index, &pixels, &stride_bytes);
    if (err < 0 || !pixels) {
        LOGE("PIXMAN_RENDERER_ERROR: PIXMAN_LOCK_FAILURE - lock failed on slot %d (err=%d, w=%d, h=%d)",
             slot_index, err, output->width, output->height);
        weston_compositor_read_presentation_clock(base->compositor, &ts);
        weston_output_finish_frame(base, &ts, WP_PRESENTATION_FEEDBACK_INVALID);
        return -1;
    }

    // 3. Resolve pixel format (DRM_FORMAT_ABGR8888 matches Android R8G8B8A8 little-endian)
    const struct pixel_format_info *pfmt = pixel_format_get_info(DRM_FORMAT_ABGR8888);
    if (!pfmt) {
        LOGE("PIXMAN_RENDERER_ERROR: PIXMAN_FORMAT_FAILURE - failed to get pixel format for DRM_FORMAT_ABGR8888");
        android_presentation_unlock_buffer(output->presentation, slot_index, NULL);
        weston_compositor_read_presentation_clock(base->compositor, &ts);
        weston_output_finish_frame(base, &ts, WP_PRESENTATION_FEEDBACK_INVALID);
        return -1;
    }

    // 4. Wrap mapped buffer in Weston renderbuffer (zero intermediate memcpy)
    weston_renderbuffer_t rb = renderer->create_renderbuffer(base, pfmt, pixels, stride_bytes, NULL, NULL);
    if (!rb) {
        LOGE("PIXMAN_RENDERER_ERROR: PIXMAN_IMAGE_FAILURE - create_renderbuffer failed on slot %d (stride=%d)",
             slot_index, stride_bytes);
        android_presentation_unlock_buffer(output->presentation, slot_index, NULL);
        weston_compositor_read_presentation_clock(base->compositor, &ts);
        weston_output_finish_frame(base, &ts, WP_PRESENTATION_FEEDBACK_INVALID);
        return -1;
    }

    // 5. Accumulate damage and execute Weston Pixman scene composite
    pixman_region32_t damage;
    pixman_region32_init(&damage);
    weston_output_flush_damage_for_primary_plane(base, &damage);

    renderer->repaint_output(base, &damage, rb);

    pixman_region32_fini(&damage);

    // 6. Cleanly destroy renderbuffer before unlocking memory
    renderer->destroy_renderbuffer(rb);

    // 7. Unlock AHardwareBuffer and flush CPU cache
    int release_fence = -1;
    err = android_presentation_unlock_buffer(output->presentation, slot_index, &release_fence);
    if (err < 0) {
        LOGE("PIXMAN_RENDERER_ERROR: PIXMAN_UNLOCK_FAILURE - unlock failed on slot %d: %d", slot_index, err);
    }

    // 8. Submit buffer to SurfaceControl transaction
    err = android_presentation_submit_buffer(output->presentation, slot_index, release_fence);
    if (err < 0) {
        LOGE("PIXMAN_RENDERER_ERROR: PIXMAN_SUBMIT_FAILURE - submit failed on slot %d: %d", slot_index, err);
    }

    // 9. Advance presentation clock and complete Weston frame
    weston_compositor_read_presentation_clock(base->compositor, &ts);
    weston_output_finish_frame(base, &ts, WP_PRESENTATION_FEEDBACK_INVALID);

    output->frame_count++;
    if (output->frame_count <= 5) {
        LOGI("PIXMAN_REPAINT_END: frame=%u submitted successfully (slot=%d)", output->frame_count, slot_index);
    }
    return 0;
}

static int
linuxdroid_output_enable(struct weston_output *base)
{
    struct linuxdroid_output *output = (struct linuxdroid_output *)base;
    struct weston_renderer *renderer = base->compositor->renderer;

    if (!renderer || !renderer->pixman) {
        LOGE("PIXMAN_RENDERER_ERROR: PIXMAN_INIT_FAILURE - compositor has no pixman renderer");
        return -1;
    }

    // 1. Connect to Android presentation layer if native window is attached
    if (output->presentation && output->native_window) {
        int err = android_presentation_enable(output->presentation,
                                              output->native_window,
                                              output->width,
                                              output->height);
        if (err < 0) {
            LOGE("ANDROID_PRESENTATION_ERROR: failed to enable Android presentation surface: %d", err);
            return -1; // If SurfaceControl creation fails, output enable must fail
        }
    }

    // 2. Initialize Pixman output renderer state
    const struct pixel_format_info *pfmt = pixel_format_get_info(DRM_FORMAT_ABGR8888);
    if (!pfmt) {
        LOGE("PIXMAN_RENDERER_ERROR: PIXMAN_FORMAT_FAILURE - failed to get pixel format DRM_FORMAT_ABGR8888");
        return -1;
    }

    struct pixman_renderer_output_options options = {
        .use_shadow = false,
        .fb_size = {
            .width = output->base.current_mode ? output->base.current_mode->width : output->width,
            .height = output->base.current_mode ? output->base.current_mode->height : output->height,
        },
        .format = pfmt,
    };

    if (renderer->pixman->output_create(base, &options) < 0) {
        LOGE("PIXMAN_RENDERER_ERROR: PIXMAN_INIT_FAILURE - renderer->pixman->output_create failed");
        return -1;
    }
    output->pixman_initialized = true;

    // 3. Create deterministic test scene for first visible frame validation
    linuxdroid_output_create_test_scene(base);

    // 4. Schedule initial repaint so first frame is immediately rendered and presented
    weston_output_schedule_repaint(base);

    LOGI("LINUXDROID_OUTPUT_ENABLED: output '%s' enabled (%dx%d) with Pixman software renderer",
         base->name ? base->name : "(unnamed)", output->width, output->height);
    return 0;
}

static int
linuxdroid_output_disable(struct weston_output *base)
{
    struct linuxdroid_output *output = (struct linuxdroid_output *)base;
    struct weston_renderer *renderer = base->compositor->renderer;

    if (output->pixman_initialized && renderer && renderer->pixman) {
        LOGI("PIXMAN_RENDERER_DESTROY: destroying Pixman output renderer state for '%s'",
             base->name ? base->name : "(unnamed)");
        renderer->pixman->output_destroy(base);
        output->pixman_initialized = false;
    }

    if (output->test_surface) {
        weston_surface_unref(output->test_surface);
        output->test_surface = NULL;
        output->test_view = NULL;
        weston_layer_fini(&output->test_layer);
    }

    if (output->presentation) {
        android_presentation_disable(output->presentation);
    }

    LOGI("LINUXDROID_OUTPUT_DISABLED: output '%s' disabled", base->name ? base->name : "(unnamed)");
    return 0;
}

static void
linuxdroid_output_destroy_hook(struct weston_output *base)
{
    struct linuxdroid_output *output = (struct linuxdroid_output *)base;

    LOGI("LINUXDROID_OUTPUT_DESTROYED: output '%s' destroying", base->name ? base->name : "(unnamed)");

    if (base->enabled) {
        linuxdroid_output_disable(base);
    }

    if (output->presentation) {
        android_presentation_destroy(output->presentation);
        output->presentation = NULL;
    }

    weston_output_release(base);
    free(output);
}

struct linuxdroid_backend *
linuxdroid_backend_create(struct weston_compositor *compositor,
                          const struct linuxdroid_backend_config *config)
{
    struct linuxdroid_backend *b;

    if (!compositor) {
        LOGE("WESTON_FAILURE: null compositor passed to linuxdroid_backend_create");
        return NULL;
    }

    b = (struct linuxdroid_backend *)calloc(1, sizeof(*b));
    if (!b) {
        LOGE("WESTON_FAILURE: failed to allocate linuxdroid_backend");
        return NULL;
    }

    b->compositor = compositor;
    b->refresh_mhz = (config && config->refresh_mhz > 0) ? config->refresh_mhz : LINUXDROID_DEFAULT_REFRESH_MHZ;

    b->base.supported_presentation_clocks = WESTON_PRESENTATION_CLOCKS_SOFTWARE;
    b->base.destroy = linuxdroid_backend_destroy;
    b->base.create_output = linuxdroid_output_create;

    // Initialize Pixman software renderer on compositor if not yet active
    if (!compositor->renderer) {
        LOGI("PIXMAN_RENDERER_INIT: initializing Pixman software renderer on compositor");
        if (weston_compositor_init_renderer(compositor, WESTON_RENDERER_PIXMAN, NULL) < 0) {
            LOGE("PIXMAN_RENDERER_ERROR: PIXMAN_INIT_FAILURE - failed to initialize Pixman software renderer");
            free(b);
            return NULL;
        }
        LOGI("PIXMAN_RENDERER_READY: Pixman software renderer initialized successfully");
    }

    wl_list_insert(&compositor->backend_list, &b->base.link);

    if (weston_compositor_backends_loaded(compositor) < 0) {
        LOGE("WESTON_FAILURE: weston_compositor_backends_loaded failed");
        wl_list_remove(&b->base.link);
        free(b);
        return NULL;
    }

    LOGI("LINUXDROID_BACKEND_CREATED: LinuxDroid custom backend registered successfully");
    return b;
}

void
linuxdroid_backend_destroy(struct weston_backend *backend)
{
    struct linuxdroid_backend *b = (struct linuxdroid_backend *)backend;
    struct weston_head *head_base, *head_next;

    if (!b)
        return;

    LOGI("LINUXDROID_BACKEND_DESTROY: LinuxDroid backend destroying");

    wl_list_remove(&b->base.link);

    if (b->compositor) {
        wl_list_for_each_safe(head_base, head_next, &b->compositor->head_list, compositor_link) {
            if (head_base->backend == &b->base) {
                struct linuxdroid_head *head = (struct linuxdroid_head *)head_base;
                linuxdroid_head_destroy(head);
            }
        }
    }

    free(b);
}

struct linuxdroid_head *
linuxdroid_head_create(struct linuxdroid_backend *backend,
                       const char *name,
                       int32_t width_mm,
                       int32_t height_mm)
{
    struct linuxdroid_head *head;

    if (!backend || !backend->compositor || !name) {
        LOGE("WESTON_START_FAILED: invalid arguments to linuxdroid_head_create");
        return NULL;
    }

    head = (struct linuxdroid_head *)calloc(1, sizeof(*head));
    if (!head) {
        LOGE("WESTON_START_FAILED: failed to allocate linuxdroid_head");
        return NULL;
    }

    weston_head_init(&head->base, name);
    head->base.backend = &backend->base;

    weston_head_set_connection_status(&head->base, true);
    weston_head_set_supported_eotf_mask(&head->base, WESTON_EOTF_MODE_ALL_MASK);
    weston_head_set_supported_colorimetry_mask(&head->base, WESTON_COLORIMETRY_MODE_ALL_MASK);
    weston_head_set_monitor_strings(&head->base, "LinuxDroid", "AndroidDisplay", "001");
    weston_head_set_physical_size(&head->base, width_mm, height_mm);

    weston_compositor_add_head(backend->compositor, &head->base);

    LOGI("LINUXDROID_HEAD_CREATED: logical display head '%s' created and added to compositor", name);
    return head;
}

void
linuxdroid_head_destroy(struct linuxdroid_head *head)
{
    if (!head)
        return;

    LOGI("LINUXDROID_HEAD_DESTROYED: logical display head '%s' released and destroyed",
         head->base.name ? head->base.name : "(unnamed)");
    weston_head_release(&head->base);
    free(head);
}

struct weston_output *
linuxdroid_output_create(struct weston_backend *backend, const char *name)
{
    struct linuxdroid_backend *b;
    struct linuxdroid_output *output;

    if (!backend || !name) {
        LOGE("WESTON_START_FAILED: invalid arguments to linuxdroid_output_create");
        return NULL;
    }

    b = (struct linuxdroid_backend *)backend;

    output = (struct linuxdroid_output *)calloc(1, sizeof(*output));
    if (!output) {
        LOGE("WESTON_START_FAILED: failed to allocate linuxdroid_output");
        return NULL;
    }

    output->presentation = android_presentation_create();
    output->width = LINUXDROID_DEFAULT_WIDTH;
    output->height = LINUXDROID_DEFAULT_HEIGHT;

    weston_output_init(&output->base, b->compositor, name);

    output->base.destroy = linuxdroid_output_destroy_hook;
    output->base.disable = linuxdroid_output_disable;
    output->base.enable = linuxdroid_output_enable;
    output->base.start_repaint_loop = linuxdroid_output_start_repaint_loop;
    output->base.repaint = linuxdroid_output_repaint;

    output->backend = b;

    weston_compositor_add_pending_output(&output->base, b->compositor);

    LOGI("LINUXDROID_OUTPUT_CREATED: logical compositor output '%s' created and pending", name);
    return &output->base;
}

int
linuxdroid_output_set_mode(struct weston_output *output,
                           int32_t width,
                           int32_t height,
                           int32_t refresh_mhz,
                           int32_t scale)
{
    struct linuxdroid_output *droid_output;

    if (!output || width <= 0 || height <= 0) {
        LOGE("WESTON_FAILURE: invalid dimensions for linuxdroid_output_set_mode");
        return -1;
    }

    droid_output = (struct linuxdroid_output *)output;
    droid_output->width = width;
    droid_output->height = height;

    if (scale < 1) scale = 1;

    droid_output->mode.flags = WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED;
    droid_output->mode.width = width * scale;
    droid_output->mode.height = height * scale;
    droid_output->mode.refresh = (refresh_mhz > 0) ? refresh_mhz : LINUXDROID_DEFAULT_REFRESH_MHZ;

    wl_list_insert(&output->mode_list, &droid_output->mode.link);
    output->current_mode = &droid_output->mode;
    output->current_scale = scale;
    output->transform = WL_OUTPUT_TRANSFORM_NORMAL;

    return 0;
}

void
linuxdroid_output_destroy(struct weston_output *output)
{
    if (!output)
        return;

    if (output->destroy) {
        output->destroy(output);
    } else {
        weston_output_release(output);
        free(output);
    }
}

void
linuxdroid_output_set_window(struct weston_output *base, struct ANativeWindow *window)
{
    struct linuxdroid_output *output = (struct linuxdroid_output *)base;
    if (!output) return;

    output->native_window = window;
    if (output->presentation) {
        android_presentation_set_window(output->presentation, window);
    }
}

int
linuxdroid_output_resize(struct weston_output *base, int32_t width, int32_t height)
{
    struct linuxdroid_output *output = (struct linuxdroid_output *)base;
    if (!output || width <= 0 || height <= 0) return -1;

    output->width = width;
    output->height = height;

    if (output->presentation) {
        android_presentation_resize(output->presentation, width, height);
    }

    if (output->base.current_mode) {
        output->mode.width = width * output->base.current_scale;
        output->mode.height = height * output->base.current_scale;
    }

    if (output->pixman_initialized && base->compositor && base->compositor->renderer) {
        struct weston_size new_fb_size = {
            .width = output->mode.width,
            .height = output->mode.height
        };
        struct weston_geometry area = {
            .x = 0,
            .y = 0,
            .width = output->mode.width,
            .height = output->mode.height
        };
        weston_renderer_resize_output(base, &new_fb_size, &area);
        LOGI("PIXMAN_RENDERER_RESIZE: output '%s' resized to %dx%d",
             base->name ? base->name : "(unnamed)", new_fb_size.width, new_fb_size.height);
    }

    return 0;
}

int
linuxdroid_output_create_test_scene(struct weston_output *output)
{
    struct linuxdroid_output *droid_output = (struct linuxdroid_output *)output;
    struct weston_compositor *ec = output->compositor;

    if (!ec || droid_output->test_surface) return 0;

    weston_layer_init(&droid_output->test_layer, ec);
    weston_layer_set_position(&droid_output->test_layer, WESTON_LAYER_POSITION_NORMAL);

    struct weston_surface *surf = weston_surface_create(ec, NULL);
    if (!surf) {
        LOGW("WESTON_FAILURE: failed to create test scene surface");
        return -1;
    }

    struct weston_view *view = weston_view_create(surf);
    if (!view) {
        LOGW("WESTON_FAILURE: failed to create test scene view");
        weston_surface_unref(surf);
        return -1;
    }

    // Attach solid Navy #101828 to the surface
    struct weston_buffer_reference *buf_ref =
        weston_buffer_create_solid_rgba(ec, 0.06f, 0.09f, 0.16f, 1.0f);
    if (!buf_ref) {
        LOGW("WESTON_FAILURE: failed to create solid test buffer");
        weston_surface_unref(surf);
        return -1;
    }

    int w = output->width;
    int h = output->height;
    weston_surface_attach_solid(surf, buf_ref, w, h);
    weston_buffer_destroy_solid(buf_ref);

    weston_surface_map(surf);

    struct weston_coord_global pos = { .c = { .x = 0.0, .y = 0.0 } };
    weston_view_set_position(view, pos);
    weston_view_move_to_layer(view, &droid_output->test_layer.view_list);

    droid_output->test_surface = surf;
    droid_output->test_view = view;

    LOGI("LINUXDROID_TEST_SCENE_CREATED: deterministic visible test scene attached (%dx%d)", w, h);
    return 0;
}
