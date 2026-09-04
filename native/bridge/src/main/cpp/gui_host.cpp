#include "gui_host.h"
#include "linuxdroid_backend.h"
#include "android_presentation.h"
#include "input_bridge.h"
#include "input_translator.h"
#include "desktop_shell_client.h"
#include "desktop_window_tracker.h"

#include <wayland-server.h>
#include <libweston/libweston.h>
#include <libweston/weston-log.h>
#include <libweston/desktop.h>

#include <android/log.h>
#include <sys/eventfd.h>
#include <unistd.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <cerrno>
#include <cstdarg>
#include <cstring>

#define TAG "LinuxDroid/GuiHost"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {
namespace gui {

const char* lifecycleStateToString(LifecycleState state) {
    switch (state) {
        case LifecycleState::STOPPED:  return "STOPPED";
        case LifecycleState::STARTING: return "STARTING";
        case LifecycleState::RUNNING:  return "RUNNING";
        case LifecycleState::STOPPING: return "STOPPING";
        default:                       return "UNKNOWN";
    }
}

namespace {

struct DesktopSurfaceContext {
    uint64_t id{0};
    struct weston_desktop_surface* desktop_surface{nullptr};
    struct weston_view* view{nullptr};
    std::string app_id;
    std::string title;
    bool mapped{false};
};

int handleWakeEvent(int fd, uint32_t mask, void* data) {
    (void)mask;
    (void)data;
    uint64_t val = 0;
    while (read(fd, &val, sizeof(val)) > 0) {}
    GuiHost::getInstance().processQueuedInput();
    return 0;
}

int westonLogHandler(const char* fmt, va_list ap) {
    return __android_log_vprint(ANDROID_LOG_INFO, "LinuxDroid/Weston", fmt, ap);
}

int westonLogContinueHandler(const char* fmt, va_list ap) {
    return __android_log_vprint(ANDROID_LOG_INFO, "LinuxDroid/Weston", fmt, ap);
}

} // anonymous namespace

GuiHost& GuiHost::getInstance() {
    static GuiHost instance;
    return instance;
}

GuiHost::GuiHost()
    : state_(LifecycleState::STOPPED),
      wake_fd_(-1),
      display_(nullptr),
      wake_source_(nullptr),
      log_ctx_(nullptr),
      compositor_(nullptr),
      backend_(nullptr),
      head_(nullptr),
      output_(nullptr),
      desktop_(nullptr),
      window_cascade_count_(0),
      init_success_(false) {}

GuiHost::~GuiHost() {
    stop();
}

bool GuiHost::start() {
    std::unique_lock<std::mutex> lock(lifecycle_mutex_);

    if (state_ == LifecycleState::RUNNING) {
        LOGI("WESTON_START_BEGIN: GUI host start requested while already running");
        return true;
    }

    if (state_ == LifecycleState::STARTING) {
        init_cv_.wait(lock, [this] { return state_ != LifecycleState::STARTING; });
        return state_ == LifecycleState::RUNNING;
    }

    if (state_ == LifecycleState::STOPPING) {
        init_cv_.wait(lock, [this] { return state_ == LifecycleState::STOPPED; });
    }

    LOGI("WESTON_START_BEGIN: starting native GUI host");
    state_ = LifecycleState::STARTING;
    init_success_ = false;

    // Create wake eventfd to allow deterministic asynchronous interruption of the Wayland event loop
    wake_fd_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (wake_fd_ < 0) {
        LOGE("WESTON_START_FAILED: failed to create eventfd: %s", strerror(errno));
        state_ = LifecycleState::STOPPED;
        return false;
    }

    if (worker_thread_.joinable()) {
        worker_thread_.join();
    }

    worker_thread_ = std::thread(&GuiHost::workerMain, this);

    init_cv_.wait(lock, [this] {
        return state_ != LifecycleState::STARTING;
    });

    if (state_ == LifecycleState::RUNNING) {
        InputBridge::getInstance().setWakeFd(wake_fd_);
        LOGI("WESTON_START_BEGIN: GUI host started successfully");
        return true;
    } else {
        LOGE("WESTON_START_FAILED: GUI host initialization failed");
        InputBridge::getInstance().setWakeFd(-1);
        if (worker_thread_.joinable()) {
            worker_thread_.join();
        }
        if (wake_fd_ >= 0) {
            close(wake_fd_);
            wake_fd_ = -1;
        }
        state_ = LifecycleState::STOPPED;
        return false;
    }
}

bool GuiHost::stop() {
    std::unique_lock<std::mutex> lock(lifecycle_mutex_);

    if (state_ == LifecycleState::STOPPED) {
        LOGI("WESTON_STOP_REQUEST: GUI host stop requested while already stopped");
        return true;
    }

    if (state_ == LifecycleState::STARTING) {
        init_cv_.wait(lock, [this] { return state_ != LifecycleState::STARTING; });
        if (state_ == LifecycleState::STOPPED) {
            return true;
        }
    }

    if (state_ == LifecycleState::STOPPING) {
        init_cv_.wait(lock, [this] { return state_ == LifecycleState::STOPPED; });
        return true;
    }

    LOGI("WESTON_STOP_REQUEST: GUI host stopping");
    state_ = LifecycleState::STOPPING;

    // Stop Desktop Shell client before terminating compositor
    if (shell_client_) {
        shell_client_->stop();
    }

    InputBridge::getInstance().setWakeFd(-1);
    if (backend_ != nullptr) {
        linuxdroid_backend_reset_input(backend_);
    }

    // Signal Wayland display loop to terminate
    if (display_ != nullptr) {
        wl_display_terminate(display_);
    }

    // Wake the epoll event loop immediately
    if (wake_fd_ >= 0) {
        uint64_t val = 1;
        write(wake_fd_, &val, sizeof(val));
    }

    // Unlock mutex while joining worker thread so worker can perform clean resource teardown
    lock.unlock();

    if (worker_thread_.joinable()) {
        worker_thread_.join();
    }

    lock.lock();

    if (wake_fd_ >= 0) {
        close(wake_fd_);
        wake_fd_ = -1;
    }

    state_ = LifecycleState::STOPPED;
    init_cv_.notify_all();

    LOGI("WESTON_STOPPED: GUI host stopped");
    return true;
}

bool GuiHost::isRunning() const {
    return state_ == LifecycleState::RUNNING;
}

LifecycleState GuiHost::getState() const {
    return state_.load(std::memory_order_relaxed);
}

void GuiHost::setNativeWindow(ANativeWindow* window, int width, int height) {
    std::lock_guard<std::mutex> lock(window_mutex_);
    native_window_ = window;
    window_width_ = width;
    window_height_ = height;

    if (output_ != nullptr) {
        linuxdroid_output_set_window(output_, window);
        if (width > 0 && height > 0) {
            linuxdroid_output_resize(output_, width, height);
        }
    }

    if (shell_client_ && width > 0 && height > 0) {
        shell_client_->setOutputGeometry(width, height, 1);
        shell_client_->renderAll();
    }
}

void GuiHost::changeNativeWindow(ANativeWindow* window, int width, int height, int format) {
    std::lock_guard<std::mutex> lock(window_mutex_);
    native_window_ = window;
    window_width_ = width;
    window_height_ = height;
    window_format_ = format;

    if (backend_ != nullptr) {
        linuxdroid_backend_reset_input(backend_);
    }

    if (output_ != nullptr) {
        linuxdroid_output_set_window(output_, window);
        if (width > 0 && height > 0) {
            linuxdroid_output_resize(output_, width, height);
        }
    }

    if (shell_client_ && width > 0 && height > 0) {
        shell_client_->setOutputGeometry(width, height, 1);
        shell_client_->renderAll();
    }
}

void GuiHost::destroyNativeWindow() {
    std::lock_guard<std::mutex> lock(window_mutex_);
    native_window_ = nullptr;
    if (backend_ != nullptr) {
        linuxdroid_backend_reset_input(backend_);
    }
    if (output_ != nullptr) {
        linuxdroid_output_set_window(output_, nullptr);
    }
}

void GuiHost::handleSurfaceAdded(struct weston_desktop_surface* surface, void* user_data) {
    (void)user_data;
    struct weston_view* view = weston_desktop_surface_create_view(surface);
    if (!view) {
        LOGE("WESTON_DESKTOP_SURFACE_ADDED: failed to create view for surface %p", surface);
        return;
    }

    static std::atomic<uint64_t> s_id_seq{1};
    auto* ctx = new DesktopSurfaceContext();
    ctx->id = s_id_seq.fetch_add(1);
    ctx->desktop_surface = surface;
    ctx->view = view;

    const char* app_id = weston_desktop_surface_get_app_id(surface);
    const char* title = weston_desktop_surface_get_title(surface);
    ctx->app_id = app_id ? app_id : "";
    ctx->title = title ? title : "";

    weston_desktop_surface_set_user_data(surface, ctx);

    LOGI("WESTON_DESKTOP_SURFACE_ADDED: id=%" PRIu64 " app_id='%s' title='%s'",
         ctx->id, ctx->app_id.c_str(), ctx->title.c_str());

    if (ctx->app_id != "org.linuxdroid.desktop-background" &&
        ctx->app_id != "org.linuxdroid.desktop-panel" &&
        ctx->app_id != "org.linuxdroid.desktop-launcher") {
        DesktopWindowTracker::getInstance().registerWindow(ctx->id, ctx->app_id, ctx->title, surface);
    }
}

void GuiHost::handleSurfaceRemoved(struct weston_desktop_surface* surface, void* user_data) {
    (void)user_data;
    auto* ctx = static_cast<DesktopSurfaceContext*>(weston_desktop_surface_get_user_data(surface));
    if (ctx) {
        LOGI("WESTON_DESKTOP_SURFACE_REMOVED: id=%" PRIu64 " app_id='%s'", ctx->id, ctx->app_id.c_str());
        if (ctx->app_id != "org.linuxdroid.desktop-background" &&
            ctx->app_id != "org.linuxdroid.desktop-panel" &&
            ctx->app_id != "org.linuxdroid.desktop-launcher") {
            DesktopWindowTracker::getInstance().unregisterWindow(ctx->id);
        }
        if (ctx->view) {
            weston_desktop_surface_unlink_view(ctx->view);
            weston_view_destroy(ctx->view);
            ctx->view = nullptr;
        }
        weston_desktop_surface_set_user_data(surface, nullptr);
        delete ctx;
    }
}

void GuiHost::handleSurfaceCommitted(struct weston_desktop_surface* surface, struct weston_coord_surface buf_offset, void* user_data) {
    (void)buf_offset;
    auto* host = static_cast<GuiHost*>(user_data);
    auto* ctx = static_cast<DesktopSurfaceContext*>(weston_desktop_surface_get_user_data(surface));
    if (!ctx || !ctx->view) return;

    struct weston_surface* w_surface = weston_desktop_surface_get_surface(surface);
    if (!w_surface) return;

    if (!weston_surface_is_mapped(w_surface)) {
        weston_surface_map(w_surface);
        ctx->mapped = true;

        int out_w = host->output_ ? host->output_->width : LINUXDROID_DEFAULT_WIDTH;
        int out_h = host->output_ ? host->output_->height : LINUXDROID_DEFAULT_HEIGHT;

        if (ctx->app_id == "org.linuxdroid.desktop-background") {
            weston_view_move_to_layer(ctx->view, &host->background_layer_.view_list);
            struct weston_coord_global pos = { .c = { 0.0, 0.0 } };
            weston_view_set_position(ctx->view, pos);
            LOGI("WESTON_SURFACE_MAPPED: Background surface positioned at (0,0) size %dx%d", out_w, out_h);
        } else if (ctx->app_id == "org.linuxdroid.desktop-panel") {
            weston_view_move_to_layer(ctx->view, &host->panel_layer_.view_list);
            double panel_y = static_cast<double>(out_h - 48);
            struct weston_coord_global pos = { .c = { 0.0, panel_y } };
            weston_view_set_position(ctx->view, pos);
            LOGI("WESTON_SURFACE_MAPPED: Panel surface positioned at (0,%.0f)", panel_y);
        } else if (ctx->app_id == "org.linuxdroid.desktop-launcher") {
            weston_view_move_to_layer(ctx->view, &host->panel_layer_.view_list);
            double launcher_h = 280.0;
            double launcher_y = static_cast<double>(out_h - 48) - launcher_h;
            struct weston_coord_global pos = { .c = { 8.0, launcher_y } };
            weston_view_set_position(ctx->view, pos);
            LOGI("WESTON_SURFACE_MAPPED: Launcher surface positioned at (8,%.0f)", launcher_y);
        } else {
            weston_view_move_to_layer(ctx->view, &host->desktop_layer_.view_list);
            double win_x = 40.0 + (host->window_cascade_count_ % 8) * 30.0;
            double win_y = 40.0 + (host->window_cascade_count_ % 8) * 30.0;
            host->window_cascade_count_++;
            struct weston_coord_global pos = { .c = { win_x, win_y } };
            weston_view_set_position(ctx->view, pos);

            struct weston_seat* seat = host->backend_ ? linuxdroid_backend_get_seat(host->backend_) : nullptr;
            if (seat) {
                weston_view_activate_input(ctx->view, seat, WESTON_ACTIVATE_FLAG_CONFIGURE);
            }
            weston_desktop_surface_set_activated(surface, true);
            DesktopWindowTracker::getInstance().setWindowActive(ctx->id, true);
            LOGI("WESTON_SURFACE_MAPPED: Application window (id=%" PRIu64 ") positioned at (%.0f,%.0f)", ctx->id, win_x, win_y);
        }
    }
}

void GuiHost::handleSurfaceMove(struct weston_desktop_surface*, struct weston_seat*, uint32_t, void*) {}
void GuiHost::handleSurfaceResize(struct weston_desktop_surface*, struct weston_seat*, uint32_t, enum weston_desktop_surface_edge, void*) {}

void GuiHost::handleFullscreenRequested(struct weston_desktop_surface* surface, bool fullscreen, struct weston_output*, void*) {
    weston_desktop_surface_set_fullscreen(surface, fullscreen);
}

void GuiHost::handleMaximizedRequested(struct weston_desktop_surface* surface, bool maximized, void*) {
    weston_desktop_surface_set_maximized(surface, maximized);
}

void GuiHost::handleMinimizedRequested(struct weston_desktop_surface*, void*) {}
void GuiHost::handlePingTimeout(struct weston_desktop_client*, void*) {}
void GuiHost::handlePong(struct weston_desktop_client*, void*) {}

void GuiHost::workerMain() {
    // 0. Ensure environment directories for Wayland runtime & XKB data
    if (!getenv("XDG_RUNTIME_DIR")) {
        setenv("XDG_RUNTIME_DIR", "/tmp", 0);
    }
    if (!getenv("XKB_CONFIG_ROOT")) {
        if (access("/usr/share/X11/xkb", R_OK) == 0) {
            setenv("XKB_CONFIG_ROOT", "/usr/share/X11/xkb", 0);
        } else if (access("/data/data/com.linuxdroid/files/xkb", R_OK) == 0) {
            setenv("XKB_CONFIG_ROOT", "/data/data/com.linuxdroid/files/xkb", 0);
        }
    }
    if (!getenv("WESTON_MODULE_MAP")) {
        std::string mod_map = "gl-renderer.so=libgl-renderer.so;";
        if (access("/workspaces/LinuxDroid/native/weston/prefix/lib/libweston-17/gl-renderer.so", R_OK) == 0) {
            mod_map += "gl-renderer.so=/workspaces/LinuxDroid/native/weston/prefix/lib/libweston-17/gl-renderer.so;";
        }
        setenv("WESTON_MODULE_MAP", mod_map.c_str(), 0);
    }

    // 1. Create Wayland Server Display
    display_ = wl_display_create();
    if (display_ == nullptr) {
        LOGE("WESTON_START_FAILED: failed to create Wayland display");
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }
    LOGI("WESTON_DISPLAY_CREATED: wl_display created successfully");

    // Initialize shared memory buffers for Wayland clients
    wl_display_init_shm(display_);

    // Add UNIX domain socket for client connections
    const char* socket_name = "wayland-0";
    if (wl_display_add_socket(display_, socket_name) >= 0) {
        setenv("WAYLAND_DISPLAY", socket_name, 1);
        LOGI("WESTON_SOCKET_CREATED: Wayland socket '%s' added successfully", socket_name);
    } else {
        LOGW("WESTON_SOCKET_WARNING: failed to add Wayland socket '%s' (XDG_RUNTIME_DIR=%s)",
             socket_name, getenv("XDG_RUNTIME_DIR") ? getenv("XDG_RUNTIME_DIR") : "(null)");
    }

    // 2. Register wake_fd_ with Wayland event loop for deterministic termination
    struct wl_event_loop* loop = wl_display_get_event_loop(display_);
    if (loop == nullptr) {
        LOGE("WESTON_START_FAILED: failed to get Wayland event loop");
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    wake_source_ = wl_event_loop_add_fd(loop, wake_fd_, WL_EVENT_READABLE, handleWakeEvent, nullptr);
    if (wake_source_ == nullptr) {
        LOGE("WESTON_START_FAILED: failed to add wake event source to Wayland event loop");
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    // 3. Route Weston logging to Android logcat
    weston_log_set_handler(westonLogHandler, westonLogContinueHandler);

    // 4. Initialize Weston log context and compositor runtime
    log_ctx_ = weston_log_ctx_create();
    if (log_ctx_ == nullptr) {
        LOGE("WESTON_START_FAILED: failed to create Weston log context");
        wl_event_source_remove(wake_source_);
        wake_source_ = nullptr;
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    compositor_ = weston_compositor_create(display_, log_ctx_, nullptr, nullptr);
    if (compositor_ == nullptr) {
        LOGE("WESTON_START_FAILED: failed to create libweston compositor");
        weston_log_ctx_destroy(log_ctx_);
        log_ctx_ = nullptr;
        wl_event_source_remove(wake_source_);
        wake_source_ = nullptr;
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }
    LOGI("WESTON_COMPOSITOR_CREATED: libweston compositor created successfully");

    // 5. Initialize LinuxDroid custom backend with GLES hardware renderer
    struct linuxdroid_backend_config backend_config = {
        .refresh_mhz = LINUXDROID_DEFAULT_REFRESH_MHZ,
        .renderer_type = LINUXDROID_RENDERER_GLES,
    };
    backend_ = linuxdroid_backend_create(compositor_, &backend_config);
    if (backend_ == nullptr) {
        LOGE("WESTON_START_FAILED: failed to create LinuxDroid backend (GLES initialization failed; strict Phase 8 policy prohibits silent fallback)");
        weston_compositor_destroy(compositor_);
        compositor_ = nullptr;
        weston_log_ctx_destroy(log_ctx_);
        log_ctx_ = nullptr;
        wl_event_source_remove(wake_source_);
        wake_source_ = nullptr;
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    // Query & log GLES device information
    const char* gl_vendor = (const char*)glGetString(GL_VENDOR);
    const char* gl_renderer = (const char*)glGetString(GL_RENDERER);
    const char* gl_version = (const char*)glGetString(GL_VERSION);
    LOGI("GLES_DEVICE_INFO: vendor='%s', renderer='%s', version='%s'",
         gl_vendor ? gl_vendor : "unknown",
         gl_renderer ? gl_renderer : "unknown",
         gl_version ? gl_version : "unknown");

    // 6. Create logical display head
    head_ = linuxdroid_head_create(backend_, "linuxdroid-head-0", 70, 150);
    if (head_ == nullptr) {
        LOGE("WESTON_START_FAILED: failed to create LinuxDroid head");
        weston_compositor_destroy(compositor_);
        compositor_ = nullptr;
        backend_ = nullptr;
        weston_log_ctx_destroy(log_ctx_);
        log_ctx_ = nullptr;
        wl_event_source_remove(wake_source_);
        wake_source_ = nullptr;
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    // 7. Create compositor output and attach head
    output_ = weston_compositor_create_output(compositor_, &head_->base, "linuxdroid-output-0");
    if (output_ == nullptr) {
        LOGE("WESTON_START_FAILED: failed to create LinuxDroid output");
        weston_compositor_destroy(compositor_);
        compositor_ = nullptr;
        backend_ = nullptr;
        head_ = nullptr;
        weston_log_ctx_destroy(log_ctx_);
        log_ctx_ = nullptr;
        wl_event_source_remove(wake_source_);
        wake_source_ = nullptr;
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    // Attach active native window if provided before start
    {
        std::lock_guard<std::mutex> lock(window_mutex_);
        if (native_window_ != nullptr) {
            linuxdroid_output_set_window(output_, native_window_);
        }
    }

    // 8. Configure output mode using current window dimensions or defaults
    int32_t out_w = LINUXDROID_DEFAULT_WIDTH;
    int32_t out_h = LINUXDROID_DEFAULT_HEIGHT;
    {
        std::lock_guard<std::mutex> lock(window_mutex_);
        if (window_width_ > 0 && window_height_ > 0) {
            out_w = window_width_;
            out_h = window_height_;
        }
    }

    if (linuxdroid_output_set_mode(output_, out_w, out_h, LINUXDROID_DEFAULT_REFRESH_MHZ, 1) < 0) {
        LOGE("WESTON_START_FAILED: failed to set mode on LinuxDroid output");
        weston_compositor_destroy(compositor_);
        compositor_ = nullptr;
        backend_ = nullptr;
        head_ = nullptr;
        output_ = nullptr;
        weston_log_ctx_destroy(log_ctx_);
        log_ctx_ = nullptr;
        wl_event_source_remove(wake_source_);
        wake_source_ = nullptr;
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    // 9. Enable output
    if (weston_output_enable(output_) < 0) {
        LOGE("WESTON_START_FAILED: failed to enable LinuxDroid output");
        weston_compositor_destroy(compositor_);
        compositor_ = nullptr;
        backend_ = nullptr;
        head_ = nullptr;
        output_ = nullptr;
        weston_log_ctx_destroy(log_ctx_);
        log_ctx_ = nullptr;
        wl_event_source_remove(wake_source_);
        wake_source_ = nullptr;
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    // 10. Initialize Layer hierarchy for desktop shell
    weston_layer_init(&background_layer_, compositor_);
    weston_layer_set_position(&background_layer_, WESTON_LAYER_POSITION_BACKGROUND);

    weston_layer_init(&desktop_layer_, compositor_);
    weston_layer_set_position(&desktop_layer_, WESTON_LAYER_POSITION_NORMAL);

    weston_layer_init(&panel_layer_, compositor_);
    weston_layer_set_position(&panel_layer_, WESTON_LAYER_POSITION_UI);

    // 11. Initialize weston_desktop (registers xdg_wm_base Wayland global)
    static const struct weston_desktop_api desktop_api = {
        .struct_size = sizeof(struct weston_desktop_api),
        .ping_timeout = GuiHost::handlePingTimeout,
        .pong = GuiHost::handlePong,
        .surface_added = GuiHost::handleSurfaceAdded,
        .surface_removed = GuiHost::handleSurfaceRemoved,
        .committed = GuiHost::handleSurfaceCommitted,
        .show_window_menu = nullptr,
        .set_parent = nullptr,
        .move = GuiHost::handleSurfaceMove,
        .resize = GuiHost::handleSurfaceResize,
        .fullscreen_requested = GuiHost::handleFullscreenRequested,
        .maximized_requested = GuiHost::handleMaximizedRequested,
        .minimized_requested = GuiHost::handleMinimizedRequested,
        .set_xwayland_position = nullptr,
        .get_position = nullptr,
    };
    desktop_ = weston_desktop_create(compositor_, &desktop_api, this);
    if (desktop_ == nullptr) {
        LOGW("WESTON_DESKTOP_WARNING: failed to create weston_desktop");
    } else {
        LOGI("WESTON_DESKTOP_CREATED: weston_desktop initialized (xdg_wm_base enabled)");
    }

    // Connect DesktopWindowTracker action handler to weston desktop surfaces
    DesktopWindowTracker::getInstance().setActionHandler([this](uint64_t window_id, const std::string& action) {
        struct weston_seat* seat = (backend_ ? linuxdroid_backend_get_seat(backend_) : nullptr);
        DesktopWindowEntry entry;
        if (DesktopWindowTracker::getInstance().getWindow(window_id, &entry) && entry.native_handle) {
            auto* dsurface = static_cast<struct weston_desktop_surface*>(entry.native_handle);
            if (action == "activate") {
                auto* ctx = static_cast<DesktopSurfaceContext*>(weston_desktop_surface_get_user_data(dsurface));
                if (ctx && ctx->view && seat) {
                    weston_view_activate_input(ctx->view, seat, WESTON_ACTIVATE_FLAG_CLICKED);
                }
                weston_desktop_surface_set_activated(dsurface, true);
                DesktopWindowTracker::getInstance().setWindowActive(window_id, true);
            } else if (action == "close") {
                weston_desktop_surface_close(dsurface);
            }
        }
    });

    // Launch DesktopShellClient in dedicated client thread
    shell_client_ = std::make_unique<DesktopShellClient>();
    shell_client_->setOutputGeometry(out_w, out_h, 1);
    shell_client_->start(socket_name);

    // 12. Initialization successful: signal RUNNING to waiter
    {
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::RUNNING;
        init_success_ = true;
        init_cv_.notify_all();
    }
    LOGI("WESTON_EVENT_LOOP_STARTED: running Wayland/libweston event loop with LinuxDroid backend");

    // 13. Run Wayland event loop (blocks until wl_display_terminate is called)
    wl_display_run(display_);
    LOGI("WESTON_EVENT_LOOP_STOPPED: Wayland/libweston event loop stopped");

    // Clean up Desktop Shell client
    if (shell_client_) {
        shell_client_->stop();
        shell_client_.reset();
    }
    DesktopWindowTracker::getInstance().setActionHandler(nullptr);

    if (desktop_ != nullptr) {
        weston_desktop_destroy(desktop_);
        desktop_ = nullptr;
    }

    weston_layer_fini(&panel_layer_);
    weston_layer_fini(&desktop_layer_);
    weston_layer_fini(&background_layer_);

    // 14. Teardown native state deterministically in reverse order of ownership
    if (output_ != nullptr) {
        linuxdroid_output_set_window(output_, nullptr);
        if (output_->enabled) {
            weston_output_disable(output_);
        }
    }

    // weston_compositor_destroy shuts down outputs and backends, releases heads, and frees compositor
    if (compositor_ != nullptr) {
        weston_compositor_destroy(compositor_);
        compositor_ = nullptr;
        backend_ = nullptr;
        head_ = nullptr;
        output_ = nullptr;
        LOGI("WESTON_COMPOSITOR_DESTROYED: libweston compositor destroyed cleanly");
    }

    if (log_ctx_ != nullptr) {
        weston_log_ctx_destroy(log_ctx_);
        log_ctx_ = nullptr;
    }

    if (wake_source_ != nullptr) {
        wl_event_source_remove(wake_source_);
        wake_source_ = nullptr;
    }

    if (display_ != nullptr) {
        wl_display_destroy(display_);
        display_ = nullptr;
        LOGI("WESTON_DISPLAY_DESTROYED: Wayland display destroyed cleanly");
    }

    LOGI("WESTON_STOPPED: native GUI host stopped");
}

void GuiHost::processQueuedInput() {
    if (state_.load(std::memory_order_relaxed) != LifecycleState::RUNNING ||
        backend_ == nullptr || compositor_ == nullptr) {
        return;
    }

    struct weston_seat* seat = linuxdroid_backend_get_seat(backend_);
    if (seat == nullptr) return;

    struct weston_touch_device* touch_dev = linuxdroid_backend_get_touch_device(backend_);

    NativeInputEvent evt;
    while (InputBridge::getInstance().popEvent(&evt)) {
        struct timespec ts;
        if (evt.timestampNs > 0) {
            ts.tv_sec = static_cast<time_t>(evt.timestampNs / 1000000000ULL);
            ts.tv_nsec = static_cast<long>(evt.timestampNs % 1000000000ULL);
        } else {
            weston_compositor_read_presentation_clock(compositor_, &ts);
        }

        switch (evt.type) {
            case InputEventType::KEY_PRESS:
            case InputEventType::KEY_RELEASE: {
                uint32_t key = InputTranslator::androidKeycodeToLinux(evt.keyCode);
                if (key == KEY_RESERVED) {
                    LOGW("INPUT_UNKNOWN_KEYCODE: unmapped android_kc=%d", evt.keyCode);
                    break;
                }
                struct weston_key_event key_event = {};
                key_event.base.ts = ts;
                key_event.base.seat = seat;
                key_event.key = key;
                key_event.key_state = (evt.type == InputEventType::KEY_PRESS)
                                          ? WL_KEYBOARD_KEY_STATE_PRESSED
                                          : WL_KEYBOARD_KEY_STATE_RELEASED;
                key_event.key_update_state = STATE_UPDATE_AUTOMATIC;
                notify_key(&key_event);
                break;
            }

            case InputEventType::MOUSE_MOVE: {
                int w = output_ ? output_->width : window_width_;
                int h = output_ ? output_->height : window_height_;
                double cx = InputTranslator::clampCoordinate(evt.x, w);
                double cy = InputTranslator::clampCoordinate(evt.y, h);
                struct weston_coord_global pos = { .c = { .x = cx, .y = cy } };
                struct weston_pointer_motion_event motion_event = {};
                motion_event.base.ts = ts;
                motion_event.base.seat = seat;
                motion_event.mask = WESTON_POINTER_MOTION_ABS;
                motion_event.abs = pos;
                notify_motion(&motion_event);
                notify_pointer_frame(seat);
                break;
            }

            case InputEventType::MOUSE_DOWN:
            case InputEventType::MOUSE_UP: {
                uint32_t button = InputTranslator::androidButtonToLinux(evt.id);
                struct weston_pointer_button_event btn_event = {};
                btn_event.base.ts = ts;
                btn_event.base.seat = seat;
                btn_event.button = button;
                btn_event.button_state = (evt.type == InputEventType::MOUSE_DOWN)
                                             ? WL_POINTER_BUTTON_STATE_PRESSED
                                             : WL_POINTER_BUTTON_STATE_RELEASED;
                notify_button(&btn_event);
                notify_pointer_frame(seat);
                break;
            }

            case InputEventType::MOUSE_SCROLL: {
                if (evt.scrollY != 0.0f) {
                    struct weston_pointer_axis_event axis_ev = {};
                    axis_ev.base.ts = ts;
                    axis_ev.base.seat = seat;
                    axis_ev.axis = WL_POINTER_AXIS_VERTICAL_SCROLL;
                    axis_ev.value = InputTranslator::translateScrollAxis(evt.scrollY);
                    notify_axis(&axis_ev);
                }
                if (evt.scrollX != 0.0f) {
                    struct weston_pointer_axis_event axis_ev = {};
                    axis_ev.base.ts = ts;
                    axis_ev.base.seat = seat;
                    axis_ev.axis = WL_POINTER_AXIS_HORIZONTAL_SCROLL;
                    axis_ev.value = -InputTranslator::translateScrollAxis(evt.scrollX);
                    notify_axis(&axis_ev);
                }
                notify_pointer_frame(seat);
                break;
            }

            case InputEventType::TOUCH_DOWN:
            case InputEventType::TOUCH_MOVE:
            case InputEventType::TOUCH_UP: {
                if (touch_dev != nullptr) {
                    int w = output_ ? output_->width : window_width_;
                    int h = output_ ? output_->height : window_height_;
                    double cx = InputTranslator::clampCoordinate(evt.x, w);
                    double cy = InputTranslator::clampCoordinate(evt.y, h);
                    struct weston_coord_global pos = { .c = { .x = cx, .y = cy } };
                    int32_t ttype = WL_TOUCH_MOTION;
                    if (evt.type == InputEventType::TOUCH_DOWN) ttype = WL_TOUCH_DOWN;
                    else if (evt.type == InputEventType::TOUCH_UP) ttype = WL_TOUCH_UP;

                    struct weston_touch_event touch_ev = {};
                    touch_ev.base.ts = ts;
                    touch_ev.base.seat = seat;
                    touch_ev.device = touch_dev;
                    touch_ev.touch_type = ttype;
                    touch_ev.touch_id = evt.id;
                    touch_ev.pos = pos;
                    notify_touch(&touch_ev);
                    notify_touch_frame(touch_dev);
                }
                break;
            }

            case InputEventType::TOUCH_CANCEL: {
                if (touch_dev != nullptr) {
                    notify_touch_cancel(touch_dev);
                }
                break;
            }
        }
    }
}

} // namespace gui
} // namespace linuxdroid
