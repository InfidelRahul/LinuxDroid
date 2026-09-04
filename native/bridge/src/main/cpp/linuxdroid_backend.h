#ifndef LINUXDROID_BACKEND_H
#define LINUXDROID_BACKEND_H

#include <stdbool.h>
#include <stdint.h>
#include <EGL/egl.h>

#ifdef __cplusplus
extern "C" {
#endif

#include <libweston/libweston.h>
#include <libweston/backend.h>
#include "vsync_bridge.h"

#define LINUXDROID_DEFAULT_REFRESH_MHZ 60000

/**
 * Renderer type selection for LinuxDroid backend.
 */
enum linuxdroid_renderer_type {
    LINUXDROID_RENDERER_GLES = 0,   // Production default (GPU-backed)
    LINUXDROID_RENDERER_PIXMAN = 1  // Diagnostic / fallback reference
};

/**
 * Explicit renderer lifecycle state.
 */
enum linuxdroid_renderer_state {
    LINUXDROID_RENDERER_STATE_UNINITIALIZED = 0,
    LINUXDROID_RENDERER_STATE_GLES_INITIALIZED = 1,
    LINUXDROID_RENDERER_STATE_GLES_FAILED = 2,
    LINUXDROID_RENDERER_STATE_PIXMAN_INITIALIZED = 3,
    LINUXDROID_RENDERER_STATE_PIXMAN_FAILED = 4,
};

/**
 * Configuration structure for initializing the LinuxDroid libweston backend.
 */
struct linuxdroid_backend_config {
    int refresh_mhz;
    enum linuxdroid_renderer_type renderer_type;
};

/**
 * LinuxDroid custom backend instance.
 * Embeds struct weston_backend as its first member to comply with libweston container_of contract.
 */
struct linuxdroid_backend {
    struct weston_backend base;
    struct weston_compositor *compositor;
    int refresh_mhz;
    enum linuxdroid_renderer_type renderer_type;
    enum linuxdroid_renderer_state renderer_state;

    // Phase 6: Seat & input devices
    struct weston_seat seat;
    struct weston_touch_device *touch_device;
    bool seat_initialized;

    // Phase 9: Frame Timing & Android VSync
    struct linuxdroid_vsync_bridge *vsync_bridge;
};

/**
 * LinuxDroid Weston head abstraction representing a logical display target.
 * Wraps struct weston_head.
 */
struct linuxdroid_head {
    struct weston_head base;
};

struct android_presentation;
struct ANativeWindow;

typedef void *weston_renderbuffer_t;
typedef bool (*weston_renderbuffer_discarded_func)(weston_renderbuffer_t renderbuffer, void *user_data);

/**
 * LinuxDroid Weston output abstraction representing a Linux compositor output.
 * Connects the Weston output to the Android presentation subsystem (SurfaceControl & AHardwareBuffer).
 * Wraps struct weston_output.
 */
struct linuxdroid_output {
    struct weston_output base;
    struct linuxdroid_backend *backend;
    struct weston_mode mode;
    struct android_presentation *presentation;
    struct ANativeWindow *native_window;
    int32_t width;
    int32_t height;
    bool pixman_initialized;
    bool gles_initialized;
    weston_renderbuffer_t gles_renderbuffers[3];
    uint32_t frame_count;
    struct weston_layer test_layer;
    struct weston_surface *test_surface;
    struct weston_view *test_view;
    struct linuxdroid_vsync_bridge *vsync_bridge;
};

/*
 * Weston C ABI exports called by custom backends and renderers.
 * These symbols are dynamically exported by libweston-17.so (T visibility),
 * but Weston's build only declares them in internal private headers (libweston-internal.h).
 * We declare their exact C signatures here to cleanly link against the real libweston-17.so.
 */
void
weston_compositor_add_head(struct weston_compositor *compositor,
                           struct weston_head *head);

void
weston_compositor_add_pending_output(struct weston_output *output,
                                     struct weston_compositor *compositor);

void
weston_compositor_read_presentation_clock(struct weston_compositor *compositor,
                                          struct timespec *ts);

struct weston_renderer_options {
    int reserved;
};

int
weston_compositor_backends_loaded(struct weston_compositor *compositor);

int
weston_compositor_init_renderer(struct weston_compositor *compositor,
                                enum weston_renderer_type renderer_type,
                                const struct weston_renderer_options *options);

bool
weston_renderer_resize_output(struct weston_output *output,
                              const struct weston_size *fb_size,
                              const struct weston_geometry *area);

struct weston_buffer_reference *
weston_buffer_create_solid_rgba(struct weston_compositor *compositor,
                                float r, float g, float b, float a);

void
weston_surface_attach_solid(struct weston_surface *surface,
                            struct weston_buffer_reference *buffer_ref,
                            int w, int h);

void
weston_buffer_destroy_solid(struct weston_buffer_reference *buffer_ref);

void
weston_surface_map(struct weston_surface *surface);

void
weston_view_move_to_layer(struct weston_view *view,
                          struct weston_layer_entry *layer);

struct weston_paint_node;
struct linux_dmabuf_buffer;
struct linux_dmabuf_memory;
struct weston_drm_format_array;
struct pixel_format_info;

struct pixman_renderer_output_options {
    bool use_shadow;
    struct weston_size fb_size;
    const struct pixel_format_info *format;
};

struct pixman_renderer_interface {
    int (*output_create)(struct weston_output *output,
                         const struct pixman_renderer_output_options *options);
    void (*output_destroy)(struct weston_output *output);
};

struct gl_renderer_display_options {
    struct weston_renderer_options base;
    uint32_t egl_platform;
    void *egl_native_display;
    int32_t egl_surface_type;
    const struct pixel_format_info **formats;
    unsigned formats_count;
};

struct gl_renderer_fbo_options {
    struct weston_size fb_size;
    struct weston_geometry area;
};

struct gl_renderer_interface {
    int (*display_create)(struct weston_compositor *ec,
                          const struct gl_renderer_display_options *options);
    int (*output_window_create)(struct weston_output *output,
                                const void *options);
    const struct pixel_format_info **
        (*get_supported_rendering_formats)(struct weston_compositor *ec,
                                           unsigned int *formats_count);
    int (*output_fbo_create)(struct weston_output *output,
                             const struct gl_renderer_fbo_options *options);
    void (*output_destroy)(struct weston_output *output);
    int (*create_fence_fd)(struct weston_output *output);
    EGLDisplay (*get_display)(struct weston_compositor *ec);
    int (*make_current)(struct weston_compositor *ec);
};

struct weston_renderer {
    void (*repaint_output)(struct weston_output *output,
                           pixman_region32_t *output_damage,
                           weston_renderbuffer_t renderbuffer);

    bool (*resize_output)(struct weston_output *output,
                          const struct weston_size *fb_size,
                          const struct weston_geometry *area);

    void (*flush_damage)(struct weston_paint_node *pnode);
    void (*attach)(struct weston_paint_node *pnode);
    void (*destroy)(struct weston_compositor *ec);

    int (*surface_copy_content)(struct weston_surface *surface,
                                void *target, size_t size,
                                int src_x, int src_y,
                                int width, int height);

    bool (*import_dmabuf)(struct weston_compositor *ec,
                          struct linux_dmabuf_buffer *buffer);

    const struct weston_drm_format_array *
            (*get_supported_dmabuf_formats)(struct weston_compositor *ec);

    void (*buffer_init)(struct weston_compositor *ec,
                        struct weston_buffer *buffer);

    weston_renderbuffer_t
    (*create_renderbuffer)(struct weston_output *output,
                           const struct pixel_format_info *format,
                           void *buffer,
                           int stride,
                           weston_renderbuffer_discarded_func discarded_cb,
                           void *user_data);

    weston_renderbuffer_t
    (*create_renderbuffer_dmabuf)(struct weston_output *output,
                                  struct linux_dmabuf_memory *dmabuf,
                                  weston_renderbuffer_discarded_func discarded_cb,
                                  void *user_data);

    void (*destroy_renderbuffer)(weston_renderbuffer_t renderbuffer);

    struct linux_dmabuf_memory *
            (*dmabuf_alloc)(struct weston_renderer *renderer,
                            unsigned int width, unsigned int height,
                            uint32_t format,
                            const uint64_t *modifiers, unsigned int count);

    bool (*can_render_straight_alpha)(struct weston_compositor *wc);

    enum weston_renderer_type type;
    const struct gl_renderer_interface *gl;
    const void *vulkan;
    const struct pixman_renderer_interface *pixman;
};

/**
 * Creates and initializes the LinuxDroid custom backend on the compositor.
 *
 * @param compositor Pointer to the active weston_compositor.
 * @param config Optional backend configuration (uses defaults if NULL).
 * @return Pointer to linuxdroid_backend, or NULL on failure.
 */
struct linuxdroid_backend *
linuxdroid_backend_create(struct weston_compositor *compositor,
                          const struct linuxdroid_backend_config *config);

/**
 * Backend destroy callback conforming to weston_backend::destroy.
 */
void
linuxdroid_backend_destroy(struct weston_backend *backend);

/**
 * Creates and registers a LinuxDroid display head with the compositor.
 *
 * @param backend Pointer to the LinuxDroid backend.
 * @param name Unique name for the head (e.g. "linuxdroid-head-0").
 * @param width_mm Physical width in millimeters (0 for unmeasured).
 * @param height_mm Physical height in millimeters (0 for unmeasured).
 * @return Pointer to created head, or NULL on failure.
 */
struct linuxdroid_head *
linuxdroid_head_create(struct linuxdroid_backend *backend,
                       const char *name,
                       int32_t width_mm,
                       int32_t height_mm);

/**
 * Releases and destroys a LinuxDroid display head.
 */
void
linuxdroid_head_destroy(struct linuxdroid_head *head);

/**
 * Allocates and initializes a LinuxDroid output (weston_backend::create_output).
 */
struct weston_output *
linuxdroid_output_create(struct weston_backend *backend, const char *name);

/**
 * Configures the mode (geometry and refresh rate) and scale on a pending LinuxDroid output.
 *
 * @param output The output to configure.
 * @param width Buffer width in pixels.
 * @param height Buffer height in pixels.
 * @param refresh_mhz Refresh rate in millihertz (e.g. 60000 for 60Hz).
 * @param scale Output display scale (>= 1).
 * @return 0 on success, negative on error.
 */
int
linuxdroid_output_set_mode(struct weston_output *output,
                           int32_t width,
                           int32_t height,
                           int32_t refresh_mhz,
                           int32_t scale);

/**
 * Disables and destroys a LinuxDroid output.
 */
void
linuxdroid_output_destroy(struct weston_output *output);

/**
 * Attaches or rebinds an Android ANativeWindow to a LinuxDroid output.
 */
void
linuxdroid_output_set_window(struct weston_output *output, struct ANativeWindow *window);

/**
 * Resizes the output and underlying Android presentation resources without buffer corruption.
 */
int
linuxdroid_output_resize(struct weston_output *output, int32_t width, int32_t height);

/**
 * Creates a deterministic test scene surface on the compositor to validate
 * the first real visible Linux desktop frame through Pixman and SurfaceControl.
 */
int
linuxdroid_output_create_test_scene(struct weston_output *output);

/* ─── Phase 6: Seat & Input Subsystem ABI ────────────────────────────────── */

void
weston_seat_init(struct weston_seat *seat, struct weston_compositor *ec,
                 const char *seat_name);

void
weston_seat_release(struct weston_seat *seat);

int
weston_seat_init_pointer(struct weston_seat *seat);

int
weston_seat_init_keyboard(struct weston_seat *seat, struct xkb_keymap *keymap);

int
weston_seat_init_touch(struct weston_seat *seat);

void
weston_seat_release_keyboard(struct weston_seat *seat);

void
weston_seat_release_pointer(struct weston_seat *seat);

void
weston_seat_release_touch(struct weston_seat *seat);

struct weston_touch_device *
weston_touch_create_touch_device(struct weston_touch *touch,
                                 const char *syspath,
                                 void *backend_data,
                                 const struct weston_touch_device_ops *ops,
                                 weston_touch_device_set_output_func_t set_output);

void
weston_touch_device_destroy(struct weston_touch_device *device);

struct weston_seat *
linuxdroid_backend_get_seat(struct linuxdroid_backend *b);

struct weston_touch_device *
linuxdroid_backend_get_touch_device(struct linuxdroid_backend *b);

void
linuxdroid_backend_reset_input(struct linuxdroid_backend *b);

enum linuxdroid_renderer_state
linuxdroid_backend_get_renderer_state(struct linuxdroid_backend *b);

enum linuxdroid_renderer_type
linuxdroid_backend_get_renderer_type(struct linuxdroid_backend *b);

/* ─── Phase 9: Frame Timing & Android VSync ABI ──────────────────────────── */

int
linuxdroid_backend_handle_vsync_event(int fd, uint32_t mask, void *data);

struct linuxdroid_vsync_bridge *
linuxdroid_backend_get_vsync_bridge(struct linuxdroid_backend *b);

void
linuxdroid_output_set_vsync_bridge(struct weston_output *output, struct linuxdroid_vsync_bridge *bridge);

int
linuxdroid_output_set_refresh_rate(struct weston_output *output, int refresh_mhz);

#ifdef __cplusplus
}
#endif

#endif /* LINUXDROID_BACKEND_H */
