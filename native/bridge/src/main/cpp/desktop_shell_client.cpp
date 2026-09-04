#include "desktop_shell_client.h"
#include "font_8x16.h"

#include <sys/mman.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <ctime>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <cinttypes>
#include <algorithm>
#include <android/log.h>

#define TAG "LinuxDroid/DesktopShell"
#define LOGI(fmt, ...) do { \
    printf("[SHELL_INFO] " fmt "\n", ##__VA_ARGS__); \
    __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__); \
} while (0)
#define LOGW(fmt, ...) do { \
    printf("[SHELL_WARN] " fmt "\n", ##__VA_ARGS__); \
    __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__); \
} while (0)
#define LOGE(fmt, ...) do { \
    printf("[SHELL_ERROR] " fmt "\n", ##__VA_ARGS__); \
    __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__); \
} while (0)

namespace linuxdroid {

void ShmBuffer::destroy() {
    if (buffer) {
        wl_buffer_destroy(buffer);
        buffer = nullptr;
    }
    if (pixels && pixels != MAP_FAILED) {
        munmap(pixels, size);
        pixels = nullptr;
    }
    if (fd >= 0) {
        close(fd);
        fd = -1;
    }
    width = 0;
    height = 0;
    stride = 0;
    size = 0;
}

DesktopShellClient::DesktopShellClient() {
    launcher_menu_ = {
        { "Terminal", "/bin/bash", "Bash interactive Linux shell", "System", "terminal" },
        { "POSIX Shell", "/bin/sh", "Standard system command shell", "System", "terminal" },
        { "Text Editor", "/usr/bin/nano", "Command-line text editor", "Development", "file" },
        { "File Manager", "/usr/bin/mc", "Midnight Commander file manager", "Utilities", "folder" },
        { "System Monitor", "/usr/bin/top", "Process activity and resources", "System", "settings" },
        { "Environment", "/usr/bin/env", "Inspect environment variables", "System", "settings" },
        { "System Info", "/bin/uname", "Linux kernel & OS release", "System", "terminal" }
    };
}

DesktopShellClient::~DesktopShellClient() {
    stop();
}

bool DesktopShellClient::start(const char* socket_name) {
    if (running_.load(std::memory_order_relaxed)) {
        LOGW("Desktop shell already running");
        return true;
    }

    if (pipe2(wake_pipe_, O_CLOEXEC | O_NONBLOCK) < 0) {
        LOGE("Failed to create wake pipe for desktop shell: %s", strerror(errno));
        return false;
    }

    running_.store(true, std::memory_order_relaxed);
    std::string sock = (socket_name && socket_name[0]) ? socket_name : "wayland-0";
    thread_ = std::thread(&DesktopShellClient::threadMain, this, sock);

    LOGI("SHELL_STARTING: Desktop shell thread spawned (target socket='%s')", sock.c_str());
    return true;
}

void DesktopShellClient::stop() {
    if (!running_.exchange(false)) {
        return;
    }

    LOGI("SHELL_STOPPING: Stopping desktop shell client...");
    if (wake_pipe_[1] >= 0) {
        char b = 1;
        (void)write(wake_pipe_[1], &b, 1);
    }

    if (thread_.joinable()) {
        thread_.join();
    }

    if (wake_pipe_[0] >= 0) { close(wake_pipe_[0]); wake_pipe_[0] = -1; }
    if (wake_pipe_[1] >= 0) { close(wake_pipe_[1]); wake_pipe_[1] = -1; }
    LOGI("SHELL_STOPPED: Desktop shell cleanly stopped");
}

void DesktopShellClient::setOutputGeometry(int32_t width, int32_t height, int32_t scale) {
    std::lock_guard<std::mutex> lock(render_mutex_);
    if (width > 0 && height > 0) {
        width_ = width;
        height_ = height;
    }
    if (scale > 0) {
        scale_ = scale;
    }
    LOGI("SHELL_OUTPUT_GEOMETRY: w=%d h=%d scale=%d", width_, height_, scale_);
}

void DesktopShellClient::toggleLauncher() {
    setLauncherOpen(!launcher_open_);
}

void DesktopShellClient::setLauncherOpen(bool open) {
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        if (launcher_open_ == open) return;
        launcher_open_ = open;
    }
    DesktopState::getInstance().setLauncherVisible(open);
    LOGI("SHELL_LAUNCHER_STATE: open=%d", open ? 1 : 0);
    renderAll();
}

void DesktopShellClient::selectNextLauncherItem() {
    std::lock_guard<std::mutex> lock(render_mutex_);
    auto items = getFilteredLauncherItems();
    if (items.empty()) return;
    selected_launcher_item_ = (selected_launcher_item_ + 1) % static_cast<int>(items.size());
    renderLauncher();
}

void DesktopShellClient::selectPrevLauncherItem() {
    std::lock_guard<std::mutex> lock(render_mutex_);
    auto items = getFilteredLauncherItems();
    if (items.empty()) return;
    selected_launcher_item_ = (selected_launcher_item_ - 1 + static_cast<int>(items.size())) % static_cast<int>(items.size());
    renderLauncher();
}

void DesktopShellClient::activateSelectedLauncherItem() {
    std::string name, path;
    AppLaunchHandler handler;
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        auto items = getFilteredLauncherItems();
        if (selected_launcher_item_ < 0 || selected_launcher_item_ >= static_cast<int>(items.size())) return;
        name = items[selected_launcher_item_].name;
        path = items[selected_launcher_item_].exec_path;
        handler = app_launch_handler_;
        launcher_open_ = false;
    }
    DesktopState::getInstance().setLauncherVisible(false);

    LOGI("APPLICATION_LAUNCH_REQUEST: app='%s' path='%s'", name.c_str(), path.c_str());
    if (handler) {
        handler(name, path);
    }
    renderAll();
}

