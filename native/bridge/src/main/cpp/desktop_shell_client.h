#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <memory>
#include <thread>
#include <atomic>
#include <mutex>
#include <wayland-client.h>
#include <xkbcommon/xkbcommon.h>
#include "protocol/xdg-shell-client-protocol.h"
#include "desktop_window_tracker.h"

namespace linuxdroid {

struct ShmBuffer {
    struct wl_buffer* buffer{nullptr};
    uint32_t* pixels{nullptr};
    int width{0};
    int height{0};
    int stride{0};
    size_t size{0};
    int fd{-1};

    void destroy();
};

struct LauncherMenuItem {
    std::string name;
    std::string exec_path;
    std::string description;
};

class DesktopShellClient {
public:
    DesktopShellClient();
    ~DesktopShellClient();

    // Lifecycle
    bool start(const char* socket_name = "wayland-0");
    void stop();
    bool isRunning() const { return running_.load(std::memory_order_relaxed); }

    // Geometry & Output
    void setOutputGeometry(int32_t width, int32_t height, int32_t scale);
    int32_t getWidth() const { return width_; }
    int32_t getHeight() const { return height_; }

    // UI State & Actions
    void toggleLauncher();
    void setLauncherOpen(bool open);
    bool isLauncherOpen() const { return launcher_open_; }

    void selectNextLauncherItem();
    void selectPrevLauncherItem();
    void activateSelectedLauncherItem();

    void launchApplication(size_t index);
    bool launchCustomCommand(const std::string& path, const std::vector<std::string>& args);

    // Manual / Test triggering of redraw
    void renderAll();
    void renderBackground();
    void renderPanel();
    void renderLauncher();

private:
    void threadMain(std::string socket_name);
    bool initWayland(const char* socket_name);
    void cleanupWayland();

    ShmBuffer createShmBuffer(int width, int height);

    // Drawing helpers
    void drawRect(ShmBuffer& buf, int x, int y, int w, int h, uint32_t color);
    void drawFilledRect(ShmBuffer& buf, int x, int y, int w, int h, uint32_t color);
    void drawText(ShmBuffer& buf, int x, int y, const char* text, uint32_t color);

    // Wayland listener callbacks
    static void registryHandleGlobal(void* data, struct wl_registry* reg, uint32_t name, const char* iface, uint32_t ver);
    static void registryHandleGlobalRemove(void* data, struct wl_registry* reg, uint32_t name);

    static void outputHandleGeometry(void* data, struct wl_output* output, int32_t x, int32_t y,
                                     int32_t physical_width, int32_t physical_height,
                                     int32_t subpixel, const char* make, const char* model, int32_t transform);
    static void outputHandleMode(void* data, struct wl_output* output, uint32_t flags,
                                 int32_t width, int32_t height, int32_t refresh);
    static void outputHandleDone(void* data, struct wl_output* output);
    static void outputHandleScale(void* data, struct wl_output* output, int32_t factor);

    static void xdgWmBaseHandlePing(void* data, struct xdg_wm_base* shell, uint32_t serial);

    static void seatHandleCapabilities(void* data, struct wl_seat* seat, uint32_t caps);
    static void seatHandleName(void* data, struct wl_seat* seat, const char* name);

    static void pointerHandleEnter(void* data, struct wl_pointer* pointer, uint32_t serial,
                                   struct wl_surface* surface, wl_fixed_t sx, wl_fixed_t sy);
    static void pointerHandleLeave(void* data, struct wl_pointer* pointer, uint32_t serial,
                                   struct wl_surface* surface);
    static void pointerHandleMotion(void* data, struct wl_pointer* pointer, uint32_t time,
                                    wl_fixed_t sx, wl_fixed_t sy);
    static void pointerHandleButton(void* data, struct wl_pointer* pointer, uint32_t serial,
                                    uint32_t time, uint32_t button, uint32_t state);
    static void pointerHandleAxis(void* data, struct wl_pointer* pointer, uint32_t time,
                                  uint32_t axis, wl_fixed_t value);
    static void pointerHandleFrame(void* data, struct wl_pointer* pointer);
    static void pointerHandleAxisSource(void* data, struct wl_pointer* pointer, uint32_t axis_source);
    static void pointerHandleAxisStop(void* data, struct wl_pointer* pointer, uint32_t time, uint32_t axis);
    static void pointerHandleAxisDiscrete(void* data, struct wl_pointer* pointer, uint32_t axis, int32_t discrete);

