#include "desktop_session.h"
#include <android/log.h>

#define LOG_TAG "LinuxDroid-DesktopSession"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace linuxdroid {

DesktopSession& DesktopSession::getInstance() {
    static DesktopSession instance;
    return instance;
}

DesktopSession::DesktopSession() = default;

DesktopSession::~DesktopSession() {
    stop();
}

bool DesktopSession::start(const std::string& wayland_socket) {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    if (running_.load()) {
        LOGI("DesktopSession is already running");
        return true;
    }

    socket_name_ = wayland_socket.empty() ? "wayland-0" : wayland_socket;
    LOGI("Starting DesktopSession on socket '%s'", socket_name_.c_str());

    // Initialize UI Shell Client
    shell_client_ = std::make_unique<DesktopShellClient>();

    // Pass initial display metrics from DesktopState to Shell Client
    auto metrics = DesktopState::getInstance().getDisplayMetrics();
    shell_client_->setOutputGeometry(metrics.width, metrics.height, metrics.scale);

    if (!catalog_.empty()) {
        shell_client_->updateApplicationCatalog(catalog_);
    }
    if (app_launch_handler_) {
        shell_client_->setAppLaunchHandler(app_launch_handler_);
    }

    if (!shell_client_->start(socket_name_.c_str())) {
        LOGE("Failed to start DesktopShellClient");
        shell_client_.reset();
        return false;
    }

    running_.store(true);
    LOGI("DesktopSession started successfully");
    return true;
}

bool DesktopSession::stop() {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    if (!running_.load()) {
        return true;
    }

    LOGI("Stopping DesktopSession");
    if (shell_client_) {
        shell_client_->stop();
        shell_client_.reset();
    }

    WindowModel::getInstance().clear();
    DesktopState::getInstance().reset();

    running_.store(false);
    LOGI("DesktopSession stopped successfully");
    return true;
}

bool DesktopSession::isRunning() const {
    return running_.load();
}

void DesktopSession::setOutputGeometry(int32_t width, int32_t height, int32_t scale) {
    DesktopState::getInstance().setDisplayGeometry(width, height, scale);
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    if (shell_client_) {
        shell_client_->setOutputGeometry(width, height, scale);
        shell_client_->renderAll();
    }
}

void DesktopSession::updateApplicationCatalog(const std::vector<LauncherMenuItem>& items) {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    catalog_ = items;
    if (shell_client_) {
        shell_client_->updateApplicationCatalog(items);
    }
}

std::vector<LauncherMenuItem> DesktopSession::getApplicationCatalog() const {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    return catalog_;
}

void DesktopSession::setAppLaunchHandler(DesktopShellClient::AppLaunchHandler handler) {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    app_launch_handler_ = std::move(handler);
    if (shell_client_) {
        shell_client_->setAppLaunchHandler(app_launch_handler_);
    }
}

} // namespace linuxdroid
