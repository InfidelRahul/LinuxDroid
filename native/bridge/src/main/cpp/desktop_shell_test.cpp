#include "desktop_shell_client.h"
#include "desktop_window_tracker.h"
#include "desktop_session.h"
#include "desktop_state.h"
#include "gui_host.h"
#include "linuxdroid_backend.h"
#include "input_bridge.h"

#include <wayland-server.h>
#include <wayland-client.h>
#include <libweston/libweston.h>
#include <libweston/weston-log.h>
#include <libweston/desktop.h>

#include <cstdio>
#include <cstdlib>
#include <cassert>
#include <cstring>
#include <unistd.h>
#include <sys/socket.h>

using namespace linuxdroid;
using namespace linuxdroid::gui;

static void test_wayland_connection_and_globals() {
    printf("[RUN] test_wayland_connection_and_globals\n");

    if (!getenv("XDG_RUNTIME_DIR")) setenv("XDG_RUNTIME_DIR", "/tmp", 0);

    struct wl_display* server_display = wl_display_create();
    assert(server_display != nullptr);
    wl_display_init_shm(server_display);

    struct weston_log_context* log_ctx = weston_log_ctx_create();
    assert(log_ctx != nullptr);

    struct weston_compositor* compositor = weston_compositor_create(server_display, log_ctx, nullptr, nullptr);
    assert(compositor != nullptr);

    struct linuxdroid_backend_config config = { .refresh_mhz = 60000 };
    struct linuxdroid_backend* backend = linuxdroid_backend_create(compositor, &config);
    assert(backend != nullptr);

    static const struct weston_desktop_api d_api = {
        .struct_size = sizeof(struct weston_desktop_api),
    };
    struct weston_desktop* desktop = weston_desktop_create(compositor, &d_api, nullptr);
    assert(desktop != nullptr);

    int sv[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0);

    struct wl_client* server_client = wl_client_create(server_display, sv[0]);
    assert(server_client != nullptr);

    struct wl_display* client_display = wl_display_connect_to_fd(sv[1]);
    assert(client_display != nullptr);

    struct TestContext {
        struct wl_compositor* comp{nullptr};
        struct wl_shm* shm{nullptr};
        struct wl_seat* seat{nullptr};
        struct xdg_wm_base* wm_base{nullptr};
    } ctx;

    static const struct wl_registry_listener reg_listener = {
        .global = [](void* data, struct wl_registry* reg, uint32_t name, const char* iface, uint32_t ver) {
            auto* c = static_cast<TestContext*>(data);
            if (strcmp(iface, wl_compositor_interface.name) == 0) {
                c->comp = static_cast<struct wl_compositor*>(wl_registry_bind(reg, name, &wl_compositor_interface, 4));
            } else if (strcmp(iface, wl_shm_interface.name) == 0) {
                c->shm = static_cast<struct wl_shm*>(wl_registry_bind(reg, name, &wl_shm_interface, 1));
            } else if (strcmp(iface, wl_seat_interface.name) == 0) {
                c->seat = static_cast<struct wl_seat*>(wl_registry_bind(reg, name, &wl_seat_interface, 7));
            } else if (strcmp(iface, xdg_wm_base_interface.name) == 0) {
                c->wm_base = static_cast<struct xdg_wm_base*>(wl_registry_bind(reg, name, &xdg_wm_base_interface, 1));
            }
        },
        .global_remove = [](void*, struct wl_registry*, uint32_t) {}
    };

    struct wl_registry* reg = wl_display_get_registry(client_display);
    wl_registry_add_listener(reg, &reg_listener, &ctx);

    wl_display_flush_clients(server_display);
    wl_display_roundtrip(client_display);

    assert(ctx.comp != nullptr);
    assert(ctx.shm != nullptr);
    assert(ctx.seat != nullptr);
    assert(ctx.wm_base != nullptr);

    xdg_wm_base_destroy(ctx.wm_base);
    wl_seat_destroy(ctx.seat);
    wl_shm_destroy(ctx.shm);
    wl_compositor_destroy(ctx.comp);
    wl_registry_destroy(reg);
    wl_display_disconnect(client_display);

    weston_desktop_destroy(desktop);
    weston_compositor_destroy(compositor);
    weston_log_ctx_destroy(log_ctx);
    wl_display_destroy(server_display);

    printf("[PASS] test_wayland_connection_and_globals\n");
}

