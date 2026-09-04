#include "desktop_window_tracker.h"
#include <algorithm>
#include <cinttypes>
#include <android/log.h>

#define TAG "LinuxDroid/WindowTracker"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

DesktopWindowTracker& DesktopWindowTracker::getInstance() {
    static DesktopWindowTracker instance;
    return instance;
}

void DesktopWindowTracker::registerWindow(uint64_t id, const std::string& app_id, const std::string& title, void* handle) {
    ChangeListener listener_copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        // Deactivate other windows by default when new window registers
        for (auto& w : windows_) {
            w.is_active = false;
        }

        DesktopWindowEntry entry;
        entry.id = id;
        entry.app_id = app_id;
        entry.title = title.empty() ? (app_id.empty() ? "Application" : app_id) : title;
        entry.is_active = true;
        entry.native_handle = handle;

        auto it = std::find_if(windows_.begin(), windows_.end(), [id](const DesktopWindowEntry& e) {
            return e.id == id;
        });
        if (it != windows_.end()) {
            *it = entry;
        } else {
            windows_.push_back(entry);
        }
        listener_copy = change_listener_;
    }
    LOGI("WINDOW_REGISTERED: id=%" PRIu64 " app_id='%s' title='%s'", id, app_id.c_str(), title.c_str());
    if (listener_copy) {
        listener_copy();
    }
}

void DesktopWindowTracker::updateWindowTitle(uint64_t id, const std::string& title) {
    ChangeListener listener_copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = std::find_if(windows_.begin(), windows_.end(), [id](const DesktopWindowEntry& e) {
            return e.id == id;
        });
        if (it != windows_.end()) {
            it->title = title;
            listener_copy = change_listener_;
        }
    }
    if (listener_copy) {
        listener_copy();
    }
}

void DesktopWindowTracker::setWindowActive(uint64_t id, bool active) {
    ChangeListener listener_copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& w : windows_) {
            if (w.id == id) {
                w.is_active = active;
            } else if (active) {
                w.is_active = false;
            }
        }
        listener_copy = change_listener_;
    }
    LOGI("WINDOW_ACTIVE_STATE: id=%" PRIu64 " active=%d", id, active ? 1 : 0);
    if (listener_copy) {
        listener_copy();
    }
}

void DesktopWindowTracker::unregisterWindow(uint64_t id) {
    ChangeListener listener_copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = std::remove_if(windows_.begin(), windows_.end(), [id](const DesktopWindowEntry& e) {
            return e.id == id;
        });
        if (it != windows_.end()) {
            windows_.erase(it, windows_.end());
            // If active window was closed, activate the last remaining window
            if (!windows_.empty()) {
                bool has_active = false;
                for (const auto& w : windows_) {
                    if (w.is_active) {
                        has_active = true;
                        break;
                    }
                }
                if (!has_active) {
                    windows_.back().is_active = true;
                }
            }
            listener_copy = change_listener_;
        }
    }
    LOGI("WINDOW_UNREGISTERED: id=%" PRIu64, id);
    if (listener_copy) {
        listener_copy();
    }
}

std::vector<DesktopWindowEntry> DesktopWindowTracker::getWindows() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return windows_;
}

bool DesktopWindowTracker::getWindow(uint64_t id, DesktopWindowEntry* out) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = std::find_if(windows_.begin(), windows_.end(), [id](const DesktopWindowEntry& e) {
        return e.id == id;
    });
    if (it != windows_.end()) {
        if (out) *out = *it;
        return true;
    }
    return false;
}

size_t DesktopWindowTracker::getWindowCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return windows_.size();
}

void DesktopWindowTracker::setActionHandler(ActionHandler handler) {
    std::lock_guard<std::mutex> lock(mutex_);
    action_handler_ = handler;
}

void DesktopWindowTracker::requestActivate(uint64_t id) {
    ActionHandler handler;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        handler = action_handler_;
    }
    if (handler) {
        handler(id, "activate");
    } else {
        setWindowActive(id, true);
    }
}

void DesktopWindowTracker::requestClose(uint64_t id) {
    ActionHandler handler;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        handler = action_handler_;
    }
    if (handler) {
        handler(id, "close");
    }
}

void DesktopWindowTracker::setChangeListener(ChangeListener listener) {
    std::lock_guard<std::mutex> lock(mutex_);
    change_listener_ = listener;
}

void DesktopWindowTracker::clear() {
    ChangeListener listener_copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        windows_.clear();
        action_handler_ = nullptr;
        listener_copy = change_listener_;
    }
    if (listener_copy) {
        listener_copy();
    }
}

} // namespace linuxdroid
