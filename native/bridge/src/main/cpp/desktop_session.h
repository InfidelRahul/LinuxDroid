#pragma once

#include "desktop_state.h"
#include "window_model.h"
#include "window_manager.h"
#include "desktop_shell_client.h"

#include <memory>
#include <atomic>
#include <string>
#include <mutex>

namespace linuxdroid {

class DesktopSession {
public:
    static DesktopSession& getInstance();

    DesktopSession();
    ~DesktopSession();

    DesktopSession(const DesktopSession&) = delete;
    DesktopSession& operator=(const DesktopSession&) = delete;

    // Lifecycle
    bool start(const std::string& wayland_socket = "wayland-0");
    bool stop();
    bool isRunning() const;

    void setOutputGeometry(int32_t width, int32_t height, int32_t scale);

    void updateApplicationCatalog(const std::vector<LauncherMenuItem>& items);
    std::vector<LauncherMenuItem> getApplicationCatalog() const;
    void setAppLaunchHandler(DesktopShellClient::AppLaunchHandler handler);

    DesktopShellClient* getShellClient() const { return shell_client_.get(); }

private:
    std::atomic<bool> running_{false};
    mutable std::mutex lifecycle_mutex_;
    std::string socket_name_{"wayland-0"};
    std::unique_ptr<DesktopShellClient> shell_client_;
    std::vector<LauncherMenuItem> catalog_;
    DesktopShellClient::AppLaunchHandler app_launch_handler_;
};

} // namespace linuxdroid
