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
        { "Terminal", "/bin/bash", "Bash interactive Linux shell" },
        { "POSIX Shell", "/bin/sh", "Standard system command shell" },
        { "Environment", "/usr/bin/env", "Inspect environment variables" },
        { "System Info", "/bin/uname", "Linux kernel & OS release" },
        { "Process List", "/bin/ps", "List current running processes" }
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
    LOGI("SHELL_LAUNCHER_STATE: open=%d", open ? 1 : 0);
    renderAll();
}

void DesktopShellClient::selectNextLauncherItem() {
    std::lock_guard<std::mutex> lock(render_mutex_);
    if (launcher_menu_.empty()) return;
    selected_launcher_item_ = (selected_launcher_item_ + 1) % static_cast<int>(launcher_menu_.size());
    renderLauncher();
}

void DesktopShellClient::selectPrevLauncherItem() {
    std::lock_guard<std::mutex> lock(render_mutex_);
    if (launcher_menu_.empty()) return;
    selected_launcher_item_ = (selected_launcher_item_ - 1 + static_cast<int>(launcher_menu_.size())) % static_cast<int>(launcher_menu_.size());
    renderLauncher();
}

void DesktopShellClient::activateSelectedLauncherItem() {
    size_t idx = 0;
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        if (!launcher_open_ || launcher_menu_.empty()) return;
        idx = static_cast<size_t>(selected_launcher_item_);
    }
    launchApplication(idx);
    setLauncherOpen(false);
}

void DesktopShellClient::launchApplication(size_t index) {
    std::string name, path;
    {
        std::lock_guard<std::mutex> lock(render_mutex_);
        if (index >= launcher_menu_.size()) return;
        name = launcher_menu_[index].name;
        path = launcher_menu_[index].exec_path;
    }

    LOGI("APPLICATION_LAUNCH_REQUEST: app='%s' path='%s'", name.c_str(), path.c_str());

    std::vector<std::string> args = { path };
    launchCustomCommand(path, args);

    // Register launched window in DesktopWindowTracker so panel represents it
    uint64_t fake_id = static_cast<uint64_t>(time(nullptr)) * 1000 + index;
    DesktopWindowTracker::getInstance().registerWindow(fake_id, name, name, nullptr);
    renderPanel();
}

bool DesktopShellClient::launchCustomCommand(const std::string& path, const std::vector<std::string>& args) {
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("APPLICATION_LAUNCH_FAILED: fork failed: %s", strerror(errno));
        return false;
    }

    if (pid == 0) {
        // Child process
        setenv("WAYLAND_DISPLAY", "wayland-0", 1);
        setenv("XDG_RUNTIME_DIR", "/tmp", 1);
        setenv("DISPLAY", ":0", 1);

        std::vector<char*> argv;
        for (const auto& arg : args) {
            argv.push_back(const_cast<char*>(arg.c_str()));
        }
        argv.push_back(nullptr);

        // Check if proot rootfs environment is active
        const char* guest_rootfs = "/data/data/com.linuxdroid/files/rootfs";
        const char* proot_bin = "/data/data/com.linuxdroid/files/proot";

        if (access(proot_bin, X_OK) == 0 && access(guest_rootfs, F_OK) == 0) {
            // Launch inside PRoot guest environment
            std::vector<char*> proot_argv;
            proot_argv.push_back(const_cast<char*>(proot_bin));
            proot_argv.push_back(const_cast<char*>("-r"));
            proot_argv.push_back(const_cast<char*>(guest_rootfs));
            proot_argv.push_back(const_cast<char*>("-0"));
            proot_argv.push_back(const_cast<char*>("-b"));
            proot_argv.push_back(const_cast<char*>("/tmp:/tmp"));
            for (auto* a : argv) {
                if (a) proot_argv.push_back(a);
            }
            proot_argv.push_back(nullptr);
            execv(proot_bin, proot_argv.data());
        }

        // Direct guest or host execution fallback
        execvp(path.c_str(), argv.data());
        _exit(127);
    }

    LOGI("APPLICATION_LAUNCHED: pid=%d command='%s'", pid, path.c_str());
    return true;
}

