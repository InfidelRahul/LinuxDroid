#include "desktop_state.h"

namespace linuxdroid {

DesktopState& DesktopState::getInstance() {
    static DesktopState instance;
    return instance;
}

DesktopState::DesktopState() {
    reset();
}

DesktopStateSnapshot DesktopState::getSnapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return snapshot_;
}

void DesktopState::setDisplayGeometry(int32_t width, int32_t height, int32_t scale, int32_t dpi) {
    std::vector<ChangeListener> copy_listeners;
    DesktopStateSnapshot copy_snap;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snapshot_.display.width = width;
        snapshot_.display.height = height;
        snapshot_.display.scale = (scale > 0) ? scale : 1;
        snapshot_.display.dpi = (dpi > 0) ? dpi : 160;

        // Adaptive layout breakpoint calculation
        int32_t effective_width = (scale > 1) ? (width / scale) : width;
        if (effective_width < 720) {
            snapshot_.display.layout_mode = DesktopLayoutMode::PHONE;
        } else if (effective_width < 1100) {
            snapshot_.display.layout_mode = DesktopLayoutMode::TABLET;
        } else {
            snapshot_.display.layout_mode = DesktopLayoutMode::DESKTOP;
        }

        copy_listeners = listeners_;
        copy_snap = snapshot_;
    }

    for (const auto& listener : copy_listeners) {
        if (listener) listener(copy_snap);
    }
}

DisplayMetrics DesktopState::getDisplayMetrics() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return snapshot_.display;
}

void DesktopState::setActiveWorkspace(uint32_t workspace) {
    std::vector<ChangeListener> copy_listeners;
    DesktopStateSnapshot copy_snap;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (snapshot_.active_workspace == workspace) return;
        snapshot_.active_workspace = workspace;
        copy_listeners = listeners_;
        copy_snap = snapshot_;
    }

    for (const auto& listener : copy_listeners) {
        if (listener) listener(copy_snap);
    }
}

uint32_t DesktopState::getActiveWorkspace() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return snapshot_.active_workspace;
}

void DesktopState::setFocusedWindow(uint64_t window_id) {
    std::vector<ChangeListener> copy_listeners;
    DesktopStateSnapshot copy_snap;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (snapshot_.focused_window_id == window_id) return;
        snapshot_.focused_window_id = window_id;
        copy_listeners = listeners_;
        copy_snap = snapshot_;
    }

    for (const auto& listener : copy_listeners) {
        if (listener) listener(copy_snap);
    }
}

uint64_t DesktopState::getFocusedWindow() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return snapshot_.focused_window_id;
}

void DesktopState::setLauncherVisible(bool visible) {
    std::vector<ChangeListener> copy_listeners;
    DesktopStateSnapshot copy_snap;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (snapshot_.launcher_visible == visible) return;
        snapshot_.launcher_visible = visible;
        copy_listeners = listeners_;
        copy_snap = snapshot_;
    }

    for (const auto& listener : copy_listeners) {
        if (listener) listener(copy_snap);
    }
}

bool DesktopState::isLauncherVisible() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return snapshot_.launcher_visible;
}

void DesktopState::toggleLauncher() {
    setLauncherVisible(!isLauncherVisible());
}

void DesktopState::updateBattery(int32_t percent, bool charging) {
    std::vector<ChangeListener> copy_listeners;
    DesktopStateSnapshot copy_snap;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snapshot_.system.battery_percent = percent;
        snapshot_.system.is_charging = charging;
        copy_listeners = listeners_;
        copy_snap = snapshot_;
    }

    for (const auto& listener : copy_listeners) {
        if (listener) listener(copy_snap);
    }
}

void DesktopState::updateNetwork(bool connected, const std::string& name) {
    std::vector<ChangeListener> copy_listeners;
    DesktopStateSnapshot copy_snap;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snapshot_.system.network_connected = connected;
        snapshot_.system.network_name = name;
        copy_listeners = listeners_;
        copy_snap = snapshot_;
    }

    for (const auto& listener : copy_listeners) {
        if (listener) listener(copy_snap);
    }
}

void DesktopState::updateClock(const std::string& clock_str) {
    std::vector<ChangeListener> copy_listeners;
    DesktopStateSnapshot copy_snap;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snapshot_.system.clock_str = clock_str;
        copy_listeners = listeners_;
        copy_snap = snapshot_;
    }

    for (const auto& listener : copy_listeners) {
        if (listener) listener(copy_snap);
    }
}

void DesktopState::setTheme(const ThemeTokens& theme) {
    std::vector<ChangeListener> copy_listeners;
    DesktopStateSnapshot copy_snap;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snapshot_.theme = theme;
        copy_listeners = listeners_;
        copy_snap = snapshot_;
    }

    for (const auto& listener : copy_listeners) {
        if (listener) listener(copy_snap);
    }
}

ThemeTokens DesktopState::getTheme() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return snapshot_.theme;
}

void DesktopState::addChangeListener(ChangeListener listener) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (listener) {
        listeners_.push_back(std::move(listener));
    }
}

void DesktopState::clearChangeListeners() {
    std::lock_guard<std::mutex> lock(mutex_);
    listeners_.clear();
}

void DesktopState::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    snapshot_ = DesktopStateSnapshot{};
}

} // namespace linuxdroid

