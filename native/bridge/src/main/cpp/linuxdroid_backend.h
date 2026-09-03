#ifndef LINUXDROID_BACKEND_H
#define LINUXDROID_BACKEND_H

#include <stdbool.h>
#include <stdint.h>

#include <libweston/libweston.h>
#include <libweston/backend.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LINUXDROID_DEFAULT_REFRESH_MHZ 60000

/**
 * Configuration structure for initializing the LinuxDroid libweston backend.
 */
struct linuxdroid_backend_config {
    int refresh_mhz;
};

/**
 * LinuxDroid custom backend instance.
 * Embeds struct weston_backend as its first member to comply with libweston container_of contract.
 */
struct linuxdroid_backend {
    struct weston_backend base;
    struct weston_compositor *compositor;
    int refresh_mhz;
};

/**
 * LinuxDroid Weston head abstraction representing a logical display target.
 * Wraps struct weston_head.
 */
struct linuxdroid_head {
    struct weston_head base;
};

/**
 * LinuxDroid Weston output abstraction representing a Linux compositor output.
 * Wraps struct weston_output.
 */
struct linuxdroid_output {
    struct weston_output base;
    struct linuxdroid_backend *backend;
    struct weston_mode mode;
};

/*
 * Weston 16 C ABI exports called by custom backends.
 * These symbols are dynamically exported by libweston-16.so (T visibility),
 * but Weston's build only declares them in internal private headers (libweston-internal.h).
 * We declare their exact C signatures here to cleanly link against the real libweston-16.so.
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

int
weston_compositor_backends_loaded(struct weston_compositor *compositor);

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

#ifdef __cplusplus
}
#endif

#endif /* LINUXDROID_BACKEND_H */
