#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include <mutex>

namespace linuxdroid {

struct DesktopWindowEntry {
    uint64_t id{0};
    std::string app_id;
    std::string title;
    bool is_active{false};
    void* native_handle{nullptr}; // e.g. struct weston_desktop_surface*
};

class DesktopWindowTracker {
public:
    static DesktopWindowTracker& getInstance();

    // Window registration & lifecycle (called from compositor weston_desktop_api)
    void registerWindow(uint64_t id, const std::string& app_id, const std::string& title, void* handle);
    void updateWindowTitle(uint64_t id, const std::string& title);
    void setWindowActive(uint64_t id, bool active);
    void unregisterWindow(uint64_t id);

    // Window inspection & manipulation (called from Desktop Shell client)
    std::vector<DesktopWindowEntry> getWindows() const;
    bool getWindow(uint64_t id, DesktopWindowEntry* out) const;
    size_t getWindowCount() const;

    // Shell action requests (dispatched to compositor)
    using ActionHandler = std::function<void(uint64_t window_id, const std::string& action)>;
    void setActionHandler(ActionHandler handler);
    void requestActivate(uint64_t id);
    void requestClose(uint64_t id);

    // Change notification
    using ChangeListener = std::function<void()>;
    void setChangeListener(ChangeListener listener);

    void clear();

private:
    DesktopWindowTracker() = default;
    ~DesktopWindowTracker() = default;
    DesktopWindowTracker(const DesktopWindowTracker&) = delete;
    DesktopWindowTracker& operator=(const DesktopWindowTracker&) = delete;

    mutable std::mutex mutex_;
    std::vector<DesktopWindowEntry> windows_;
    ActionHandler action_handler_;
    ChangeListener change_listener_;
};

} // namespace linuxdroid
