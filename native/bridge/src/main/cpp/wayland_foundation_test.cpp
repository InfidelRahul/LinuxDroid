#include "wayland_foundation_test.h"
#include <wayland-server.h>
#include <libweston-16/libweston/libweston.h>
#include <libweston-16/libweston/version.h>
#include <pixman.h>
#include <xkbcommon/xkbcommon.h>
#include <sstream>
#include <cstring>
#include <android/log.h>

#define TAG "LinuxDroid-WaylandFoundation"

namespace linuxdroid {
namespace wayland {

FoundationStatus verifyWaylandFoundation() {
    FoundationStatus status;
    std::ostringstream ss;

    // 1. Verify Wayland Server
    struct wl_display *display = wl_display_create();
    if (display != nullptr) {
        status.wayland_server_ok = true;
        wl_display_destroy(display);
        ss << "Wayland server: OK; ";
    } else {
        ss << "Wayland server: FAILED; ";
    }

    // 2. Verify Weston / libweston 16
    int major = 0, minor = 0, micro = 0;
    weston_version(&major, &minor, &micro);
    if (major == 16 && WESTON_VERSION_MAJOR == 16) {
        status.libweston_ok = true;
        std::ostringstream ver_ss;
        ver_ss << major << "." << minor << "." << micro;
        status.weston_version_str = ver_ss.str();
        ss << "libweston: OK (" << status.weston_version_str << "); ";
    } else {
        ss << "libweston: FAILED (major=" << major << "); ";
    }

    // 3. Verify Pixman
    pixman_image_t *img = pixman_image_create_bits(PIXMAN_a8r8g8b8, 64, 64, nullptr, 0);
    if (img != nullptr) {
        status.pixman_ok = true;
        pixman_image_unref(img);
        ss << "Pixman: OK; ";
    } else {
        ss << "Pixman: FAILED; ";
    }

    // 4. Verify xkbcommon
    struct xkb_context *ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    if (ctx != nullptr) {
        status.xkbcommon_ok = true;
        xkb_context_unref(ctx);
        ss << "xkbcommon: OK; ";
    } else {
        ss << "xkbcommon: FAILED; ";
    }

    status.details = ss.str();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Wayland Foundation Verification: %s", status.details.c_str());
    return status;
}

} // namespace wayland
} // namespace linuxdroid
