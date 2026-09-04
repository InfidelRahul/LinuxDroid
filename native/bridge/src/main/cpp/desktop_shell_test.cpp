#include "desktop_shell_client.h"
#include "desktop_window_tracker.h"
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

int main() {
    printf("=== LinuxDroid Phase 7 Minimal Wayland Desktop Shell Test Suite ===\n");
    test_wayland_connection_and_globals();
    test_shell_lifecycle_and_restart();
    test_geometry_and_resize();
    test_launcher_ui_and_navigation();
    test_window_list_and_application_launch();
    printf("=== All Phase 7 Desktop Shell Tests PASSED! ===\n");
    return 0;
}