void DesktopShellClient::updateApplicationCatalog(const std::vector<LauncherMenuItem>& items) {
    std::lock_guard<std::mutex> lock(render_mutex_);
    launcher_menu_ = items;
    selected_launcher_item_ = 0;
    LOGI("APPLICATION_CATALOG_UPDATED: count=%zu", launcher_menu_.size());
    renderLauncher();
}

std::vector<LauncherMenuItem> DesktopShellClient::getApplicationCatalog() const {
    std::lock_guard<std::mutex> lock(render_mutex_);
    return launcher_menu_;
}

void DesktopShellClient::setLauncherSearchQuery(const std::string& query) {
    std::lock_guard<std::mutex> lock(render_mutex_);
    search_query_ = query;
    selected_launcher_item_ = 0;
    renderLauncher();
}

std::string DesktopShellClient::getLauncherSearchQuery() const {
    std::lock_guard<std::mutex> lock(render_mutex_);
    return search_query_;
}

void DesktopShellClient::selectLauncherCategory(const std::string& category) {
    std::lock_guard<std::mutex> lock(render_mutex_);
    selected_category_ = category;
    selected_launcher_item_ = 0;
    renderLauncher();
}

std::string DesktopShellClient::getSelectedLauncherCategory() const {
    std::lock_guard<std::mutex> lock(render_mutex_);
    return selected_category_;
}

std::vector<LauncherMenuItem> DesktopShellClient::getFilteredLauncherItems() const {
    std::vector<LauncherMenuItem> filtered;
    std::string lower_query = search_query_;
    std::transform(lower_query.begin(), lower_query.end(), lower_query.begin(), ::tolower);

    for (const auto& item : launcher_menu_) {
        // Category filter
        if (selected_category_ != "All" && item.category != selected_category_) {
            continue;
        }

        // Search query filter
        if (!lower_query.empty()) {
            std::string lower_name = item.name;
            std::transform(lower_name.begin(), lower_name.end(), lower_name.begin(), ::tolower);
            std::string lower_desc = item.description;
            std::transform(lower_desc.begin(), lower_desc.end(), lower_desc.begin(), ::tolower);

            if (lower_name.find(lower_query) == std::string::npos &&
                lower_desc.find(lower_query) == std::string::npos) {
                continue;
            }
        }
        filtered.push_back(item);
    }
    return filtered;
}

void DesktopShellClient::setAppLaunchHandler(AppLaunchHandler handler) {
    std::lock_guard<std::mutex> lock(render_mutex_);
    app_launch_handler_ = std::move(handler);
}

void DesktopShellClient::launchApplication(size_t index) {
    std::string name, path;
    AppLaunchHandler handler;
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        if (index >= launcher_menu_.size()) return;
        name = launcher_menu_[index].name;
        path = launcher_menu_[index].exec_path;
        handler = app_launch_handler_;
        launcher_open_ = false;
    }
    DesktopState::getInstance().setLauncherVisible(false);

    LOGI("APPLICATION_LAUNCH_REQUEST: app='%s' path='%s'", name.c_str(), path.c_str());

    if (handler) {
        handler(name, path);
    } else {
        LOGW("APPLICATION_LAUNCH_UNHANDLED: No launch handler registered for '%s'", name.c_str());
    }

    renderAll();
}

bool DesktopShellClient::launchCustomCommand(const std::string& path, const std::vector<std::string>& /*args*/) {
    LOGI("APPLICATION_LAUNCH_DISPATCH: dispatching '%s' to launch handler", path.c_str());
    AppLaunchHandler handler;
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        handler = app_launch_handler_;
    }
    if (handler) {
        handler(path, path);
        return true;
    }
    LOGW("APPLICATION_LAUNCH_REJECTED: launchCustomCommand called without registered handler for '%s'", path.c_str());
    return false;
}

ShmBuffer DesktopShellClient::createShmBuffer(int width, int height) {
    ShmBuffer buf;
    if (width <= 0 || height <= 0 || shm_ == nullptr) return buf;

    int stride = width * 4;
    size_t size = static_cast<size_t>(stride * height);

    int fd = -1;
#if defined(__NR_memfd_create) || defined(SYS_memfd_create)
    fd = memfd_create("linuxdroid-shm", MFD_CLOEXEC | MFD_ALLOW_SEALING);
#endif
    if (fd < 0) {
        char temp_path[] = "/tmp/linuxdroid-shm-XXXXXX";
        fd = mkstemp(temp_path);
        if (fd >= 0) unlink(temp_path);
    }
    if (fd < 0) {
        LOGE("Failed to allocate anonymous shm fd");
        return buf;
    }

    if (ftruncate(fd, size) < 0) {
        LOGE("ftruncate shm failed: %s", strerror(errno));
        close(fd);
        return buf;
    }

    void* data = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOGE("mmap shm failed: %s", strerror(errno));
        close(fd);
        return buf;
    }

    struct wl_shm_pool* pool = wl_shm_create_pool(shm_, fd, size);
    if (!pool) {
        LOGE("wl_shm_create_pool failed");
        munmap(data, size);
        close(fd);
        return buf;
    }

    struct wl_buffer* wl_buf = wl_shm_pool_create_buffer(pool, 0, width, height, stride, WL_SHM_FORMAT_ARGB8888);
    wl_shm_pool_destroy(pool);

    if (!wl_buf) {
        LOGE("wl_shm_pool_create_buffer failed");
        munmap(data, size);
        close(fd);
        return buf;
    }

    buf.buffer = wl_buf;
    buf.pixels = static_cast<uint32_t*>(data);
    buf.width = width;
    buf.height = height;
    buf.stride = stride;
    buf.size = size;
    buf.fd = fd;
    return buf;
}

