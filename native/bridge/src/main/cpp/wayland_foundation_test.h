#pragma once

#include <string>

namespace linuxdroid {
namespace wayland {

struct FoundationStatus {
    bool wayland_server_ok = false;
    bool libweston_ok = false;
    bool pixman_ok = false;
    bool xkbcommon_ok = false;
    std::string weston_version_str;
    std::string details;

    bool allOk() const {
        return wayland_server_ok && libweston_ok && pixman_ok && xkbcommon_ok;
    }
};

FoundationStatus verifyWaylandFoundation();

} // namespace wayland
} // namespace linuxdroid
