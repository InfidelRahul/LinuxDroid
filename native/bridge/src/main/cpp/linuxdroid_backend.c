#include "linuxdroid_backend.h"

#include <android/log.h>
#include <assert.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define TAG "LinuxDroid/WestonBackend"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

static int
linuxdroid_output_start_repaint_loop(struct weston_output *output)
{
    struct timespec ts;
    weston_compositor_read_presentation_clock(output->compositor, &ts);
    weston_output_finish_frame(output, &ts, WP_PRESENTATION_FEEDBACK_INVALID);
    return 0;
}

static int
linuxdroid_output_repaint(struct weston_output *output)
{
    // Phase 3 boundary: No renderer attached yet.
    // Advancing presentation clock marks the frame finished so the compositor event loop
    // and repaint timeline remain healthy without rendering.
    struct timespec ts;
    weston_compositor_read_presentation_clock(output->compositor, &ts);
    weston_output_finish_frame(output, &ts, WP_PRESENTATION_FEEDBACK_INVALID);
    return 0;
}

static int
linuxdroid_output_enable(struct weston_output *base)
{
    LOGI("WESTON_OUTPUT_ENABLE: output '%s' enabled", base->name ? base->name : "(unnamed)");
    return 0;
}

static int
linuxdroid_output_disable(struct weston_output *base)
{
    LOGI("WESTON_OUTPUT_DISABLE: output '%s' disabled", base->name ? base->name : "(unnamed)");
    return 0;
}

static void
linuxdroid_output_destroy_hook(struct weston_output *base)
{
    struct linuxdroid_output *output = (struct linuxdroid_output *)base;

    LOGI("WESTON_OUTPUT_DESTROY: output '%s' destroying", base->name ? base->name : "(unnamed)");

    if (base->enabled) {
        linuxdroid_output_disable(base);
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

    wl_list_insert(&compositor->backend_list, &b->base.link);

    if (weston_compositor_backends_loaded(compositor) < 0) {
        LOGE("WESTON_FAILURE: weston_compositor_backends_loaded failed");
        wl_list_remove(&b->base.link);
        free(b);
        return NULL;
    }

    LOGI("WESTON_BACKEND_INIT: LinuxDroid custom backend registered successfully");
    return b;
}

void
linuxdroid_backend_destroy(struct weston_backend *backend)
{
    struct linuxdroid_backend *b = (struct linuxdroid_backend *)backend;
    struct weston_head *head_base, *head_next;

    if (!b)
        return;

    LOGI("WESTON_BACKEND_DESTROY: LinuxDroid backend destroying");

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
        LOGE("WESTON_FAILURE: invalid arguments to linuxdroid_head_create");
        return NULL;
    }

    head = (struct linuxdroid_head *)calloc(1, sizeof(*head));
    if (!head) {
        LOGE("WESTON_FAILURE: failed to allocate linuxdroid_head");
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

    LOGI("WESTON_HEAD_CREATE: head '%s' created and added to compositor", name);
    return head;
}

void
linuxdroid_head_destroy(struct linuxdroid_head *head)
{
    if (!head)
        return;

    weston_head_release(&head->base);
    free(head);
}

struct weston_output *
linuxdroid_output_create(struct weston_backend *backend, const char *name)
{
    struct linuxdroid_backend *b;
    struct linuxdroid_output *output;

    if (!backend || !name) {
        LOGE("WESTON_FAILURE: invalid arguments to linuxdroid_output_create");
        return NULL;
    }

    b = (struct linuxdroid_backend *)backend;

    output = (struct linuxdroid_output *)calloc(1, sizeof(*output));
    if (!output) {
        LOGE("WESTON_FAILURE: failed to allocate linuxdroid_output");
        return NULL;
    }

    weston_output_init(&output->base, b->compositor, name);

    output->base.destroy = linuxdroid_output_destroy_hook;
    output->base.disable = linuxdroid_output_disable;
    output->base.enable = linuxdroid_output_enable;
    output->base.start_repaint_loop = linuxdroid_output_start_repaint_loop;
    output->base.repaint = linuxdroid_output_repaint;

    output->backend = b;

    weston_compositor_add_pending_output(&output->base, b->compositor);

    LOGI("WESTON_OUTPUT_CREATE: output '%s' created", name);
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