void DesktopShellClient::renderBackground() {
    if (!bg_surface_ || !shm_) return;

    if (bg_buffer_.width != width_ || bg_buffer_.height != height_) {
        bg_buffer_.destroy();
        bg_buffer_ = createShmBuffer(width_, height_);
    }

    if (!bg_buffer_.pixels) return;

    auto theme = DesktopState::getInstance().getTheme();
    UIPainter painter(bg_buffer_.pixels, width_, height_);

    // 1. High-definition gradient wallpaper
    painter.drawLinearGradient(0, 0, width_, height_, theme.bg_gradient_top, theme.bg_gradient_bottom, true);

    // 2. Subtle grid texture
    for (int y = 48; y < height_; y += 64) {
        for (int x = 48; x < width_; x += 64) {
            painter.drawPixel(x, y, UIPainter::rgba(56, 189, 248, 40));
        }
    }

    // 3. Central Desktop Branding
    const char* brand = "LinuxDroid OS";
    int b_w = painter.getTextWidth(brand, 2);
    painter.drawText((width_ - b_w) / 2, height_ / 3, brand, UIPainter::rgba(148, 163, 184, 80), 2);

    const char* subtext = "Rootless Native Wayland Desktop Environment";
    int s_w = painter.getTextWidth(subtext, 1);
    painter.drawText((width_ - s_w) / 2, height_ / 3 + 40, subtext, UIPainter::rgba(100, 116, 139, 70), 1);

    wl_surface_attach(bg_surface_, bg_buffer_.buffer, 0, 0);
    wl_surface_damage(bg_surface_, 0, 0, width_, height_);
    wl_surface_commit(bg_surface_);
}

void DesktopShellClient::renderPanel() {
    if (!panel_surface_ || !shm_) return;

    if (panel_buffer_.width != width_ || panel_buffer_.height != panel_height_) {
        panel_buffer_.destroy();
        panel_buffer_ = createShmBuffer(width_, panel_height_);
    }

    if (!panel_buffer_.pixels) return;

    auto theme = DesktopState::getInstance().getTheme();
    UIPainter painter(panel_buffer_.pixels, width_, panel_height_);

    // 1. Glass Panel Background & Top Highlight
    painter.clear(theme.panel_bg);
    painter.drawFilledRect(0, 0, width_, 2, theme.panel_border);

    // 2. Application Launcher Button [App Grid Icon + "Apps"]
    uint32_t launcher_btn_color = launcher_open_ ? theme.accent_color : UIPainter::rgba(30, 41, 59, 200);
    painter.drawRoundedRect(8, 6, 92, 36, 6, launcher_btn_color);
    painter.drawRect(8, 6, 92, 36, launcher_open_ ? UIPainter::rgba(56, 189, 248, 255) : theme.panel_border);
    painter.drawLauncherIcon(14, 12, 24, UIPainter::rgba(248, 250, 252, 255));
    painter.drawText(44, 16, "Apps", UIPainter::rgba(248, 250, 252, 255), 1);

    // 3. Active Window Taskbar Pills
    auto windows = WindowModel::getInstance().getWindows();
    int win_x = 112;
    int pill_w = 160;
    int pill_h = 36;
    int pill_pad = 6;

    for (size_t i = 0; i < windows.size() && win_x + pill_w < width_ - 180; ++i) {
        const auto& w = windows[i];
        uint32_t pill_bg = w.is_active ? theme.pill_active_bg : theme.pill_inactive_bg;
        painter.drawRoundedRect(win_x, pill_pad, pill_w, pill_h, 6, pill_bg);

        if (w.is_active) {
            // Active window bottom underline
            painter.drawFilledRect(win_x + 8, pill_pad + pill_h - 3, pill_w - 16, 2, UIPainter::rgba(56, 189, 248, 255));
        }

        // Icon based on app_id
        if (w.app_id == "foot" || w.app_id == "Terminal" || w.app_id == "xterm") {
            painter.drawTerminalIcon(win_x + 6, pill_pad + 6, 24, UIPainter::rgba(248, 250, 252, 255));
        } else if (w.app_id == "editor" || w.app_id == "gedit") {
            painter.drawFolderIcon(win_x + 6, pill_pad + 6, 24, UIPainter::rgba(248, 250, 252, 255));
        } else {
            painter.drawMaximizeIcon(win_x + 6, pill_pad + 6, 24, UIPainter::rgba(248, 250, 252, 255));
        }

        // Window Title
        painter.drawTextTruncated(win_x + 34, pill_pad + 10, w.title.c_str(), 11, theme.text_primary, 1);

        // Close Button 'x'
        int close_x = win_x + pill_w - 20;
        int close_y = pill_pad + 10;
        painter.drawCloseIcon(close_x, close_y, 16, UIPainter::rgba(203, 213, 225, 200));

        win_x += pill_w + 6;
    }

    // 4. System Tray (Wi-Fi, Battery, Clock)
    auto sys = DesktopState::getInstance().getSnapshot().system;

    // Wi-Fi Icon
    int wifi_x = width_ - 170;
    painter.drawWifiIcon(wifi_x, 14, 20, sys.network_connected, UIPainter::rgba(56, 189, 248, 255));

    // Battery Icon
    int bat_x = width_ - 140;
    painter.drawBatteryIcon(bat_x, 15, 30, 16, sys.battery_percent, sys.is_charging, UIPainter::rgba(203, 213, 225, 255));

    // Digital Clock
    time_t now = time(nullptr);
    struct tm tstruct{};
    localtime_r(&now, &tstruct);
    char time_str[32];
    snprintf(time_str, sizeof(time_str), "%02d:%02d:%02d", tstruct.tm_hour, tstruct.tm_min, tstruct.tm_sec);
    int clock_x = width_ - 95;
    painter.drawText(clock_x, 16, time_str, UIPainter::rgba(248, 250, 252, 255), 1);

    wl_surface_attach(panel_surface_, panel_buffer_.buffer, 0, 0);
    wl_surface_damage(panel_surface_, 0, 0, width_, panel_height_);
    wl_surface_commit(panel_surface_);
}

