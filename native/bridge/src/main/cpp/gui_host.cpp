#include "gui_host.h"

#include <wayland-server.h>
#include <libweston-16/libweston/libweston.h>
#include <libweston-16/libweston/weston-log.h>

#include <android/log.h>
#include <sys/eventfd.h>
#include <unistd.h>
#include <cerrno>
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
      init_success_(false) {}

GuiHost::~GuiHost() {
    stop();
}

bool GuiHost::start() {
    std::unique_lock<std::mutex> lock(lifecycle_mutex_);

    if (state_ == LifecycleState::RUNNING) {
        LOGI("GUI host start requested while already running");
        return true;
    }

    if (state_ == LifecycleState::STARTING) {
        init_cv_.wait(lock, [this] { return state_ != LifecycleState::STARTING; });
        return state_ == LifecycleState::RUNNING;
    }

    if (state_ == LifecycleState::STOPPING) {
        init_cv_.wait(lock, [this] { return state_ == LifecycleState::STOPPED; });
    }

    LOGI("GUI host starting");
    state_ = LifecycleState::STARTING;
    init_success_ = false;

    // Create wake eventfd to allow deterministic asynchronous interruption of the Wayland event loop
    wake_fd_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (wake_fd_ < 0) {
        LOGE("GUI host initialization failure (failed to create eventfd: %s)", strerror(errno));
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
        LOGI("GUI host started");
        return true;
    } else {
        LOGE("GUI host initialization failure");
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
        LOGI("GUI host stop requested while already stopped");
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

    LOGI("GUI host stopping");
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

    LOGI("GUI host stopped");
    return true;
}

bool GuiHost::isRunning() const {
    return state_ == LifecycleState::RUNNING;
}

LifecycleState GuiHost::getState() const {
    return state_.load(std::memory_order_relaxed);
}

void GuiHost::workerMain() {
    // 1. Create Wayland Server Display
    display_ = wl_display_create();
    if (display_ == nullptr) {
        LOGE("Failed to create Wayland display");
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    // 2. Register wake_fd_ with Wayland event loop for deterministic termination
    struct wl_event_loop* loop = wl_display_get_event_loop(display_);
    if (loop == nullptr) {
        LOGE("Failed to get Wayland event loop");
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    wake_source_ = wl_event_loop_add_fd(loop, wake_fd_, WL_EVENT_READABLE, handleWakeEvent, nullptr);
    if (wake_source_ == nullptr) {
        LOGE("Failed to add wake event source to Wayland event loop");
        wl_display_destroy(display_);
        display_ = nullptr;
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    // 3. Initialize minimal Weston log context and compositor runtime
    log_ctx_ = weston_log_ctx_create();
    if (log_ctx_ != nullptr) {
        compositor_ = weston_compositor_create(display_, log_ctx_, nullptr, nullptr);
    }

    if (compositor_ == nullptr) {
        LOGE("Failed to create libweston compositor");
        if (log_ctx_ != nullptr) {
            weston_log_ctx_destroy(log_ctx_);
            log_ctx_ = nullptr;
        }
        if (wake_source_ != nullptr) {
            wl_event_source_remove(wake_source_);
            wake_source_ = nullptr;
        }
        wl_display_destroy(display_);
        display_ = nullptr;

        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::STOPPED;
        init_cv_.notify_all();
        return;
    }

    // 4. Initialization successful: signal RUNNING to waiter
    {
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        state_ = LifecycleState::RUNNING;
        init_success_ = true;
        init_cv_.notify_all();
    }

    // 5. Run Wayland event loop (blocks until wl_display_terminate is called)
    wl_display_run(display_);

    // 6. Teardown native state deterministically in reverse order of ownership
    if (compositor_ != nullptr) {
        weston_compositor_destroy(compositor_);
        compositor_ = nullptr;
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
    }
}

} // namespace gui
} // namespace linuxdroid