ShmBuffer DesktopShellClient::createShmBuffer(int width, int height) {
    ShmBuffer buf;
    if (width <= 0 || height <= 0 || shm_ == nullptr) return buf;

    int stride = width * 4;
    size_t size = static_cast<size_t>(stride * height);

    // 1. Try memfd_create
    int fd = -1;
#if defined(__NR_memfd_create) || defined(SYS_memfd_create)
    fd = memfd_create("linuxdroid-shm", MFD_CLOEXEC | MFD_ALLOW_SEALING);
#endif

    // 2. Fallback to mkstemp
    if (fd < 0) {
        char temp_path[] = "/tmp/linuxdroid-shm-XXXXXX";
        fd = mkstemp(temp_path);
        if (fd >= 0) {
            unlink(temp_path);
        }
    }

    if (fd < 0) {
        LOGE("Failed to allocate shm buffer fd: %s", strerror(errno));
        return buf;
    }

    if (ftruncate(fd, size) < 0) {
        LOGE("ftruncate failed for shm buffer: %s", strerror(errno));
        close(fd);
        return buf;
    }

    void* data = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOGE("mmap failed for shm buffer: %s", strerror(errno));
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

void DesktopShellClient::drawRect(ShmBuffer& buf, int x, int y, int w, int h, uint32_t color) {
    if (!buf.pixels || w <= 0 || h <= 0) return;
    int x2 = std::min(x + w, buf.width);
    int y2 = std::min(y + h, buf.height);
    int x1 = std::max(0, x);
    int y1 = std::max(0, y);

    for (int i = x1; i < x2; ++i) {
        if (y1 >= 0 && y1 < buf.height) buf.pixels[y1 * buf.width + i] = color;
        if (y2 - 1 >= 0 && y2 - 1 < buf.height) buf.pixels[(y2 - 1) * buf.width + i] = color;
    }
    for (int j = y1; j < y2; ++j) {
        if (x1 >= 0 && x1 < buf.width) buf.pixels[j * buf.width + x1] = color;
        if (x2 - 1 >= 0 && x2 - 1 < buf.width) buf.pixels[j * buf.width + (x2 - 1)] = color;
    }
}

void DesktopShellClient::drawFilledRect(ShmBuffer& buf, int x, int y, int w, int h, uint32_t color) {
    if (!buf.pixels || w <= 0 || h <= 0) return;
    int x2 = std::min(x + w, buf.width);
    int y2 = std::min(y + h, buf.height);
    int x1 = std::max(0, x);
    int y1 = std::max(0, y);

    for (int j = y1; j < y2; ++j) {
        uint32_t* row = &buf.pixels[j * buf.width];
        for (int i = x1; i < x2; ++i) {
            row[i] = color;
        }
    }
}

void DesktopShellClient::drawText(ShmBuffer& buf, int x, int y, const char* text, uint32_t color) {
    if (!buf.pixels || !text) return;

    int cur_x = x;
    while (*text) {
        uint8_t c = static_cast<uint8_t>(*text);
        if (c >= 32 && c <= 126) {
            const uint8_t* glyph = FONT_8X16[c - 32];
            for (int row = 0; row < 16; ++row) {
                int py = y + row;
                if (py < 0 || py >= buf.height) continue;
                uint8_t bits = glyph[row];
                for (int col = 0; col < 8; ++col) {
                    int px = cur_x + col;
                    if (px < 0 || px >= buf.width) continue;
                    if (bits & (0x80 >> col)) {
                        buf.pixels[py * buf.width + px] = color;
                    }
                }
            }
        }
        cur_x += 8;
        text++;
    }
}

void DesktopShellClient::renderBackground() {
    if (!bg_surface_ || !shm_) return;

    if (bg_buffer_.width != width_ || bg_buffer_.height != height_) {
        bg_buffer_.destroy();
        bg_buffer_ = createShmBuffer(width_, height_);
    }

    if (!bg_buffer_.pixels) return;

    // Deep Slate background (#181f2a)
    drawFilledRect(bg_buffer_, 0, 0, width_, height_, 0xFF181F2A);

    // Subtle decorative grid lines
    for (int y = 40; y < height_; y += 60) {
        for (int x = 40; x < width_; x += 60) {
            drawFilledRect(bg_buffer_, x, y, 2, 2, 0xFF2A3444);
        }
    }

    // Centered LinuxDroid desktop watermark
    const char* brand = "LinuxDroid Desktop GUI";
    int text_len = static_cast<int>(strlen(brand)) * 8;
    int center_x = (width_ - text_len) / 2;
    int center_y = height_ / 3;
    drawText(bg_buffer_, center_x, center_y, brand, 0xFF4B5563);
    const char* subtext = "Pure Wayland Client Desktop Shell (Phase 7)";
    int sub_len = static_cast<int>(strlen(subtext)) * 8;
    drawText(bg_buffer_, (width_ - sub_len) / 2, center_y + 24, subtext, 0xFF374151);

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

    // Panel background: Dark graphite (#1F2937) with top border (#374151)
    drawFilledRect(panel_buffer_, 0, 0, width_, panel_height_, 0xFF1F2937);
    drawFilledRect(panel_buffer_, 0, 0, width_, 2, 0xFF374151);

    // 1. Launcher button: [≡ LinuxDroid]
    uint32_t btn_bg = launcher_open_ ? 0xFF2563EB : 0xFF1E3A8A;
    drawFilledRect(panel_buffer_, 8, 6, 120, 36, btn_bg);
    drawRect(panel_buffer_, 8, 6, 120, 36, 0xFF60A5FA);
    drawText(panel_buffer_, 16, 16, "= LinuxDroid", 0xFFFFFFFF);

    // 2. Window list buttons
    auto windows = DesktopWindowTracker::getInstance().getWindows();
    int win_x = 136;
    for (size_t i = 0; i < windows.size() && win_x + 140 < width_ - 180; ++i) {
        const auto& w = windows[i];
        uint32_t w_bg = w.is_active ? 0xFF047857 : 0xFF374151;
        uint32_t w_border = w.is_active ? 0xFF10B981 : 0xFF4B5563;

        drawFilledRect(panel_buffer_, win_x, 6, 136, 36, w_bg);
        drawRect(panel_buffer_, win_x, 6, 136, 36, w_border);

        std::string label = w.title.substr(0, 14);
        drawText(panel_buffer_, win_x + 8, 16, label.c_str(), 0xFFF9FAFB);
        win_x += 144;
    }

    // 3. Status & Clock area (Right-aligned)
    time_t now = time(nullptr);
    struct tm tstruct{};
    localtime_r(&now, &tstruct);
    char time_str[32];
    snprintf(time_str, sizeof(time_str), "%02d:%02d:%02d", tstruct.tm_hour, tstruct.tm_min, tstruct.tm_sec);

    int clock_x = width_ - 90;
    drawText(panel_buffer_, clock_x, 16, time_str, 0xFF9CA3AF);

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

    // Launcher body: Slate (#1F2937) with border (#4B5563)
    drawFilledRect(launcher_buffer_, 0, 0, launcher_width_, launcher_height_, 0xFF1F2937);
    drawRect(launcher_buffer_, 0, 0, launcher_width_, launcher_height_, 0xFF4B5563);

    // Header: Applications
    drawFilledRect(launcher_buffer_, 0, 0, launcher_width_, 36, 0xFF111827);
    drawText(launcher_buffer_, 12, 10, "Applications", 0xFF60A5FA);

    // Items list
    int item_y = 44;
    for (size_t i = 0; i < launcher_menu_.size(); ++i) {
        bool selected = (static_cast<int>(i) == selected_launcher_item_);
        uint32_t item_bg = selected ? 0xFF374151 : 0xFF1F2937;
        drawFilledRect(launcher_buffer_, 6, item_y, launcher_width_ - 12, 36, item_bg);
        if (selected) {
            drawRect(launcher_buffer_, 6, item_y, launcher_width_ - 12, 36, 0xFF2563EB);
            drawText(launcher_buffer_, 12, item_y + 10, ">", 0xFF60A5FA);
        }

        drawText(launcher_buffer_, 26, item_y + 10, launcher_menu_[i].name.c_str(), selected ? 0xFFFFFFFF : 0xFFD1D5DB);
        item_y += 42;
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
        // Check Launcher button
        if (x >= 8 && x <= 128 && y >= 6 && y <= 42) {
            toggleLauncher();
            return;
        }

        // Check Window buttons
        auto windows = DesktopWindowTracker::getInstance().getWindows();
        int win_x = 136;
        for (size_t i = 0; i < windows.size(); ++i) {
            if (x >= win_x && x <= win_x + 136 && y >= 6 && y <= 42) {
                DesktopWindowTracker::getInstance().requestActivate(windows[i].id);
                renderPanel();
                return;
            }
            win_x += 144;
        }
    } else if (surface == launcher_surface_) {
        int item_y = 44;
        for (size_t i = 0; i < launcher_menu_.size(); ++i) {
            if (x >= 6 && x <= launcher_width_ - 6 && y >= item_y && y <= item_y + 36) {
                selected_launcher_item_ = static_cast<int>(i);
                activateSelectedLauncherItem();
                return;
            }
            item_y += 42;
        }
    } else if (surface == bg_surface_) {
        // Clicking background closes launcher if open
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
    if (flags & WL_OUTPUT_MODE_CURRENT) {
        auto* self = static_cast<DesktopShellClient*>(data);
        self->setOutputGeometry(width, height, self->scale_);
        self->renderAll();
    }
}

void DesktopShellClient::outputHandleDone(void*, struct wl_output*) {}
void DesktopShellClient::outputHandleScale(void* data, struct wl_output*, int32_t factor) {
    auto* self = static_cast<DesktopShellClient*>(data);
    self->scale_ = factor > 0 ? factor : 1;
}

void DesktopShellClient::xdgWmBaseHandlePing(void*, struct xdg_wm_base* shell, uint32_t serial) {
    xdg_wm_base_pong(shell, serial);
}

void DesktopShellClient::seatHandleCapabilities(void* data, struct wl_seat* seat, uint32_t caps) {
    auto* self = static_cast<DesktopShellClient*>(data);

    if ((caps & WL_SEAT_CAPABILITY_POINTER) && !self->pointer_) {
        self->pointer_ = wl_seat_get_pointer(seat);
        static const struct wl_pointer_listener pointer_listener = {
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
        wl_pointer_add_listener(self->pointer_, &pointer_listener, self);
    } else if (!(caps & WL_SEAT_CAPABILITY_POINTER) && self->pointer_) {
        wl_pointer_destroy(self->pointer_);
        self->pointer_ = nullptr;
    }

    if ((caps & WL_SEAT_CAPABILITY_KEYBOARD) && !self->keyboard_) {
        self->keyboard_ = wl_seat_get_keyboard(seat);
        static const struct wl_keyboard_listener keyboard_listener = {
            .keymap = keyboardHandleKeymap,
            .enter = keyboardHandleEnter,
            .leave = keyboardHandleLeave,
            .key = keyboardHandleKey,
            .modifiers = keyboardHandleModifiers,
            .repeat_info = keyboardHandleRepeatInfo,
        };
        wl_keyboard_add_listener(self->keyboard_, &keyboard_listener, self);
    } else if (!(caps & WL_SEAT_CAPABILITY_KEYBOARD) && self->keyboard_) {
        wl_keyboard_destroy(self->keyboard_);
        self->keyboard_ = nullptr;
    }

    if ((caps & WL_SEAT_CAPABILITY_TOUCH) && !self->touch_) {
        self->touch_ = wl_seat_get_touch(seat);
        static const struct wl_touch_listener touch_listener = {
            .down = touchHandleDown,
            .up = touchHandleUp,
            .motion = touchHandleMotion,
            .frame = touchHandleFrame,
            .cancel = touchHandleCancel,
        };
        wl_touch_add_listener(self->touch_, &touch_listener, self);
    } else if (!(caps & WL_SEAT_CAPABILITY_TOUCH) && self->touch_) {
        wl_touch_destroy(self->touch_);
        self->touch_ = nullptr;
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
    if (state == WL_POINTER_BUTTON_STATE_PRESSED && button == 0x110 /* BTN_LEFT */) {
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
    double tx = wl_fixed_to_double(x);
    double ty = wl_fixed_to_double(y);
    self->handlePointerClick(surface, tx, ty);
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

    if (self->xkb_keymap_) xkb_keymap_unref(self->xkb_keymap_);
    if (self->xkb_state_) xkb_state_unref(self->xkb_state_);

    self->xkb_keymap_ = xkb_keymap_new_from_string(self->xkb_ctx_, map_str,
                                                   XKB_KEYMAP_FORMAT_TEXT_V1,
                                                   XKB_KEYMAP_COMPILE_NO_FLAGS);
    munmap(map_str, size);
    close(fd);

    if (self->xkb_keymap_) {
        self->xkb_state_ = xkb_state_new(self->xkb_keymap_);
    }
}

void DesktopShellClient::keyboardHandleEnter(void*, struct wl_keyboard*, uint32_t, struct wl_surface*, struct wl_array*) {}
void DesktopShellClient::keyboardHandleLeave(void*, struct wl_keyboard*, uint32_t, struct wl_surface*) {}

void DesktopShellClient::keyboardHandleKey(void* data, struct wl_keyboard*, uint32_t, uint32_t, uint32_t key, uint32_t state) {
    auto* self = static_cast<DesktopShellClient*>(data);
    if (state != WL_KEYBOARD_KEY_STATE_PRESSED) return;

    xkb_keysym_t sym = XKB_KEY_NoSymbol;
    if (self->xkb_state_) {
        sym = xkb_state_key_get_one_sym(self->xkb_state_, key + 8);
    }

    switch (sym) {
        case XKB_KEY_Super_L:
        case XKB_KEY_Super_R:
            self->toggleLauncher();
            break;
        case XKB_KEY_Escape:
            if (self->launcher_open_) self->setLauncherOpen(false);
            break;
        case XKB_KEY_Tab:
            if (self->launcher_open_) self->selectNextLauncherItem();
            break;
        case XKB_KEY_Up:
            if (self->launcher_open_) self->selectPrevLauncherItem();
            break;
        case XKB_KEY_Down:
            if (self->launcher_open_) self->selectNextLauncherItem();
            break;
        case XKB_KEY_Return:
        case XKB_KEY_KP_Enter:
            if (self->launcher_open_) {
                self->activateSelectedLauncherItem();
            } else {
                self->toggleLauncher();
            }
            break;
        default:
            break;
    }
}

void DesktopShellClient::keyboardHandleModifiers(void* data, struct wl_keyboard*, uint32_t, uint32_t mods_depressed,
                                                 uint32_t mods_latched, uint32_t mods_locked, uint32_t group) {
    auto* self = static_cast<DesktopShellClient*>(data);
    if (self->xkb_state_) {
        xkb_state_update_mask(self->xkb_state_, mods_depressed, mods_latched, mods_locked, 0, 0, group);
    }
}

void DesktopShellClient::keyboardHandleRepeatInfo(void*, struct wl_keyboard*, int32_t, int32_t) {}

// --- Surface Configure Callbacks ---

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

    // Register DesktopWindowTracker listener so panel updates dynamically on window changes
    DesktopWindowTracker::getInstance().setChangeListener([this] {
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

        int ret = poll(pfd, 2, -1);
        if (ret < 0) {
            wl_display_cancel_read(display_);
            if (errno == EINTR) continue;
            LOGE("poll error in desktop shell loop: %s", strerror(errno));
            break;
        }

        if (pfd[1].revents & POLLIN) {
            wl_display_cancel_read(display_);
            char b[16];
            (void)read(wake_pipe_[0], b, sizeof(b));
            break;
        }

        if (pfd[0].revents & POLLIN) {
            wl_display_read_events(display_);
            wl_display_dispatch_pending(display_);
        } else {
            wl_display_cancel_read(display_);
        }
    }

    cleanupWayland();
    LOGI("SHELL_THREAD_EXITED: Desktop shell thread exited cleanly");
}

bool DesktopShellClient::initWayland(const char* socket_name) {
    xkb_ctx_ = xkb_context_new(XKB_CONTEXT_NO_FLAGS);

    display_ = wl_display_connect(socket_name);
    if (!display_) {
        LOGE("wl_display_connect failed for socket '%s'", socket_name);
        return false;
    }

    registry_ = wl_display_get_registry(display_);
    static const struct wl_registry_listener reg_listener = {
        .global = registryHandleGlobal,
        .global_remove = registryHandleGlobalRemove,
    };
    wl_registry_add_listener(registry_, &reg_listener, this);

    wl_display_roundtrip(display_);

    if (!compositor_ || !shm_ || !seat_ || !xdg_wm_base_) {
        LOGE("Missing required Wayland globals (comp=%p shm=%p seat=%p wm=%p)",
             compositor_, shm_, seat_, xdg_wm_base_);
        return false;
    }

    wl_display_roundtrip(display_);

    // 1. Create Desktop Background Surface
    bg_surface_ = wl_compositor_create_surface(compositor_);
    bg_xdg_surface_ = xdg_wm_base_get_xdg_surface(xdg_wm_base_, bg_surface_);
    static const struct xdg_surface_listener bg_surf_listener = {
        .configure = bgSurfaceConfigure,
    };
    xdg_surface_add_listener(bg_xdg_surface_, &bg_surf_listener, this);
    bg_xdg_toplevel_ = xdg_surface_get_toplevel(bg_xdg_surface_);
    xdg_toplevel_set_app_id(bg_xdg_toplevel_, "org.linuxdroid.desktop-background");
    xdg_toplevel_set_title(bg_xdg_toplevel_, "LinuxDroid Background");
    static const struct xdg_toplevel_listener bg_top_listener = {
        .configure = bgToplevelConfigure,
        .close = bgToplevelClose,
    };
    xdg_toplevel_add_listener(bg_xdg_toplevel_, &bg_top_listener, this);
    wl_surface_commit(bg_surface_);

    // 2. Create Panel Surface
    panel_surface_ = wl_compositor_create_surface(compositor_);
    panel_xdg_surface_ = xdg_wm_base_get_xdg_surface(xdg_wm_base_, panel_surface_);
    static const struct xdg_surface_listener pan_surf_listener = {
        .configure = panelSurfaceConfigure,
    };
    xdg_surface_add_listener(panel_xdg_surface_, &pan_surf_listener, this);
    panel_xdg_toplevel_ = xdg_surface_get_toplevel(panel_xdg_surface_);
    xdg_toplevel_set_app_id(panel_xdg_toplevel_, "org.linuxdroid.desktop-panel");
    xdg_toplevel_set_title(panel_xdg_toplevel_, "LinuxDroid Panel");
    static const struct xdg_toplevel_listener pan_top_listener = {
        .configure = panelToplevelConfigure,
        .close = panelToplevelClose,
    };
    xdg_toplevel_add_listener(panel_xdg_toplevel_, &pan_top_listener, this);
    wl_surface_commit(panel_surface_);

    wl_display_roundtrip(display_);
    LOGI("SHELL_SURFACES_CREATED: Background and Panel Wayland surfaces initialized");
    return true;
}

void DesktopShellClient::cleanupWayland() {
    DesktopWindowTracker::getInstance().setChangeListener(nullptr);

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

    if (pointer_) { wl_pointer_destroy(pointer_); pointer_ = nullptr; }
    if (keyboard_) { wl_keyboard_destroy(keyboard_); keyboard_ = nullptr; }
    if (touch_) { wl_touch_destroy(touch_); touch_ = nullptr; }

    if (xdg_wm_base_) { xdg_wm_base_destroy(xdg_wm_base_); xdg_wm_base_ = nullptr; }
    if (seat_) { wl_seat_destroy(seat_); seat_ = nullptr; }
    if (output_) { wl_output_destroy(output_); output_ = nullptr; }
    if (shm_) { wl_shm_destroy(shm_); shm_ = nullptr; }
    if (compositor_) { wl_compositor_destroy(compositor_); compositor_ = nullptr; }
    if (registry_) { wl_registry_destroy(registry_); registry_ = nullptr; }

    if (display_) {
        wl_display_disconnect(display_);
        display_ = nullptr;
    }

    if (xkb_state_) { xkb_state_unref(xkb_state_); xkb_state_ = nullptr; }
    if (xkb_keymap_) { xkb_keymap_unref(xkb_keymap_); xkb_keymap_ = nullptr; }
    if (xkb_ctx_) { xkb_context_unref(xkb_ctx_); xkb_ctx_ = nullptr; }
}

} // namespace linuxdroid

#ifdef BUILD_STANDALONE_SHELL
int main(int argc, char** argv) {
    const char* socket_name = "wayland-0";
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--socket") == 0 && i + 1 < argc) {
            socket_name = argv[++i];
        }
    }

    printf("Starting linuxdroid_desktop_shell on socket '%s'...\n", socket_name);
    linuxdroid::DesktopShellClient client;
    if (!client.start(socket_name)) {
        fprintf(stderr, "Failed to start desktop shell client\n");
        return 1;
    }

    while (client.isRunning()) {
        sleep(1);
    }
    return 0;
}
#endif