void DesktopShellClient::renderLauncher() {
    if (!launcher_open_) {
        if (launcher_surface_) {
            wl_surface_attach(launcher_surface_, nullptr, 0, 0);
            wl_surface_commit(launcher_surface_);
        }
        return;
    }

    if (!launcher_surface_) {
        launcher_surface_ = wl_compositor_create_surface(compositor_);
        launcher_xdg_surface_ = xdg_wm_base_get_xdg_surface(xdg_wm_base_, launcher_surface_);
        static const struct xdg_surface_listener l_surf_listener = {
            .configure = launcherSurfaceConfigure,
        };
        xdg_surface_add_listener(launcher_xdg_surface_, &l_surf_listener, this);
        launcher_xdg_toplevel_ = xdg_surface_get_toplevel(launcher_xdg_surface_);
        xdg_toplevel_set_app_id(launcher_xdg_toplevel_, "org.linuxdroid.desktop-launcher");
        xdg_toplevel_set_title(launcher_xdg_toplevel_, "LinuxDroid Launcher");
        static const struct xdg_toplevel_listener l_top_listener = {
            .configure = launcherToplevelConfigure,
            .close = launcherToplevelClose,
        };
        xdg_toplevel_add_listener(launcher_xdg_toplevel_, &l_top_listener, this);
        wl_surface_commit(launcher_surface_);
        wl_display_roundtrip(display_);
    }

    if (launcher_buffer_.width != launcher_width_ || launcher_buffer_.height != launcher_height_) {
        launcher_buffer_.destroy();
        launcher_buffer_ = createShmBuffer(launcher_width_, launcher_height_);
    }

    if (!launcher_buffer_.pixels) return;

    auto theme = DesktopState::getInstance().getTheme();
    UIPainter painter(launcher_buffer_.pixels, launcher_width_, launcher_height_);

    // 1. Drawer Background & Outer Border
    painter.clear(theme.launcher_bg);
    painter.drawRect(0, 0, launcher_width_, launcher_height_, theme.panel_border);

    // 2. Search Input Box
    painter.drawRoundedRect(12, 12, launcher_width_ - 24, 38, 6, UIPainter::rgba(30, 41, 59, 240));
    painter.drawRect(12, 12, launcher_width_ - 24, 38, UIPainter::rgba(56, 189, 248, 120));

    if (search_query_.empty()) {
        painter.drawText(22, 22, "Search applications...", UIPainter::rgba(148, 163, 184, 160), 1);
    } else {
        painter.drawText(22, 22, search_query_.c_str(), UIPainter::rgba(248, 250, 252, 255), 1);
    }

    // 3. Category Selector Pills
    const std::vector<std::string> categories = { "All", "Development", "System", "Utilities" };
    int cat_x = 12;
    int cat_y = 58;
    for (const auto& cat : categories) {
        bool is_active_cat = (selected_category_ == cat);
        uint32_t cat_bg = is_active_cat ? theme.accent_color : UIPainter::rgba(30, 41, 59, 180);
        int cat_w = painter.getTextWidth(cat.c_str(), 1) + 16;
        painter.drawRoundedRect(cat_x, cat_y, cat_w, 24, 4, cat_bg);
        painter.drawText(cat_x + 8, cat_y + 4, cat.c_str(), is_active_cat ? 0xFFFFFFFF : UIPainter::rgba(203, 213, 225, 200), 1);
        cat_x += cat_w + 6;
    }

    // 4. Applications List
    auto filtered_items = getFilteredLauncherItems();
    int item_y = 92;
    int row_h = 44;

    for (size_t i = 0; i < filtered_items.size() && item_y + row_h <= launcher_height_ - 8; ++i) {
        bool selected = (static_cast<int>(i) == selected_launcher_item_);
        uint32_t row_bg = selected ? UIPainter::rgba(56, 189, 248, 40) : UIPainter::rgba(15, 23, 42, 100);
        painter.drawRoundedRect(8, item_y, launcher_width_ - 16, row_h, 6, row_bg);

        if (selected) {
            painter.drawRect(8, item_y, launcher_width_ - 16, row_h, UIPainter::rgba(56, 189, 248, 180));
        }

        // Icon
        const auto& item = filtered_items[i];
        if (item.icon == "terminal") {
            painter.drawTerminalIcon(16, item_y + 8, 28, UIPainter::rgba(56, 189, 248, 255));
        } else if (item.icon == "folder") {
            painter.drawFolderIcon(16, item_y + 8, 28, UIPainter::rgba(251, 191, 36, 255));
        } else if (item.icon == "settings") {
            painter.drawSettingsIcon(16, item_y + 8, 28, UIPainter::rgba(148, 163, 184, 255));
        } else {
            painter.drawFolderIcon(16, item_y + 8, 28, UIPainter::rgba(56, 189, 248, 255));
        }

        // Title and Description
        painter.drawText(52, item_y + 6, item.name.c_str(), theme.text_primary, 1);
        painter.drawTextTruncated(52, item_y + 24, item.description.c_str(), 32, theme.text_secondary, 1);

        item_y += row_h + 4;
    }

    wl_surface_attach(launcher_surface_, launcher_buffer_.buffer, 0, 0);
    wl_surface_damage(launcher_surface_, 0, 0, launcher_width_, launcher_height_);
    wl_surface_commit(launcher_surface_);
}

void DesktopShellClient::renderAll() {
    renderBackground();
    renderPanel();
    renderLauncher();
}

