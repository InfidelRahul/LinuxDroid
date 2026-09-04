#include "window_manager.h"
#include <algorithm>

namespace linuxdroid {

WindowManager& WindowManager::getInstance() {
    static WindowManager instance;
    return instance;
}

WindowManager::WindowManager() = default;

void WindowManager::setNativeActionDispatcher(NativeActionDispatcher dispatcher) {
    std::lock_guard<std::mutex> lock(mutex_);
    dispatcher_ = std::move(dispatcher);
}

bool WindowManager::activateWindow(uint64_t id) {
    WindowState state;
    if (!WindowModel::getInstance().getWindow(id, &state)) {
        return false;
    }

    WindowModel::getInstance().raiseToTop(id);

    NativeActionDispatcher d;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        d = dispatcher_;
    }
    if (d && state.native_handle) {
        d(state.native_handle, "activate", 0, 0);
    }
    return true;
}

bool WindowManager::minimizeWindow(uint64_t id) {
    WindowState state;
    if (!WindowModel::getInstance().getWindow(id, &state)) {
        return false;
    }

    WindowModel::getInstance().setWindowMode(id, WindowMode::MINIMIZED);
    WindowModel::getInstance().setWindowActive(id, false);

    NativeActionDispatcher d;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        d = dispatcher_;
    }
    if (d && state.native_handle) {
        d(state.native_handle, "minimize", 0, 0);
    }
    return true;
}

bool WindowManager::maximizeWindow(uint64_t id, int32_t screen_w, int32_t screen_h, int32_t panel_h) {
    WindowState state;
    if (!WindowModel::getInstance().getWindow(id, &state)) {
        return false;
    }

    int32_t work_h = (screen_h > panel_h) ? (screen_h - panel_h) : screen_h;
    int32_t work_w = (screen_w > 0) ? screen_w : 1920;

    WindowModel::getInstance().setWindowGeometry(id, 0, 0, work_w, work_h);
    WindowModel::getInstance().setWindowMode(id, WindowMode::MAXIMIZED);
    WindowModel::getInstance().raiseToTop(id);

    NativeActionDispatcher d;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        d = dispatcher_;
    }
    if (d && state.native_handle) {
        d(state.native_handle, "maximize", work_w, work_h);
    }
    return true;
}

bool WindowManager::restoreWindow(uint64_t id) {
    WindowState state;
    if (!WindowModel::getInstance().getWindow(id, &state)) {
        return false;
    }

    WindowRect norm = state.normal_geometry;
    if (norm.width <= 0 || norm.height <= 0) {
        norm.width = 800;
        norm.height = 600;
    }

    WindowModel::getInstance().setWindowGeometry(id, norm.x, norm.y, norm.width, norm.height);
    WindowModel::getInstance().setWindowMode(id, WindowMode::NORMAL);
    WindowModel::getInstance().raiseToTop(id);

    NativeActionDispatcher d;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        d = dispatcher_;
    }
    if (d && state.native_handle) {
        d(state.native_handle, "restore", norm.width, norm.height);
    }
    return true;
}

bool WindowManager::toggleMaximize(uint64_t id, int32_t screen_w, int32_t screen_h, int32_t panel_h) {
    WindowState state;
    if (!WindowModel::getInstance().getWindow(id, &state)) {
        return false;
    }
    if (state.mode == WindowMode::MAXIMIZED) {
        return restoreWindow(id);
    } else {
        return maximizeWindow(id, screen_w, screen_h, panel_h);
    }
}

bool WindowManager::toggleMinimize(uint64_t id) {
    WindowState state;
    if (!WindowModel::getInstance().getWindow(id, &state)) {
        return false;
    }
    if (state.is_active) {
        return minimizeWindow(id);
    } else {
        return activateWindow(id);
    }
}

bool WindowManager::closeWindow(uint64_t id) {
    WindowState state;
    if (!WindowModel::getInstance().getWindow(id, &state)) {
        return false;
    }

    NativeActionDispatcher d;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        d = dispatcher_;
    }
    if (d && state.native_handle) {
        d(state.native_handle, "close", 0, 0);
    }
    return true;
}

void WindowManager::calculateCascadePosition(int32_t screen_w, int32_t screen_h, int32_t panel_h,
                                            int32_t win_w, int32_t win_h,
                                            int32_t* out_x, int32_t* out_y) {
    std::lock_guard<std::mutex> lock(mutex_);
    int32_t work_h = (screen_h > panel_h) ? (screen_h - panel_h) : screen_h;
    int32_t max_x = (screen_w > win_w) ? (screen_w - win_w) : 0;
    int32_t max_y = (work_h > win_h) ? (work_h - win_h) : 0;

    int32_t step = (cascade_step_++) % 8;
    int32_t offset = step * 36;

    int32_t x = 48 + offset;
    int32_t y = 32 + offset;

    if (x > max_x) x = (max_x > 0) ? (x % max_x) : 0;
    if (y > max_y) y = (max_y > 0) ? (y % max_y) : 0;

    if (out_x) *out_x = x;
    if (out_y) *out_y = y;
}

uint64_t WindowManager::cycleFocus(bool forward) {
    auto windows = WindowModel::getInstance().getWindows();
    if (windows.empty()) return 0;

    // Filter out minimized windows
    std::vector<WindowState> candidates;
    for (const auto& w : windows) {
        if (w.mode != WindowMode::MINIMIZED) {
            candidates.push_back(w);
        }
    }
    if (candidates.empty()) candidates = windows;

    std::sort(candidates.begin(), candidates.end(),
              [](const WindowState& a, const WindowState& b) {
                  return a.z_order > b.z_order;
              });

    size_t active_idx = 0;
    for (size_t i = 0; i < candidates.size(); ++i) {
        if (candidates[i].is_active) {
            active_idx = i;
            break;
        }
    }

    size_t target_idx = 0;
    if (forward) {
        target_idx = (active_idx + 1) % candidates.size();
    } else {
        target_idx = (active_idx == 0) ? (candidates.size() - 1) : (active_idx - 1);
    }

    uint64_t target_id = candidates[target_idx].id;
    activateWindow(target_id);
    return target_id;
}

} // namespace linuxdroid