static void test_shell_lifecycle_and_restart() {
    printf("[RUN] test_shell_lifecycle_and_restart\n");

    if (!getenv("XDG_RUNTIME_DIR")) setenv("XDG_RUNTIME_DIR", "/tmp", 0);

    // Verify DesktopShellClient start, stop, and restart
    DesktopShellClient client;
    assert(!client.isRunning());

    // Without compositor socket, start returns false or thread exits cleanly
    // Now start GuiHost
    bool started = GuiHost::getInstance().start();
    assert(started);
    assert(GuiHost::getInstance().isRunning());

    // Wait for server to bind wayland-0 socket
    usleep(100000);

    DesktopShellClient test_client;
    bool client_started = test_client.start("wayland-0");
    assert(client_started);
    usleep(100000);
    assert(test_client.isRunning());

    // Stop client cleanly
    test_client.stop();
    assert(!test_client.isRunning());

    // Restart client cleanly on same socket
    bool restarted = test_client.start("wayland-0");
    assert(restarted);
    usleep(100000);
    assert(test_client.isRunning());

    test_client.stop();
    assert(!test_client.isRunning());

    GuiHost::getInstance().stop();
    assert(!GuiHost::getInstance().isRunning());

    printf("[PASS] test_shell_lifecycle_and_restart\n");
}

static void test_geometry_and_resize() {
    printf("[RUN] test_geometry_and_resize\n");

    DesktopShellClient client;
    client.setOutputGeometry(1080, 2400, 1);
    assert(client.getWidth() == 1080);
    assert(client.getHeight() == 2400);

    client.setOutputGeometry(2400, 1080, 2);
    assert(client.getWidth() == 2400);
    assert(client.getHeight() == 1080);

    client.renderAll();

    printf("[PASS] test_geometry_and_resize\n");
}

static void test_launcher_ui_and_navigation() {
    printf("[RUN] test_launcher_ui_and_navigation\n");

    DesktopShellClient client;
    assert(!client.isLauncherOpen());

    client.toggleLauncher();
    assert(client.isLauncherOpen());

    client.selectNextLauncherItem();
    client.selectNextLauncherItem();
    client.selectPrevLauncherItem();

    client.setLauncherOpen(false);
    assert(!client.isLauncherOpen());

    printf("[PASS] test_launcher_ui_and_navigation\n");
}

static void test_window_list_and_application_launch() {
    printf("[RUN] test_window_list_and_application_launch\n");

    auto& tracker = DesktopWindowTracker::getInstance();
    tracker.clear();
    assert(tracker.getWindowCount() == 0);

    DesktopShellClient client;

    // Verify AppLaunchHandler receives launch requests
    std::string launched_app, launched_path;
    client.setAppLaunchHandler([&](const std::string& name, const std::string& path) {
        launched_app = name;
        launched_path = path;
    });

    // Launch first application (/bin/bash)
    client.launchApplication(0);
    assert(launched_app == "Terminal");
    assert(launched_path == "/bin/bash");

    // Prohibit fake windows: launchApplication does NOT fabricate windows into tracker
    assert(tracker.getWindowCount() == 0);

    // Attempting to register a window with nullptr native handle MUST be rejected
    bool null_reg = tracker.registerWindow(9999, "fake", "Fake Window", nullptr);
    assert(!null_reg);
    assert(tracker.getWindowCount() == 0);

    // Register legitimate window with valid native handle
    void* mock_surface_1 = reinterpret_cast<void*>(0x1000);
    void* mock_surface_2 = reinterpret_cast<void*>(0x2000);
    bool reg1 = tracker.registerWindow(1001, "Terminal", "Terminal", mock_surface_1);
    assert(reg1);
    assert(tracker.getWindowCount() == 1);
    auto windows = tracker.getWindows();
    assert(windows.size() == 1);
    assert(windows[0].is_active);
    assert(windows[0].app_id == "Terminal");
    assert(windows[0].native_handle == mock_surface_1);

    // Register second window
    bool reg2 = tracker.registerWindow(2001, "editor", "Text Editor", mock_surface_2);
    assert(reg2);
    assert(tracker.getWindowCount() == 2);

    windows = tracker.getWindows();
    assert(windows[1].is_active);
    assert(!windows[0].is_active);

    // Request activation of first window
    tracker.requestActivate(windows[0].id);
    windows = tracker.getWindows();
    assert(windows[0].is_active);

    // Close second window
    tracker.unregisterWindow(2001);
    assert(tracker.getWindowCount() == 1);

    tracker.clear();
    assert(tracker.getWindowCount() == 0);

    printf("[PASS] test_window_list_and_application_launch\n");
}

