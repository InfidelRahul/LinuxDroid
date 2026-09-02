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
 * API NOTE (libweston 16.0.0): a backend is a `struct weston_backend` linked
 * into `compositor->backend_list` via `wl_list_insert()`. The full definition
 * lives in the private `libweston/backend.h`, which is NOT part of the installed
 * public headers; `build-libweston.sh` installs it next to the public headers
 * so this module can embed `struct weston_backend` as its first member. We must
 * NOT use `compositor->backend` — that member does not exist in Weston 16.
 */

/* This file must not be compiled unless libweston integration is enabled. */
#ifdef LINUXDROID_HAS_LIBWESTON

#include <android/log.h>
#include <stdlib.h>

#include <wayland-server.h>
#include <libweston/libweston.h>
#include <libweston/backend.h>

#define TAG "LinuxDroid/WestonBackend"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

/*
 * LinuxDroid backend instance.
 *
 * `base` must be the first member so a `struct linuxdroid_backend*` can be
 * recovered from the `struct weston_backend*` linked into
 * `compositor->backend_list` (via container_of). The backend is registered with
 * the compositor on init and torn down by libweston on compositor destroy.
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
    struct weston_compositor *compositor;
};

/*
 * libweston calls this when the compositor is destroyed; the backend must
 * unlink itself from `compositor->backend_list` and release its memory.
 */
static void
linuxdroid_backend_destroy(struct weston_backend *backend)
{
    struct linuxdroid_backend *b =
        container_of(backend, struct linuxdroid_backend, base);

    if (b == NULL) {
        return;
    }

    LOGI("linuxdroid backend destroy");
    wl_list_remove(&b->base.link);
    free(b);
}

/*
 * Backend entry point. libweston 16 keeps backends in a list
 * (`compositor->backend_list`); the first member of `struct weston_backend` is
 * the `link` used to insert it. No output/head is created yet: the integration
 * point is established, and buffer/rendering presentation follows in a later
 * phase.
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

    b->compositor = compositor;
    b->base.destroy = linuxdroid_backend_destroy;
    b->base.supported_presentation_clocks = WESTON_PRESENTATION_CLOCKS_SOFTWARE;

    wl_list_insert(&compositor->backend_list, &b->base.link);
    compositor->primary_backend = &b->base;

    LOGI("linuxdroid backend registered with compositor (integration point ready)");
    return 0;
}

#endif /* LINUXDROID_HAS_LIBWESTON */
