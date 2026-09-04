#pragma once

#include "window_model.h"
#include <functional>
#include <mutex>
#include <string>

namespace linuxdroid {

class WindowManager {
public:
    static WindowManager& getInstance();

    // Native compositor action callback
    // (dispatched to libweston compositor event loop)
    using NativeActionDispatcher = std::function<void(void* handle, const std::string& action, int32_t p1, int32_t p2)>;
    void setNativeActionDispatcher(NativeActionDispatcher dispatcher);

    // Window Policy Actions
    bool activateWindow(uint64_t id);
    bool minimizeWindow(uint64_t id);
    bool maximizeWindow(uint64_t id, int32_t screen_w, int32_t screen_h, int32_t panel_h);
    bool restoreWindow(uint64_t id);
    bool toggleMaximize(uint64_t id, int32_t screen_w, int32_t screen_h, int32_t panel_h);
    bool toggleMinimize(uint64_t id);
    bool closeWindow(uint64_t id);

    // Initial Layout & Placement Calculation
    void calculateCascadePosition(int32_t screen_w, int32_t screen_h, int32_t panel_h,
                                  int32_t win_w, int32_t win_h,
                                  int32_t* out_x, int32_t* out_y);

    // Cycle focus (Alt+Tab)
    uint64_t cycleFocus(bool forward = true);

private:
    WindowManager();
    ~WindowManager() = default;
    WindowManager(const WindowManager&) = delete;
    WindowManager& operator=(const WindowManager&) = delete;

    mutable std::mutex mutex_;
    NativeActionDispatcher dispatcher_;
    uint32_t cascade_step_{0};
};

} // namespace linuxdroid