static void test_desktop_state() {
    printf("[RUN] test_desktop_state\n");

    auto& state = DesktopState::getInstance();
    state.reset();

    // 1. Initial defaults
    auto metrics = state.getDisplayMetrics();
    assert(metrics.width == 1920);
    assert(metrics.height == 1080);
    assert(metrics.layout_mode == DesktopLayoutMode::DESKTOP);

    // 2. Display geometry updates
    state.setDisplayGeometry(1080, 2400, 2, 420);
    metrics = state.getDisplayMetrics();
    assert(metrics.width == 1080);
    assert(metrics.height == 2400);
    assert(metrics.scale == 2);
    assert(metrics.dpi == 420);
    assert(metrics.layout_mode == DesktopLayoutMode::PHONE);

    // 3. Workspace & focus
    state.setActiveWorkspace(2);
    assert(state.getActiveWorkspace() == 2);

    state.setFocusedWindow(42);
    assert(state.getFocusedWindow() == 42);

    // 4. Launcher visibility toggle
    assert(!state.isLauncherVisible());
    state.toggleLauncher();
    assert(state.isLauncherVisible());
    state.setLauncherVisible(false);
    assert(!state.isLauncherVisible());

    // 5. Change listener notifications
    bool listener_called = false;
    DesktopStateSnapshot snapshot_received;
    state.addChangeListener([&](const DesktopStateSnapshot& s) {
        listener_called = true;
        snapshot_received = s;
    });

    state.updateBattery(85, true);
    assert(listener_called);
    assert(snapshot_received.system.battery_percent == 85);
    assert(snapshot_received.system.is_charging);

    listener_called = false;
    state.updateNetwork(true, "wlan0");
    assert(listener_called);
    assert(snapshot_received.system.network_connected);
    assert(snapshot_received.system.network_name == "wlan0");

    state.clearChangeListeners();
    state.reset();

    printf("[PASS] test_desktop_state\n");
}

static void test_window_model() {
    printf("[RUN] test_window_model\n");

    auto& model = WindowModel::getInstance();
    model.clear();
    assert(model.getWindowCount() == 0);

    // 1. Invariant: Null handle registration MUST be rejected
    uint64_t null_id = model.registerWindow("fake", "Fake Window", nullptr, 800, 600);
    assert(null_id == 0);
    assert(model.getWindowCount() == 0);

    // 2. Legitimate window registration
    void* handle1 = reinterpret_cast<void*>(0x1111);
    uint64_t id1 = model.registerWindow("org.gnome.Terminal", "Terminal", handle1, 1024, 768, 0);
    assert(id1 > 0);
    assert(model.getWindowCount() == 1);

    WindowState st1;
    assert(model.getWindow(id1, &st1));
    assert(st1.id == id1);
    assert(st1.app_id == "org.gnome.Terminal");
    assert(st1.title == "Terminal");
    assert(st1.geometry.width == 1024);
    assert(st1.geometry.height == 768);
    assert(st1.mode == WindowMode::NORMAL);
    assert(st1.is_active);
    assert(st1.native_handle == handle1);

    // 3. Register second window (verifies focus transfer and Z-ordering)
    void* handle2 = reinterpret_cast<void*>(0x2222);
    uint64_t id2 = model.registerWindow("org.gnome.gedit", "Text Editor", handle2, 800, 600, 0);
    assert(id2 > id1);
    assert(model.getWindowCount() == 2);

    assert(model.getWindow(id1, &st1));
    WindowState st2;
    assert(model.getWindow(id2, &st2));
    assert(!st1.is_active);
    assert(st2.is_active);
    assert(st2.z_order > st1.z_order);

    // 4. Mutations: Title, Geometry, Mode
    model.setWindowTitle(id1, "Terminal - user@linuxdroid");
    assert(model.getWindow(id1, &st1));
    assert(st1.title == "Terminal - user@linuxdroid");

    model.setWindowGeometry(id1, 100, 150, 1200, 800);
    assert(model.getWindow(id1, &st1));
    assert(st1.geometry.x == 100);
    assert(st1.geometry.y == 150);
    assert(st1.geometry.width == 1200);
    assert(st1.geometry.height == 800);

    model.setWindowMode(id1, WindowMode::MAXIMIZED);
    assert(model.getWindow(id1, &st1));
    assert(st1.mode == WindowMode::MAXIMIZED);

    // 5. Query by handle
    WindowState found_by_handle;
    assert(model.getWindowByHandle(handle2, &found_by_handle));
    assert(found_by_handle.id == id2);

    // 6. Raise to top
    model.raiseToTop(id1);
    assert(model.getWindow(id1, &st1));
    assert(model.getWindow(id2, &st2));
    assert(st1.is_active);
    assert(st1.z_order > st2.z_order);

    // 7. Workspace filtering
    model.setWindowWorkspace(id2, 1);
    assert(model.getWindowCount(0) == 1);
    assert(model.getWindowCount(1) == 1);
    assert(model.getWindowCount() == 2);

    // 8. Unregister
    assert(model.unregisterWindow(id1));
    assert(model.getWindowCount() == 1);
    assert(!model.getWindow(id1, nullptr));

    assert(model.unregisterByHandle(handle2));
    assert(model.getWindowCount() == 0);

    model.clear();
    printf("[PASS] test_window_model\n");
}