void DesktopShellClient::handlePointerClick(struct wl_surface* surface, double x, double y) {
    if (surface == panel_surface_) {
        // 1. Check Launcher button [x: 8..100]
        if (x >= 8 && x <= 100 && y >= 6 && y <= 42) {
            toggleLauncher();
            return;
        }

        // 2. Check Window Taskbar Pills
        auto windows = WindowModel::getInstance().getWindows();
        int win_x = 112;
        int pill_w = 160;

        for (size_t i = 0; i < windows.size(); ++i) {
            if (x >= win_x && x <= win_x + pill_w && y >= 6 && y <= 42) {
                // Check if close icon clicked [rightmost 20px of pill]
                if (x >= win_x + pill_w - 24) {
                    WindowManager::getInstance().closeWindow(windows[i].id);
                } else {
                    WindowManager::getInstance().toggleMinimize(windows[i].id);
                }
                renderPanel();
                return;
            }
            win_x += pill_w + 6;
        }
    } else if (surface == launcher_surface_) {
        // Check Category Row [y: 58..82]
        if (y >= 58 && y <= 82) {
            const std::vector<std::string> categories = { "All", "Development", "System", "Utilities" };
            UIPainter dummy(nullptr, 0, 0);
            int cat_x = 12;
            for (const auto& cat : categories) {
                int cat_w = dummy.getTextWidth(cat.c_str(), 1) + 16;
                if (x >= cat_x && x <= cat_x + cat_w) {
                    selectLauncherCategory(cat);
                    return;
                }
                cat_x += cat_w + 6;
            }
        }

        // Check Application items [y >= 92]
        auto items = getFilteredLauncherItems();
        int item_y = 92;
        int row_h = 44;

        for (size_t i = 0; i < items.size(); ++i) {
            if (x >= 8 && x <= launcher_width_ - 8 && y >= item_y && y <= item_y + row_h) {
                // Find matching item in master launcher_menu_
                for (size_t m = 0; m < launcher_menu_.size(); ++m) {
                    if (launcher_menu_[m].exec_path == items[i].exec_path &&
                        launcher_menu_[m].name == items[i].name) {
                        launchApplication(m);
                        return;
                    }
                }
            }
            item_y += row_h + 4;
        }
    } else if (surface == bg_surface_) {
        if (launcher_open_) {
            setLauncherOpen(false);
        }
    }
}

// --- Wayland Listeners ---

void DesktopShellClient::registryHandleGlobal(void* data, struct wl_registry* reg, uint32_t name, const char* iface, uint32_t ver) {
    auto* self = static_cast<DesktopShellClient*>(data);

    if (strcmp(iface, wl_compositor_interface.name) == 0) {
        self->compositor_ = static_cast<struct wl_compositor*>(
            wl_registry_bind(reg, name, &wl_compositor_interface, ver < 4 ? ver : 4));
    } else if (strcmp(iface, wl_shm_interface.name) == 0) {
        self->shm_ = static_cast<struct wl_shm*>(
            wl_registry_bind(reg, name, &wl_shm_interface, 1));
    } else if (strcmp(iface, wl_output_interface.name) == 0) {
        self->output_ = static_cast<struct wl_output*>(
            wl_registry_bind(reg, name, &wl_output_interface, ver < 3 ? ver : 3));
        static const struct wl_output_listener output_listener = {
            .geometry = outputHandleGeometry,
            .mode = outputHandleMode,
            .done = outputHandleDone,
            .scale = outputHandleScale,
        };
        wl_output_add_listener(self->output_, &output_listener, self);
    } else if (strcmp(iface, wl_seat_interface.name) == 0) {
        self->seat_ = static_cast<struct wl_seat*>(
            wl_registry_bind(reg, name, &wl_seat_interface, ver < 7 ? ver : 7));
        static const struct wl_seat_listener seat_listener = {
            .capabilities = seatHandleCapabilities,
            .name = seatHandleName,
        };
        wl_seat_add_listener(self->seat_, &seat_listener, self);
    } else if (strcmp(iface, xdg_wm_base_interface.name) == 0) {
        self->xdg_wm_base_ = static_cast<struct xdg_wm_base*>(
            wl_registry_bind(reg, name, &xdg_wm_base_interface, 1));
        static const struct xdg_wm_base_listener wm_base_listener = {
            .ping = xdgWmBaseHandlePing,
        };
        xdg_wm_base_add_listener(self->xdg_wm_base_, &wm_base_listener, self);
    }
}

void DesktopShellClient::registryHandleGlobalRemove(void*, struct wl_registry*, uint32_t) {}

void DesktopShellClient::outputHandleGeometry(void*, struct wl_output*, int32_t, int32_t, int32_t, int32_t, int32_t, const char*, const char*, int32_t) {}
void DesktopShellClient::outputHandleMode(void* data, struct wl_output*, uint32_t flags, int32_t width, int32_t height, int32_t) {
    auto* self = static_cast<DesktopShellClient*>(data);
    if (flags & WL_OUTPUT_MODE_CURRENT) {
        self->setOutputGeometry(width, height, self->scale_);
    }
}
void DesktopShellClient::outputHandleDone(void*, struct wl_output*) {}
void DesktopShellClient::outputHandleScale(void* data, struct wl_output*, int32_t factor) {
    auto* self = static_cast<DesktopShellClient*>(data);
    self->setOutputGeometry(self->width_, self->height_, factor);
}

void DesktopShellClient::xdgWmBaseHandlePing(void*, struct xdg_wm_base* shell, uint32_t serial) {
    xdg_wm_base_pong(shell, serial);
}