    static void touchHandleDown(void* data, struct wl_touch* touch, uint32_t serial, uint32_t time,
                                struct wl_surface* surface, int32_t id, wl_fixed_t x, wl_fixed_t y);
    static void touchHandleUp(void* data, struct wl_touch* touch, uint32_t serial, uint32_t time, int32_t id);
    static void touchHandleMotion(void* data, struct wl_touch* touch, uint32_t time, int32_t id,
                                  wl_fixed_t x, wl_fixed_t y);
    static void touchHandleFrame(void* data, struct wl_touch* touch);
    static void touchHandleCancel(void* data, struct wl_touch* touch);

    static void keyboardHandleKeymap(void* data, struct wl_keyboard* kbd, uint32_t format, int32_t fd, uint32_t size);
    static void keyboardHandleEnter(void* data, struct wl_keyboard* kbd, uint32_t serial,
                                    struct wl_surface* surface, struct wl_array* keys);
    static void keyboardHandleLeave(void* data, struct wl_keyboard* kbd, uint32_t serial, struct wl_surface* surface);
    static void keyboardHandleKey(void* data, struct wl_keyboard* kbd, uint32_t serial, uint32_t time,
                                  uint32_t key, uint32_t state);
    static void keyboardHandleModifiers(void* data, struct wl_keyboard* kbd, uint32_t serial,
                                        uint32_t mods_depressed, uint32_t mods_latched,
                                        uint32_t mods_locked, uint32_t group);
    static void keyboardHandleRepeatInfo(void* data, struct wl_keyboard* kbd, int32_t rate, int32_t delay);

    // Surface configure handlers
    static void bgSurfaceConfigure(void* data, struct xdg_surface* surface, uint32_t serial);
    static void bgToplevelConfigure(void* data, struct xdg_toplevel* toplevel, int32_t width, int32_t height, struct wl_array* states);
    static void bgToplevelClose(void* data, struct xdg_toplevel* toplevel);

    static void panelSurfaceConfigure(void* data, struct xdg_surface* surface, uint32_t serial);
    static void panelToplevelConfigure(void* data, struct xdg_toplevel* toplevel, int32_t width, int32_t height, struct wl_array* states);
    static void panelToplevelClose(void* data, struct xdg_toplevel* toplevel);

    static void launcherSurfaceConfigure(void* data, struct xdg_surface* surface, uint32_t serial);
    static void launcherToplevelConfigure(void* data, struct xdg_toplevel* toplevel, int32_t width, int32_t height, struct wl_array* states);
    static void launcherToplevelClose(void* data, struct xdg_toplevel* toplevel);

    // Hit testing
    void handlePointerClick(struct wl_surface* surface, double x, double y);

    std::atomic<bool> running_{false};
    std::thread thread_;
    std::mutex render_mutex_;

    int32_t width_{1080};
    int32_t height_{1920};
    int32_t scale_{1};
    int32_t panel_height_{48};
    int32_t launcher_width_{280};
    int32_t launcher_height_{280};

    bool launcher_open_{false};
    int selected_launcher_item_{0};

    std::vector<LauncherMenuItem> launcher_menu_;

    // Wayland Objects
    struct wl_display* display_{nullptr};
    struct wl_registry* registry_{nullptr};
    struct wl_compositor* compositor_{nullptr};
    struct wl_shm* shm_{nullptr};
    struct wl_seat* seat_{nullptr};
    struct wl_output* output_{nullptr};
    struct xdg_wm_base* xdg_wm_base_{nullptr};

    struct wl_pointer* pointer_{nullptr};
    struct wl_keyboard* keyboard_{nullptr};
    struct wl_touch* touch_{nullptr};

    // XKB Context
    struct xkb_context* xkb_ctx_{nullptr};
    struct xkb_keymap* xkb_keymap_{nullptr};
    struct xkb_state* xkb_state_{nullptr};

    // Surfaces & Buffers
    struct wl_surface* bg_surface_{nullptr};
    struct xdg_surface* bg_xdg_surface_{nullptr};
    struct xdg_toplevel* bg_xdg_toplevel_{nullptr};
    ShmBuffer bg_buffer_;

    struct wl_surface* panel_surface_{nullptr};
    struct xdg_surface* panel_xdg_surface_{nullptr};
    struct xdg_toplevel* panel_xdg_toplevel_{nullptr};
    ShmBuffer panel_buffer_;

    struct wl_surface* launcher_surface_{nullptr};
    struct xdg_surface* launcher_xdg_surface_{nullptr};
    struct xdg_toplevel* launcher_xdg_toplevel_{nullptr};
    ShmBuffer launcher_buffer_;

    struct wl_surface* active_pointer_surface_{nullptr};
    double pointer_x_{0.0};
    double pointer_y_{0.0};

    int wake_pipe_[2]{-1, -1};
};

} // namespace linuxdroid