static void test_window_manager() {
    printf("[RUN] test_window_manager\n");

    auto& model = WindowModel::getInstance();
    auto& wm = WindowManager::getInstance();
    model.clear();

    void* handle1 = reinterpret_cast<void*>(0x3333);
    uint64_t id1 = model.registerWindow("foot", "Foot Terminal", handle1, 800, 600);

    std::string last_action;
    void* last_handle = nullptr;
    int32_t last_p1 = 0, last_p2 = 0;

    wm.setNativeActionDispatcher([&](void* h, const std::string& act, int32_t p1, int32_t p2) {
        last_handle = h;
        last_action = act;
        last_p1 = p1;
        last_p2 = p2;
    });

    // 1. Activate
    assert(wm.activateWindow(id1));
    assert(last_action == "activate");
    assert(last_handle == handle1);

    // 2. Minimize
    assert(wm.minimizeWindow(id1));
    assert(last_action == "minimize");
    WindowState st;
    assert(model.getWindow(id1, &st));
    assert(st.mode == WindowMode::MINIMIZED);
    assert(!st.is_active);

    // 3. Maximize
    assert(wm.maximizeWindow(id1, 1920, 1080, 48));
    assert(last_action == "maximize");
    assert(last_p1 == 1920);
    assert(last_p2 == 1032);
    assert(model.getWindow(id1, &st));
    assert(st.mode == WindowMode::MAXIMIZED);
    assert(st.geometry.width == 1920);
    assert(st.geometry.height == 1032);

    // 4. Restore
    assert(wm.restoreWindow(id1));
    assert(last_action == "restore");
    assert(model.getWindow(id1, &st));
    assert(st.mode == WindowMode::NORMAL);
    assert(st.geometry.width == 800);
    assert(st.geometry.height == 600);

    // 5. Toggle Maximize & Minimize
    assert(wm.toggleMaximize(id1, 1920, 1080, 48));
    assert(model.getWindow(id1, &st));
    assert(st.mode == WindowMode::MAXIMIZED);

    assert(wm.toggleMaximize(id1, 1920, 1080, 48));
    assert(model.getWindow(id1, &st));
    assert(st.mode == WindowMode::NORMAL);

    // 6. Close
    assert(wm.closeWindow(id1));
    assert(last_action == "close");

    // 7. Cascade layout positioning
    int32_t x1 = 0, y1 = 0, x2 = 0, y2 = 0;
    wm.calculateCascadePosition(1920, 1080, 48, 800, 600, &x1, &y1);
    wm.calculateCascadePosition(1920, 1080, 48, 800, 600, &x2, &y2);
    assert(x2 > x1);
    assert(y2 > y1);

    // 8. Focus cycling (Alt+Tab)
    void* handle2 = reinterpret_cast<void*>(0x4444);
    uint64_t id2 = model.registerWindow("editor", "Editor", handle2, 800, 600);
    uint64_t cycled = wm.cycleFocus(true);
    assert(cycled == id1 || cycled == id2);

    wm.setNativeActionDispatcher(nullptr);
    model.clear();
    printf("[PASS] test_window_manager\n");
}

