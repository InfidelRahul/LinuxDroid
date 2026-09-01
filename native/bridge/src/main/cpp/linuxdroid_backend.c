/*
 * LinuxDroid — custom libweston backend (integration point).
 *
 * This is the LinuxDroid custom Android backend referenced by the frozen GUI
 * architecture:
 *
 *   libweston / Weston 16.0.0
 *        ↓
 *   LinuxDroid custom Android backend
 *        ↓
 *   weston_output          (deferred to a later phase)
 *        ↓
 *   AHardwareBuffer        (deferred to a later phase)
 *        ↓
 *   ASurfaceControl/...    (deferred to a later phase)
 *
 * Phase 3 scope (this file): establish the libweston backend OUTPUT integration
 * point only. The backend registers itself with the compositor and owns a
 * minimal embedded struct that a later phase will extend to map a `weston_head`
 * to the Android display and a `weston_output` to an AHardwareBuffer-backed
 * SurfaceControl. It does NOT present any buffer and does NOT render anything.
 *
 * This file is compiled ONLY when LINUXDROID_HAS_LIBWESTON is defined (i.e.
 * when the pinned libweston 16.0.0 build output is available). It is the module
 * that linuxdroid::WestonHost initializes at compositor startup.
 *
 * NOTE ON THE API SURFACE: the `struct weston_backend` and
 * `weston_compositor::backend` members follow the libweston 16.0.0 contract.
 * When this module is built against the pinned libweston headers, any minor
 * signature drift would be resolved at compile time; this file is intentionally
 * kept out of the default (non-libweston) build so it cannot break it.
 */

/* This file must not be compiled unless libweston integration is enabled. */
#ifdef LINUXDROID_HAS_LIBWESTON

#include <android/log.h>
#include <stdlib.h>

#include <libweston/compositor.h>

#define TAG "LinuxDroid/WestonBackend"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

/*
 * LinuxDroid backend instance.
 *
 * `base` must be the first member so a `struct linuxdroid_backend*` can be
 * recovered from the `struct weston_backend*` stored in
 * `compositor->backend`.
 *
 * Future phases will add:
 *   - head mapping   (weston_head -> Android display)
 *   - output mapping (weston_output -> AHardwareBuffer-backed SurfaceControl)
 *   - renderer        (Pixman bring-up -> GLES/EGL -> Vulkan)
 *   - input routing
 * Kept out of scope for Phase 3.
 */
struct linuxdroid_backend {
    struct weston_backend base;
};

static void
linuxdroid_backend_destroy(struct weston_compositor *compositor)
{
    struct linuxdroid_backend *b =
        (struct linuxdroid_backend *)compositor->backend;

    if (b == NULL) {
        return;
    }

    LOGI("linuxdroid backend destroy");
    free(b);
    compositor->backend = NULL;
}

/*
 * Backend entry point. libweston expects a `struct weston_backend` installed on
 * `compositor->backend`. No output/head is created yet: the integration point is
 * established, and buffer/rendering presentation follows in a later phase.
 */
int
linuxdroid_backend_init(struct weston_compositor *compositor)
{
    struct linuxdroid_backend *b;

    if (compositor == NULL) {
        LOGE("linuxdroid_backend_init called with null compositor");
        return -1;
    }

    b = (struct linuxdroid_backend *)calloc(1, sizeof(*b));
    if (b == NULL) {
        LOGE("linuxdroid_backend_init: calloc failed");
        return -1;
    }

    b->base.destroy = linuxdroid_backend_destroy;

    compositor->backend = &b->base;
    LOGI("linuxdroid backend registered with compositor (integration point ready)");

    return 0;
}

#endif /* LINUXDROID_HAS_LIBWESTON */
