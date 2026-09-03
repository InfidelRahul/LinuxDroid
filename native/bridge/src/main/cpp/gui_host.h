#pragma once

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

struct wl_display;
struct wl_event_source;
struct weston_compositor;
struct weston_log_context;

namespace linuxdroid {
namespace gui {

/**
 * State model for the native GUI host.
 * Synchronized across Android UI, JNI, and the native GUI worker thread.
 */
enum class LifecycleState {
    STOPPED = 0,
    STARTING = 1,
    RUNNING = 2,
    STOPPING = 3
};

const char* lifecycleStateToString(LifecycleState state);

/**
 * Native GUI Host:
 * Owns the native GUI worker thread, Wayland display server, and minimal libweston compositor runtime.
 * Provides idempotent start/stop semantics and ensures the Android UI thread is never blocked.
 */
class GuiHost {
public:
    static GuiHost& getInstance();

    GuiHost();
    ~GuiHost();

    GuiHost(const GuiHost&) = delete;
    GuiHost& operator=(const GuiHost&) = delete;

    /**
     * Idempotently starts the native GUI host worker thread and initializes the Wayland/Weston runtime.
     * Blocks the calling thread until the host has either successfully reached RUNNING or failed.
     *
     * Returns true if RUNNING, false on initialization failure.
     */
    bool start();

    /**
     * Idempotently stops the native GUI host worker thread and deterministically tears down native resources.
     * Blocks until the worker thread has exited and native resources are destroyed.
     *
     * Returns true if STOPPED.
     */
    bool stop();

    /**
     * Returns true if the host is in RUNNING state.
     */
    bool isRunning() const;

    /**
     * Returns the current lifecycle state.
     */
    LifecycleState getState() const;

private:
    void workerMain();

    mutable std::mutex lifecycle_mutex_;
    std::condition_variable init_cv_;
    std::atomic<LifecycleState> state_{LifecycleState::STOPPED};

    std::thread worker_thread_;
    int wake_fd_ = -1;

    // Native Wayland / libweston runtime resources
    struct wl_display* display_ = nullptr;
    struct wl_event_source* wake_source_ = nullptr;
    struct weston_log_context* log_ctx_ = nullptr;
    struct weston_compositor* compositor_ = nullptr;

    bool init_success_ = false;
};

} // namespace gui
} // namespace linuxdroid