static void test_ui_painter() {
    printf("[RUN] test_ui_painter\n");

    const int w = 200, h = 200;
    std::vector<uint32_t> buffer(w * h, 0);

    UIPainter painter(buffer.data(), w, h);

    // 1. Clear with color
    uint32_t bg = UIPainter::rgba(15, 23, 42, 255);
    painter.clear(bg);
    assert(buffer[0] == bg);
    assert(buffer[w * h - 1] == bg);

    // 2. Draw Rect and Rounded Rect
    uint32_t rect_color = UIPainter::rgba(56, 189, 248, 255);
    painter.drawFilledRect(10, 10, 50, 50, rect_color);
    assert(buffer[15 * w + 15] == rect_color);

    painter.drawRoundedRect(70, 10, 50, 50, 6, rect_color);
    assert(buffer[15 * w + 75] == rect_color);

    // 3. Vertical Gradient
    painter.drawLinearGradient(0, 100, w, 50, UIPainter::rgba(255, 0, 0, 255), UIPainter::rgba(0, 0, 255, 255), true);
    // Check top and bottom rows of gradient
    assert(buffer[100 * w + 10] != buffer[149 * w + 10]);

    // 4. Text and font scaling
    painter.drawText(10, 160, "LinuxDroid", UIPainter::rgba(255, 255, 255, 255), 1);
    int text_w = painter.getTextWidth("LinuxDroid", 1);
    assert(text_w == 10 * 8);

    // 5. Icons
    painter.drawLauncherIcon(10, 10, 24, UIPainter::rgba(255, 255, 255, 255));
    painter.drawTerminalIcon(40, 10, 24, UIPainter::rgba(56, 189, 248, 255));
    painter.drawFolderIcon(70, 10, 24, UIPainter::rgba(251, 191, 36, 255));
    painter.drawWifiIcon(100, 10, 20, true, UIPainter::rgba(56, 189, 248, 255));
    painter.drawBatteryIcon(130, 10, 30, 16, 75, false, UIPainter::rgba(203, 213, 225, 255));
    painter.drawCloseIcon(170, 10, 16, UIPainter::rgba(239, 68, 68, 255));

    printf("[PASS] test_ui_painter\n");
}

static void test_application_catalog_and_filtering() {
    printf("[RUN] test_application_catalog_and_filtering\n");

    DesktopShellClient client;

    std::vector<LauncherMenuItem> catalog = {
        { "Terminal", "/bin/bash", "Command Line Shell", "Development", "terminal" },
        { "Vim", "/usr/bin/vim", "Text Editor", "Development", "terminal" },
        { "File Manager", "/usr/bin/thunar", "Browse Files", "System", "folder" },
        { "Calculator", "/usr/bin/gnome-calculator", "Scientific Calculator", "Utilities", "settings" },
        { "Settings", "/usr/bin/xfce4-settings", "System Settings", "System", "settings" }
    };

    client.updateApplicationCatalog(catalog);
    assert(client.getApplicationCatalog().size() == 5);

    // 1. All category (default)
    auto filtered = client.getFilteredLauncherItems();
    assert(filtered.size() == 5);

    // 2. Development category filter
    client.selectLauncherCategory("Development");
    assert(client.getSelectedLauncherCategory() == "Development");
    filtered = client.getFilteredLauncherItems();
    assert(filtered.size() == 2);
    assert(filtered[0].name == "Terminal");
    assert(filtered[1].name == "Vim");

    // 3. System category filter
    client.selectLauncherCategory("System");
    filtered = client.getFilteredLauncherItems();
    assert(filtered.size() == 2);
    assert(filtered[0].name == "File Manager");
    assert(filtered[1].name == "Settings");

    // 4. Search Query Filtering
    client.selectLauncherCategory("All");
    client.setLauncherSearchQuery("calc");
    assert(client.getLauncherSearchQuery() == "calc");
    filtered = client.getFilteredLauncherItems();
    assert(filtered.size() == 1);
    assert(filtered[0].name == "Calculator");

    // Clear search
    client.setLauncherSearchQuery("");
    filtered = client.getFilteredLauncherItems();
    assert(filtered.size() == 5);

    printf("[PASS] test_application_catalog_and_filtering\n");
}

static void test_desktop_session_integration() {
    printf("[RUN] test_desktop_session_integration\n");

    auto& session = DesktopSession::getInstance();
    assert(!session.isRunning());

    // 1. Catalog pre-population
    std::vector<LauncherMenuItem> catalog = {
        { "Foot", "/usr/bin/foot", "Fast Terminal", "Development", "terminal" },
        { "Htop", "/usr/bin/htop", "Process Viewer", "System", "terminal" }
    };
    session.updateApplicationCatalog(catalog);
    assert(session.getApplicationCatalog().size() == 2);

    // 2. Launch handler wiring
    std::string last_name, last_exec;
    session.setAppLaunchHandler([&](const std::string& name, const std::string& exec_path) {
        last_name = name;
        last_exec = exec_path;
    });

    printf("[PASS] test_desktop_session_integration\n");
}

int main() {
    printf("=== LinuxDroid Phase 7 Native Desktop Environment Test Suite ===\n");
    test_wayland_connection_and_globals();
    test_shell_lifecycle_and_restart();
    test_geometry_and_resize();
    test_launcher_ui_and_navigation();
    test_window_list_and_application_launch();
    test_desktop_state();
    test_window_model();
    test_window_manager();
    test_ui_painter();
    test_application_catalog_and_filtering();
    test_desktop_session_integration();
    printf("=== All Phase 7 Desktop Environment Tests PASSED! ===\n");
    return 0;
}
