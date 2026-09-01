#include "weston_host.h"

#include <android/log.h>

#define TAG "LinuxDroid/WestonHost"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

WestonHost& WestonHost::getInstance() {
    static WestonHost instance;
    return instance;
}

WestonHost::WestonHost() = default;

WestonHost::~WestonHost() {
    stop();
}

bool WestonHost::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (running_) {
        LOGW("start() called while already running; ignoring.");
        return true;
    }
    if (startImpl()) {
        running_ = true;
        return true;
    }
    return false;
}

void WestonHost::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!running_) {
        // Tear down any partially-initialised state even if we never reached
        // the "running" state (e.g. start() failed partway).
        stopImpl();
        return;
    }
    stopImpl();
    running_ = false;
    LOGI("libweston compositor stopped.");
}

bool WestonHost::isRunning() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return running_;
}

} // namespace linuxdroid

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------
#ifdef LINUXDROID_HAS_LIBWESTON

#include <pthread.h>

#include <wayland-server.h>
#include <libweston/compositor.h>
#include <libweston/log.h>

// The LinuxDroid custom Android backend registers itself with a compositor.
// It is provided by linuxdroid_backend.c and is compiled into this library
// only when LINUXDROID_HAS_LIBWESTON is defined.
extern "C" int linuxdroid_backend_init(struct weston_compositor* compositor);

namespace linuxdroid {

namespace {

// Owned state for a single compositor lifetime. Created on start(), torn down
// on stop(). This avoids leaking or reusing a compositor across host cycles.
struct CompositorState {
    struct wl_display* display = nullptr;
    struct weston_compositor* compositor = nullptr;
    struct weston_log_context* log_ctx = nullptr;
    pthread_t thread = 0;
    bool thread_running = false;
};

// libweston's event loop runs here. wl_display_run blocks until
// wl_display_terminate() is called from stop().
void* eventLoopMain(void* arg) {
    auto* state = static_cast<CompositorState*>(arg);
    wl_display_run(state->display);
    return nullptr;
}

} // namespace

bool WestonHost::startImpl() {
    auto* state = new CompositorState();

    state->display = wl_display_create();
    if (state->display == nullptr) {
        LOGE("wl_display_create failed.");
        delete state;
        return false;
    }

    state->log_ctx = weston_log_ctx_create();
    if (state->log_ctx == nullptr) {
        LOGE("weston_log_ctx_create failed.");
        wl_display_destroy(state->display);
        delete state;
        return false;
    }

    // Weston 16 compositor creation. The final NULL is the testsuite data.
    state->compositor = weston_compositor_create(
        state->display, state->log_ctx, /*user_data=*/state, /*test_data=*/nullptr);
    if (state->compositor == nullptr) {
        LOGE("weston_compositor_create failed.");
        weston_log_ctx_destroy(state->log_ctx);
        wl_display_destroy(state->display);
        delete state;
        return false;
    }

    // Register the LinuxDroid custom Android backend. This establishes the
    // backend/output integration point only; it does not present any buffer or
    // render anything yet (that is a later phase).
    if (linuxdroid_backend_init(state->compositor) != 0) {
        LOGE("linuxdroid backend init failed.");
        weston_compositor_destroy(state->compositor);
        weston_log_ctx_destroy(state->log_ctx);
        wl_display_destroy(state->display);
        delete state;
        return false;
    }

    // Start libweston's event loop on a dedicated worker thread. This is purely
    // the Wayland event dispatch loop — it is not a rendering/frame thread.
    if (pthread_create(&state->thread, nullptr, eventLoopMain, state) != 0) {
        LOGE("pthread_create for weston event loop failed.");
        weston_compositor_destroy(state->compositor);
        weston_log_ctx_destroy(state->log_ctx);
        wl_display_destroy(state->display);
        delete state;
        return false;
    }
    state->thread_running = true;

    impl_ = state;
    LOGI("libweston 16.0.0 compositor initialised and running (linuxdroid backend).");
    return true;
}

void WestonHost::stopImpl() {
    auto* state = static_cast<CompositorState*>(impl_);
    if (state == nullptr) {
        return; // nothing to tear down
    }

    if (state->thread_running && state->thread != 0) {
        // Ask wl_display_run() to return so the worker thread exits.
        wl_display_terminate(state->display);
        pthread_join(state->thread, nullptr);
        state->thread_running = false;
    }

    if (state->compositor != nullptr) {
        weston_compositor_destroy(state->compositor);
        state->compositor = nullptr;
    }
    if (state->log_ctx != nullptr) {
        weston_log_ctx_destroy(state->log_ctx);
        state->log_ctx = nullptr;
    }
    if (state->display != nullptr) {
        wl_display_destroy(state->display);
        state->display = nullptr;
    }

    delete state;
    impl_ = nullptr;
    LOGI("libweston compositor tore down and native resources released.");
}

} // namespace linuxdroid

#else // !LINUXDROID_HAS_LIBWESTON

namespace linuxdroid {

bool WestonHost::startImpl() {
    LOGW("libweston is not built into this library (LINUXDROID_HAS_LIBWESTON unset). "
         "The compositor cannot start. Build the pinned libweston (native/weston/) "
         "and enable the integration to use the GUI compositor.");
    return false;
}

void WestonHost::stopImpl() {
    // Nothing to tear down when libweston is not compiled in.
}

} // namespace linuxdroid

#endif // LINUXDROID_HAS_LIBWESTON
