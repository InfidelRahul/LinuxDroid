#include "gui_host.h"
#include "linuxdroid_backend.h"
#include "android_presentation.h"

#include <wayland-server.h>
#include <libweston/libweston.h>
#include <libweston/weston-log.h>

#include <android/log.h>
#include <sys/eventfd.h>
#include <unistd.h>
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

int handleWakeEvent(int fd, uint32_t mask, void* data) {
    (void)mask;
    (void)data;
    uint64_t val = 0;
    while (read(fd, &val, sizeof(val)) > 0) {}
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
        LOGI("WESTON_START_BEGIN: GUI host started successfully");
        return true;
    } else {
        LOGE("WESTON_START_FAILED: GUI host initialization failed");
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
}

void GuiHost::changeNativeWindow(ANativeWindow* window, int width, int height, int format) {
    std::lock_guard<std::mutex> lock(window_mutex_);
    native_window_ = window;
    window_width_ = width;
    window_height_ = height;
    window_format_ = format;

    if (output_ != nullptr) {
        linuxdroid_output_set_window(output_, window);
        if (width > 0 && height > 0) {
            linuxdroid_output_resize(output_, width, height);
        }
    }
}

void GuiHost::destroyNativeWindow() {
    std::lock_guard<std::mutex> lock(window_mutex_);
    native_window_ = nullptr;
    if (output_ != nullptr) {
        linuxdroid_output_set_window(output_, nullptr);
    }
}

void GuiHost::workerMain() {
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

    // 5. Initialize LinuxDroid custom backend
    struct linuxdroid_backend_config backend_config = {
        .refresh_mhz = LINUXDROID_DEFAULT_REFRESH_MHZ
    };
    backend_ = linuxdroid_backend_create(compositor_, &backend_config);
    if (backend_ == nullptr) {
        LOGE("WESTON_START_FAILED: failed to create LinuxDroid backend");
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

    // 10. Initialization successful: signal RUNNING to waiter
    {
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::RUNNING;
        init_success_ = true;
        init_cv_.notify_all();
    }
    LOGI("WESTON_EVENT_LOOP_STARTED: running Wayland/libweston event loop with LinuxDroid backend");

    // 11. Run Wayland event loop (blocks until wl_display_terminate is called)
    wl_display_run(display_);
    LOGI("WESTON_EVENT_LOOP_STOPPED: Wayland/libweston event loop stopped");

    // 12. Teardown native state deterministically in reverse order of ownership
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

} // namespace gui
} // namespace linuxdroid