void DesktopShellClient::seatHandleCapabilities(void* data, struct wl_seat* seat, uint32_t caps) {
    auto* self = static_cast<DesktopShellClient*>(data);
    if ((caps & WL_SEAT_CAPABILITY_POINTER) && !self->pointer_) {
        self->pointer_ = wl_seat_get_pointer(seat);
        static const struct wl_pointer_listener p_listener = {
            .enter = pointerHandleEnter,
            .leave = pointerHandleLeave,
            .motion = pointerHandleMotion,
            .button = pointerHandleButton,
            .axis = pointerHandleAxis,
            .frame = pointerHandleFrame,
            .axis_source = pointerHandleAxisSource,
            .axis_stop = pointerHandleAxisStop,
            .axis_discrete = pointerHandleAxisDiscrete,
        };
        wl_pointer_add_listener(self->pointer_, &p_listener, self);
    } else if (!(caps & WL_SEAT_CAPABILITY_POINTER) && self->pointer_) {
        wl_pointer_release(self->pointer_);
        self->pointer_ = nullptr;
    }

    if ((caps & WL_SEAT_CAPABILITY_TOUCH) && !self->touch_) {
        self->touch_ = wl_seat_get_touch(seat);
        static const struct wl_touch_listener t_listener = {
            .down = touchHandleDown,
            .up = touchHandleUp,
            .motion = touchHandleMotion,
            .frame = touchHandleFrame,
            .cancel = touchHandleCancel,
        };
        wl_touch_add_listener(self->touch_, &t_listener, self);
    } else if (!(caps & WL_SEAT_CAPABILITY_TOUCH) && self->touch_) {
        wl_touch_release(self->touch_);
        self->touch_ = nullptr;
    }

    if ((caps & WL_SEAT_CAPABILITY_KEYBOARD) && !self->keyboard_) {
        self->keyboard_ = wl_seat_get_keyboard(seat);
        static const struct wl_keyboard_listener k_listener = {
            .keymap = keyboardHandleKeymap,
            .enter = keyboardHandleEnter,
            .leave = keyboardHandleLeave,
            .key = keyboardHandleKey,
            .modifiers = keyboardHandleModifiers,
            .repeat_info = keyboardHandleRepeatInfo,
        };
        wl_keyboard_add_listener(self->keyboard_, &k_listener, self);
    } else if (!(caps & WL_SEAT_CAPABILITY_KEYBOARD) && self->keyboard_) {
        wl_keyboard_release(self->keyboard_);
        self->keyboard_ = nullptr;
    }
}

void DesktopShellClient::seatHandleName(void*, struct wl_seat*, const char*) {}

void DesktopShellClient::pointerHandleEnter(void* data, struct wl_pointer*, uint32_t, struct wl_surface* surface, wl_fixed_t sx, wl_fixed_t sy) {
    auto* self = static_cast<DesktopShellClient*>(data);
    self->active_pointer_surface_ = surface;
    self->pointer_x_ = wl_fixed_to_double(sx);
    self->pointer_y_ = wl_fixed_to_double(sy);
}

void DesktopShellClient::pointerHandleLeave(void* data, struct wl_pointer*, uint32_t, struct wl_surface*) {
    auto* self = static_cast<DesktopShellClient*>(data);
    self->active_pointer_surface_ = nullptr;
}

void DesktopShellClient::pointerHandleMotion(void* data, struct wl_pointer*, uint32_t, wl_fixed_t sx, wl_fixed_t sy) {
    auto* self = static_cast<DesktopShellClient*>(data);
    self->pointer_x_ = wl_fixed_to_double(sx);
    self->pointer_y_ = wl_fixed_to_double(sy);
}

void DesktopShellClient::pointerHandleButton(void* data, struct wl_pointer*, uint32_t, uint32_t, uint32_t button, uint32_t state) {
    auto* self = static_cast<DesktopShellClient*>(data);
    if (state == WL_POINTER_BUTTON_STATE_PRESSED && button == 0x110) { // BTN_LEFT
        if (self->active_pointer_surface_) {
            self->handlePointerClick(self->active_pointer_surface_, self->pointer_x_, self->pointer_y_);
        }
    }
}

void DesktopShellClient::pointerHandleAxis(void*, struct wl_pointer*, uint32_t, uint32_t, wl_fixed_t) {}
void DesktopShellClient::pointerHandleFrame(void*, struct wl_pointer*) {}
void DesktopShellClient::pointerHandleAxisSource(void*, struct wl_pointer*, uint32_t) {}
void DesktopShellClient::pointerHandleAxisStop(void*, struct wl_pointer*, uint32_t, uint32_t) {}
void DesktopShellClient::pointerHandleAxisDiscrete(void*, struct wl_pointer*, uint32_t, int32_t) {}

void DesktopShellClient::touchHandleDown(void* data, struct wl_touch*, uint32_t, uint32_t, struct wl_surface* surface, int32_t, wl_fixed_t x, wl_fixed_t y) {
    auto* self = static_cast<DesktopShellClient*>(data);
    self->handlePointerClick(surface, wl_fixed_to_double(x), wl_fixed_to_double(y));
}
void DesktopShellClient::touchHandleUp(void*, struct wl_touch*, uint32_t, uint32_t, int32_t) {}
void DesktopShellClient::touchHandleMotion(void*, struct wl_touch*, uint32_t, int32_t, wl_fixed_t, wl_fixed_t) {}
void DesktopShellClient::touchHandleFrame(void*, struct wl_touch*) {}
void DesktopShellClient::touchHandleCancel(void*, struct wl_touch*) {}

void DesktopShellClient::keyboardHandleKeymap(void* data, struct wl_keyboard*, uint32_t format, int32_t fd, uint32_t size) {
    auto* self = static_cast<DesktopShellClient*>(data);
    if (format != WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1) {
        close(fd);
        return;
    }
    char* map_str = static_cast<char*>(mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0));
    if (map_str == MAP_FAILED) {
        close(fd);
        return;
    }
    if (!self->xkb_ctx_) self->xkb_ctx_ = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    if (self->xkb_keymap_) xkb_keymap_unref(self->xkb_keymap_);
    self->xkb_keymap_ = xkb_keymap_new_from_string(self->xkb_ctx_, map_str, XKB_KEYMAP_FORMAT_TEXT_V1, XKB_KEYMAP_COMPILE_NO_FLAGS);
    munmap(map_str, size);
    close(fd);

    if (self->xkb_state_) xkb_state_unref(self->xkb_state_);
    self->xkb_state_ = xkb_state_new(self->xkb_keymap_);
}

