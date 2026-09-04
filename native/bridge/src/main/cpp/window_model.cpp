#include "window_model.h"
#include <algorithm>

namespace linuxdroid {

WindowModel& WindowModel::getInstance() {
    static WindowModel instance;
    return instance;
}

WindowModel::WindowModel() = default;

uint64_t WindowModel::registerWindow(const std::string& app_id, const std::string& title,
                                    void* handle, int32_t width, int32_t height,
                                    uint32_t workspace) {
    if (handle == nullptr) {
        return 0; // Strict invariant: Never register fake or null window handles
    }

    std::vector<ChangeListener> copy_listeners;
    uint64_t assigned_id = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        assigned_id = next_id_++;

        // Deactivate existing windows when new window is registered
        for (auto& w : windows_) {
            w.is_active = false;
        }

        WindowState state;
        state.id = assigned_id;
        state.app_id = app_id.empty() ? "application" : app_id;
        state.title = title.empty() ? state.app_id : title;
        state.geometry.width = (width > 0) ? width : 800;
        state.geometry.height = (height > 0) ? height : 600;
        state.normal_geometry = state.geometry;
        state.mode = WindowMode::NORMAL;
        state.is_active = true;
        state.workspace = workspace;
        state.z_order = next_z_++;
        state.native_handle = handle;

        windows_.push_back(std::move(state));
        copy_listeners = listeners_;
    }

    for (const auto& l : copy_listeners) {
        if (l) l();
    }
    return assigned_id;
}

bool WindowModel::unregisterWindow(uint64_t id) {
    std::vector<ChangeListener> copy_listeners;
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = std::find_if(windows_.begin(), windows_.end(),
                               [id](const WindowState& w) { return w.id == id; });
        if (it != windows_.end()) {
            bool was_active = it->is_active;
            windows_.erase(it);
            found = true;

            // If active window was removed, activate top remaining window
            if (was_active && !windows_.empty()) {
                auto top_it = std::max_element(windows_.begin(), windows_.end(),
                    [](const WindowState& a, const WindowState& b) {
                        return a.z_order < b.z_order;
                    });
                if (top_it != windows_.end()) {
                    top_it->is_active = true;
                }
            }
            copy_listeners = listeners_;
        }
    }

    if (found) {
        for (const auto& l : copy_listeners) {
            if (l) l();
        }
    }
    return found;
}

bool WindowModel::unregisterByHandle(void* handle) {
    if (handle == nullptr) return false;
    uint64_t target_id = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const auto& w : windows_) {
            if (w.native_handle == handle) {
                target_id = w.id;
                break;
            }
        }
    }
    if (target_id != 0) {
        return unregisterWindow(target_id);
    }
    return false;
}

bool WindowModel::setWindowTitle(uint64_t id, const std::string& title) {
    std::vector<ChangeListener> copy_listeners;
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& w : windows_) {
            if (w.id == id) {
                if (w.title == title) return true;
                w.title = title;
                found = true;
                copy_listeners = listeners_;
                break;
            }
        }
    }
    if (found) {
        for (const auto& l : copy_listeners) {
            if (l) l();
        }
    }
    return found;
}

bool WindowModel::setWindowGeometry(uint64_t id, int32_t x, int32_t y, int32_t width, int32_t height) {
    std::vector<ChangeListener> copy_listeners;
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& w : windows_) {
            if (w.id == id) {
                WindowRect r{x, y, width, height};
                if (w.geometry == r) return true;
                w.geometry = r;
                if (w.mode == WindowMode::NORMAL) {
                    w.normal_geometry = r;
                }
                found = true;
                copy_listeners = listeners_;
                break;
            }
        }
    }
    if (found) {
        for (const auto& l : copy_listeners) {
            if (l) l();
        }
    }
    return found;
}

bool WindowModel::setWindowMode(uint64_t id, WindowMode mode) {
    std::vector<ChangeListener> copy_listeners;
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& w : windows_) {
            if (w.id == id) {
                if (w.mode == mode) return true;
                w.mode = mode;
                found = true;
                copy_listeners = listeners_;
                break;
            }
        }
    }
    if (found) {
        for (const auto& l : copy_listeners) {
            if (l) l();
        }
    }
    return found;
}

bool WindowModel::setWindowActive(uint64_t id, bool active) {
    std::vector<ChangeListener> copy_listeners;
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& w : windows_) {
            if (w.id == id) {
                w.is_active = active;
                if (active) {
                    w.z_order = next_z_++;
                    if (w.mode == WindowMode::MINIMIZED) {
                        w.mode = WindowMode::NORMAL;
                    }
                }
                found = true;
            } else if (active) {
                w.is_active = false;
            }
        }
        if (found) copy_listeners = listeners_;
    }
    if (found) {
        for (const auto& l : copy_listeners) {
            if (l) l();
        }
    }
    return found;
}

bool WindowModel::setWindowWorkspace(uint64_t id, uint32_t workspace) {
    std::vector<ChangeListener> copy_listeners;
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& w : windows_) {
            if (w.id == id) {
                w.workspace = workspace;
                found = true;
                copy_listeners = listeners_;
                break;
            }
        }
    }
    if (found) {
        for (const auto& l : copy_listeners) {
            if (l) l();
        }
    }
    return found;
}

bool WindowModel::raiseToTop(uint64_t id) {
    return setWindowActive(id, true);
}

std::vector<WindowState> WindowModel::getWindows(uint32_t workspace) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (workspace == ~0U) {
        return windows_;
    }
    std::vector<WindowState> filtered;
    for (const auto& w : windows_) {
        if (w.workspace == workspace) {
            filtered.push_back(w);
        }
    }
    return filtered;
}

bool WindowModel::getWindow(uint64_t id, WindowState* out) const {
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& w : windows_) {
        if (w.id == id) {
            if (out) *out = w;
            return true;
        }
    }
    return false;
}

bool WindowModel::getWindowByHandle(void* handle, WindowState* out) const {
    if (handle == nullptr) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& w : windows_) {
        if (w.native_handle == handle) {
            if (out) *out = w;
            return true;
        }
    }
    return false;
}

size_t WindowModel::getWindowCount(uint32_t workspace) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (workspace == ~0U) {
        return windows_.size();
    }
    size_t count = 0;
    for (const auto& w : windows_) {
        if (w.workspace == workspace) ++count;
    }
    return count;
}

uint64_t WindowModel::getActiveWindowId() const {
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& w : windows_) {
        if (w.is_active) return w.id;
    }
    return 0;
}

void WindowModel::addChangeListener(ChangeListener listener) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (listener) {
        listeners_.push_back(std::move(listener));
    }
}

void WindowModel::clearChangeListeners() {
    std::lock_guard<std::mutex> lock(mutex_);
    listeners_.clear();
}

void WindowModel::clear() {
    std::vector<ChangeListener> copy_listeners;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        windows_.clear();
        copy_listeners = listeners_;
    }
    for (const auto& l : copy_listeners) {
        if (l) l();
    }
}

} // namespace linuxdroid

