#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include <mutex>
#include <memory>

namespace linuxdroid {

enum class WindowMode {
    NORMAL = 0,
    MAXIMIZED = 1,
    MINIMIZED = 2,
    FULLSCREEN = 3
};

struct WindowRect {
    int32_t x{0};
    int32_t y{0};
    int32_t width{0};
    int32_t height{0};

    bool operator==(const WindowRect& o) const {
        return x == o.x && y == o.y && width == o.width && height == o.height;
    }
};

struct WindowState {
    uint64_t id{0};
    std::string app_id;
    std::string title;
    WindowRect geometry{};
    WindowRect normal_geometry{};
    WindowMode mode{WindowMode::NORMAL};
    bool is_active{false};
    uint32_t workspace{0};
    uint64_t z_order{0};
    void* native_handle{nullptr}; // struct weston_desktop_surface*
};

class WindowModel {
public:
    static WindowModel& getInstance();

    // Lifecycle
    uint64_t registerWindow(const std::string& app_id, const std::string& title,
                            void* handle, int32_t width, int32_t height,
                            uint32_t workspace = 0);
    bool unregisterWindow(uint64_t id);
    bool unregisterByHandle(void* handle);

    // Metadata & State Mutations
    bool setWindowTitle(uint64_t id, const std::string& title);
    bool setWindowGeometry(uint64_t id, int32_t x, int32_t y, int32_t width, int32_t height);
    bool setWindowMode(uint64_t id, WindowMode mode);
    bool setWindowActive(uint64_t id, bool active);
    bool setWindowWorkspace(uint64_t id, uint32_t workspace);
    bool raiseToTop(uint64_t id);

    // Queries
    std::vector<WindowState> getWindows(uint32_t workspace = ~0U) const;
    bool getWindow(uint64_t id, WindowState* out) const;
    bool getWindowByHandle(void* handle, WindowState* out) const;
    size_t getWindowCount(uint32_t workspace = ~0U) const;
    uint64_t getActiveWindowId() const;

    // Observers
    using ChangeListener = std::function<void()>;
    void addChangeListener(ChangeListener listener);
    void clearChangeListeners();

    void clear();

private:
    WindowModel();
    ~WindowModel() = default;
    WindowModel(const WindowModel&) = delete;
    WindowModel& operator=(const WindowModel&) = delete;

    void notifyListenersLocked();

    mutable std::mutex mutex_;
    std::vector<WindowState> windows_;
    uint64_t next_id_{1};
    uint64_t next_z_{1};
    std::vector<ChangeListener> listeners_;
};

} // namespace linuxdroid