void DesktopShellClient::keyboardHandleEnter(void*, struct wl_keyboard*, uint32_t, struct wl_surface*, struct wl_array*) {}
void DesktopShellClient::keyboardHandleLeave(void*, struct wl_keyboard*, uint32_t, struct wl_surface*) {}

void DesktopShellClient::keyboardHandleKey(void* data, struct wl_keyboard*, uint32_t, uint32_t, uint32_t key, uint32_t state) {
    auto* self = static_cast<DesktopShellClient*>(data);
    if (state == WL_KEYBOARD_KEY_STATE_PRESSED) {
        // Esc: close launcher
        if (key == 1) { // KEY_ESC
            if (self->isLauncherOpen()) {
                self->setLauncherOpen(false);
            }
        } else if (key == 103) { // KEY_UP
            if (self->isLauncherOpen()) self->selectPrevLauncherItem();
        } else if (key == 108) { // KEY_DOWN
            if (self->isLauncherOpen()) self->selectNextLauncherItem();
        } else if (key == 28) { // KEY_ENTER
            if (self->isLauncherOpen()) self->activateSelectedLauncherItem();
        } else if (key == 125 || key == 126) { // Super key
            self->toggleLauncher();
        }
    }
}

void DesktopShellClient::keyboardHandleModifiers(void* data, struct wl_keyboard*, uint32_t, uint32_t depressed, uint32_t latched, uint32_t locked, uint32_t group) {
    auto* self = static_cast<DesktopShellClient*>(data);
    if (self->xkb_state_) {
        xkb_state_update_mask(self->xkb_state_, depressed, latched, locked, 0, 0, group);
    }
}

void DesktopShellClient::keyboardHandleRepeatInfo(void*, struct wl_keyboard*, int32_t, int32_t) {}

void DesktopShellClient::bgSurfaceConfigure(void* data, struct xdg_surface* surface, uint32_t serial) {
    xdg_surface_ack_configure(surface, serial);
    auto* self = static_cast<DesktopShellClient*>(data);
    self->renderBackground();
}
void DesktopShellClient::bgToplevelConfigure(void*, struct xdg_toplevel*, int32_t, int32_t, struct wl_array*) {}
void DesktopShellClient::bgToplevelClose(void*, struct xdg_toplevel*) {}

void DesktopShellClient::panelSurfaceConfigure(void* data, struct xdg_surface* surface, uint32_t serial) {
    xdg_surface_ack_configure(surface, serial);
    auto* self = static_cast<DesktopShellClient*>(data);
    self->renderPanel();
}
void DesktopShellClient::panelToplevelConfigure(void*, struct xdg_toplevel*, int32_t, int32_t, struct wl_array*) {}
void DesktopShellClient::panelToplevelClose(void*, struct xdg_toplevel*) {}

void DesktopShellClient::launcherSurfaceConfigure(void* data, struct xdg_surface* surface, uint32_t serial) {
    xdg_surface_ack_configure(surface, serial);
    auto* self = static_cast<DesktopShellClient*>(data);
    self->renderLauncher();
}
void DesktopShellClient::launcherToplevelConfigure(void*, struct xdg_toplevel*, int32_t, int32_t, struct wl_array*) {}
void DesktopShellClient::launcherToplevelClose(void*, struct xdg_toplevel*) {}

// --- Main Thread Routine ---

void DesktopShellClient::threadMain(std::string socket_name) {
    LOGI("SHELL_THREAD_STARTED: Initializing Wayland connection...");

    if (!initWayland(socket_name.c_str())) {
        LOGE("SHELL_THREAD_FAILED: Unable to initialize Wayland client");
        running_.store(false, std::memory_order_relaxed);
        return;
    }

    LOGI("SHELL_CONNECTED: Connected to Wayland compositor successfully");

    // Register WindowModel change listener so panel updates dynamically
    WindowModel::getInstance().addChangeListener([this] {
        renderPanel();
    });

    // Render initial desktop UI
    renderAll();

    struct pollfd pfd[2];
    pfd[0].fd = wl_display_get_fd(display_);
    pfd[0].events = POLLIN;
    pfd[1].fd = wake_pipe_[0];
    pfd[1].events = POLLIN;

    while (running_.load(std::memory_order_relaxed)) {
        while (wl_display_prepare_read(display_) != 0) {
            wl_display_dispatch_pending(display_);
        }

        if (wl_display_flush(display_) < 0 && errno != EAGAIN) {
            wl_display_cancel_read(display_);
            LOGW("wl_display_flush failed: %s", strerror(errno));
            break;
        }

        // 1000ms timeout so the digital clock can update smoothly
        int ret = poll(pfd, 2, 1000);
        if (ret < 0) {
            wl_display_cancel_read(display_);
            if (errno == EINTR) continue;
            LOGE("poll failed: %s", strerror(errno));
            break;
        }

        if (ret == 0) {
            // Timeout: cancel read and re-render panel for clock update
            wl_display_cancel_read(display_);
            renderPanel();
            continue;
        }

        if (pfd[1].revents & POLLIN) {
            // Wake pipe triggered stop
            wl_display_cancel_read(display_);
            break;
        }

        if (pfd[0].revents & POLLIN) {
            wl_display_read_events(display_);
            wl_display_dispatch_pending(display_);
        } else {
            wl_display_cancel_read(display_);
        }
    }

    WindowModel::getInstance().clearChangeListeners();
    cleanupWayland();
    LOGI("SHELL_THREAD_EXITED: Desktop shell thread cleanly exited");
}

bool DesktopShellClient::initWayland(const char* socket_name) {
    display_ = wl_display_connect(socket_name);
    if (!display_) {
        LOGE("Failed to connect to Wayland display '%s'", socket_name);
        return false;
    }

    registry_ = wl_display_get_registry(display_);
    static const struct wl_registry_listener reg_listener = {
        .global = registryHandleGlobal,
        .global_remove = registryHandleGlobalRemove,
    };
    wl_registry_add_listener(registry_, &reg_listener, this);

    wl_display_roundtrip(display_);

    if (!compositor_ || !shm_ || !xdg_wm_base_) {
        LOGE("Missing required Wayland globals (comp=%p, shm=%p, xdg_wm_base=%p)",
             compositor_, shm_, xdg_wm_base_);
        cleanupWayland();
        return false;
    }

    // 1. Background Surface
    bg_surface_ = wl_compositor_create_surface(compositor_);
    bg_xdg_surface_ = xdg_wm_base_get_xdg_surface(xdg_wm_base_, bg_surface_);
    static const struct xdg_surface_listener bg_surf_listener = {
        .configure = bgSurfaceConfigure,
    };
    xdg_surface_add_listener(bg_xdg_surface_, &bg_surf_listener, this);
    bg_xdg_toplevel_ = xdg_surface_get_toplevel(bg_xdg_surface_);
    xdg_toplevel_set_app_id(bg_xdg_toplevel_, "org.linuxdroid.desktop-background");
    xdg_toplevel_set_title(bg_xdg_toplevel_, "LinuxDroid Desktop");
    static const struct xdg_toplevel_listener bg_top_listener = {
        .configure = bgToplevelConfigure,
        .close = bgToplevelClose,
    };
    xdg_toplevel_add_listener(bg_xdg_toplevel_, &bg_top_listener, this);
    wl_surface_commit(bg_surface_);

    // 2. Panel Surface
    panel_surface_ = wl_compositor_create_surface(compositor_);
    panel_xdg_surface_ = xdg_wm_base_get_xdg_surface(xdg_wm_base_, panel_surface_);
    static const struct xdg_surface_listener p_surf_listener = {
        .configure = panelSurfaceConfigure,
    };
    xdg_surface_add_listener(panel_xdg_surface_, &p_surf_listener, this);
    panel_xdg_toplevel_ = xdg_surface_get_toplevel(panel_xdg_surface_);
    xdg_toplevel_set_app_id(panel_xdg_toplevel_, "org.linuxdroid.desktop-panel");
    xdg_toplevel_set_title(panel_xdg_toplevel_, "LinuxDroid Panel");
    static const struct xdg_toplevel_listener p_top_listener = {
        .configure = panelToplevelConfigure,
        .close = panelToplevelClose,
    };
    xdg_toplevel_add_listener(panel_xdg_toplevel_, &p_top_listener, this);
    wl_surface_commit(panel_surface_);

    wl_display_roundtrip(display_);
    return true;
}

void DesktopShellClient::cleanupWayland() {
    bg_buffer_.destroy();
    panel_buffer_.destroy();
    launcher_buffer_.destroy();

    if (launcher_xdg_toplevel_) { xdg_toplevel_destroy(launcher_xdg_toplevel_); launcher_xdg_toplevel_ = nullptr; }
    if (launcher_xdg_surface_) { xdg_surface_destroy(launcher_xdg_surface_); launcher_xdg_surface_ = nullptr; }
    if (launcher_surface_) { wl_surface_destroy(launcher_surface_); launcher_surface_ = nullptr; }

    if (panel_xdg_toplevel_) { xdg_toplevel_destroy(panel_xdg_toplevel_); panel_xdg_toplevel_ = nullptr; }
    if (panel_xdg_surface_) { xdg_surface_destroy(panel_xdg_surface_); panel_xdg_surface_ = nullptr; }
    if (panel_surface_) { wl_surface_destroy(panel_surface_); panel_surface_ = nullptr; }

    if (bg_xdg_toplevel_) { xdg_toplevel_destroy(bg_xdg_toplevel_); bg_xdg_toplevel_ = nullptr; }
    if (bg_xdg_surface_) { xdg_surface_destroy(bg_xdg_surface_); bg_xdg_surface_ = nullptr; }
    if (bg_surface_) { wl_surface_destroy(bg_surface_); bg_surface_ = nullptr; }

    if (pointer_) { wl_pointer_release(pointer_); pointer_ = nullptr; }
    if (keyboard_) { wl_keyboard_release(keyboard_); keyboard_ = nullptr; }
    if (touch_) { wl_touch_release(touch_); touch_ = nullptr; }

    if (xkb_state_) { xkb_state_unref(xkb_state_); xkb_state_ = nullptr; }
    if (xkb_keymap_) { xkb_keymap_unref(xkb_keymap_); xkb_keymap_ = nullptr; }
    if (xkb_ctx_) { xkb_context_unref(xkb_ctx_); xkb_ctx_ = nullptr; }

    if (xdg_wm_base_) { xdg_wm_base_destroy(xdg_wm_base_); xdg_wm_base_ = nullptr; }
    if (seat_) { wl_seat_destroy(seat_); seat_ = nullptr; }
    if (output_) { wl_output_destroy(output_); output_ = nullptr; }
    if (shm_) { wl_shm_destroy(shm_); shm_ = nullptr; }
    if (compositor_) { wl_compositor_destroy(compositor_); compositor_ = nullptr; }
    if (registry_) { wl_registry_destroy(registry_); registry_ = nullptr; }
    if (display_) { wl_display_disconnect(display_); display_ = nullptr; }
}

} // namespace linuxdroid

#if defined(BUILD_STANDALONE_SHELL)
int main(int argc, char* argv[]) {
    const char* sock = (argc > 1) ? argv[1] : "wayland-0";
    linuxdroid::DesktopShellClient client;
    if (!client.start(sock)) {
        return 1;
    }
    while (client.isRunning()) {
        pause();
    }
    return 0;
}
#endif

