#include "vsync_bridge.h"

#include <android/log.h>
#include <android/looper.h>
#include <android/choreographer.h>
#include <dlfcn.h>
#include <sys/eventfd.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cinttypes>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>

#define TAG "LinuxDroid/VsyncBridge"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

#define DEFAULT_VSYNC_PERIOD_NS 16666666LL // 60.00 Hz default

namespace {

// Typedefs for API 33+ VSync callback functions (resolved via dlsym for robustness)
typedef void (*PFN_AChoreographer_postVsyncCallback)(
    AChoreographer* choreographer,
    AChoreographer_vsyncCallback callback,
    void* data);

typedef int64_t (*PFN_AChoreographerFrameCallbackData_getFrameTimeNanos)(
    const AChoreographerFrameCallbackData* data);

typedef size_t (*PFN_AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex)(
    const AChoreographerFrameCallbackData* data);

typedef AVsyncId (*PFN_AChoreographerFrameCallbackData_getFrameTimelineVsyncId)(
    const AChoreographerFrameCallbackData* data, size_t index);

typedef int64_t (*PFN_AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos)(
    const AChoreographerFrameCallbackData* data, size_t index);

typedef int64_t (*PFN_AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos)(
    const AChoreographerFrameCallbackData* data, size_t index);

typedef void (*PFN_AChoreographer_registerRefreshRateCallback)(
    AChoreographer* choreographer,
    AChoreographer_refreshRateCallback callback,
    void* data);

typedef void (*PFN_AChoreographer_unregisterRefreshRateCallback)(
    AChoreographer* choreographer,
    AChoreographer_refreshRateCallback callback,
    void* data);

struct ChoreographerApi {
    PFN_AChoreographer_postVsyncCallback postVsyncCallback{nullptr};
    PFN_AChoreographerFrameCallbackData_getFrameTimeNanos getFrameTimeNanos{nullptr};
    PFN_AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex getPreferredIndex{nullptr};
    PFN_AChoreographerFrameCallbackData_getFrameTimelineVsyncId getVsyncId{nullptr};
    PFN_AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos getExpectedPresentationTimeNanos{nullptr};
    PFN_AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos getDeadlineNanos{nullptr};
    PFN_AChoreographer_registerRefreshRateCallback registerRefreshRateCallback{nullptr};
    PFN_AChoreographer_unregisterRefreshRateCallback unregisterRefreshRateCallback{nullptr};

    void init() {
        postVsyncCallback = reinterpret_cast<PFN_AChoreographer_postVsyncCallback>(
            dlsym(RTLD_DEFAULT, "AChoreographer_postVsyncCallback"));
        getFrameTimeNanos = reinterpret_cast<PFN_AChoreographerFrameCallbackData_getFrameTimeNanos>(
            dlsym(RTLD_DEFAULT, "AChoreographerFrameCallbackData_getFrameTimeNanos"));
        getPreferredIndex = reinterpret_cast<PFN_AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex>(
            dlsym(RTLD_DEFAULT, "AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex"));
        getVsyncId = reinterpret_cast<PFN_AChoreographerFrameCallbackData_getFrameTimelineVsyncId>(
            dlsym(RTLD_DEFAULT, "AChoreographerFrameCallbackData_getFrameTimelineVsyncId"));
        getExpectedPresentationTimeNanos = reinterpret_cast<PFN_AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos>(
            dlsym(RTLD_DEFAULT, "AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos"));
        getDeadlineNanos = reinterpret_cast<PFN_AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos>(
            dlsym(RTLD_DEFAULT, "AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos"));
        registerRefreshRateCallback = reinterpret_cast<PFN_AChoreographer_registerRefreshRateCallback>(
            dlsym(RTLD_DEFAULT, "AChoreographer_registerRefreshRateCallback"));
        unregisterRefreshRateCallback = reinterpret_cast<PFN_AChoreographer_unregisterRefreshRateCallback>(
            dlsym(RTLD_DEFAULT, "AChoreographer_unregisterRefreshRateCallback"));
    }
};

ChoreographerApi& getApi() {
    static ChoreographerApi api;
    static std::once_flag flag;
    std::call_once(flag, []() { api.init(); });
    return api;
}

} // anonymous namespace

struct linuxdroid_vsync_bridge {
    struct weston_compositor* compositor{nullptr};
    struct weston_output* output{nullptr};

    mutable std::mutex mutex;
    std::condition_variable init_cv;
    struct linuxdroid_vsync_timing timing{};

    int event_fd{-1};
    std::atomic<bool> running{false};
    std::atomic<bool> paused{false};
    std::atomic<bool> wake_requested{false};
    std::atomic<bool> callback_pending{false};

    std::thread looper_thread;
    ALooper* looper{nullptr};
    AChoreographer* choreographer{nullptr};

    linuxdroid_vsync_bridge(struct weston_compositor* ec, struct weston_output* out)
        : compositor(ec), output(out) {
        event_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        timing.state = LINUXDROID_TIMING_UNINITIALIZED;
        timing.vsync_period_ns = DEFAULT_VSYNC_PERIOD_NS;
        timing.last_vsync_timestamp_ns = 0;
        timing.expected_present_time_ns = 0;
        timing.deadline_ns = 0;
        timing.vsync_id = 0;
        timing.vsync_sequence = 0;
        std::memset(&timing.stats, 0, sizeof(timing.stats));
    }

    ~linuxdroid_vsync_bridge() {
        stop();
        if (event_fd >= 0) {
            close(event_fd);
            event_fd = -1;
        }
    }

    void handleVsyncPulse(int64_t frameTimeNanos,
                          int64_t expectedPresentNanos,
                          int64_t deadlineNanos,
                          int64_t vsyncId) {
        bool should_wake = false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (timing.state == LINUXDROID_TIMING_PAUSED) {
                return;
            }

            timing.last_vsync_timestamp_ns = frameTimeNanos;
            timing.expected_present_time_ns = expectedPresentNanos;
            timing.deadline_ns = deadlineNanos;
            timing.vsync_id = vsyncId;
            timing.vsync_sequence++;
            timing.stats.vsync_count++;

            // If a frame was rendered and submitted, Weston is awaiting presentation completion
            if (wake_requested.exchange(false, std::memory_order_acq_rel)) {
                should_wake = true;
            }
        }

        if (should_wake && event_fd >= 0) {
            uint64_t v = 1;
            write(event_fd, &v, sizeof(v));
        }
    }

    void handleRefreshRateChanged(int64_t vsyncPeriodNanos) {
        if (vsyncPeriodNanos <= 0) return;
        std::lock_guard<std::mutex> lock(mutex);
        timing.vsync_period_ns = vsyncPeriodNanos;
        LOGI("FRAME_TIMING state=ACTIVE refresh_period_ns=%" PRId64, vsyncPeriodNanos);
    }

    void looperMain() {
        looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
        if (!looper) {
            LOGE("FRAME_TIMING_INIT_FAILURE operation=ALooper_prepare reason=failed to create looper");
            std::lock_guard<std::mutex> lock(mutex);
            timing.state = LINUXDROID_TIMING_FAILED;
            init_cv.notify_all();
            return;
        }
        ALooper_acquire(looper);

        choreographer = AChoreographer_getInstance();
        if (!choreographer) {
            LOGW("FRAME_TIMING_INIT_FAILURE operation=AChoreographer_getInstance reason=null choreographer (host test or mock mode)");
            // In synthetic/mock mode (e.g. host unit tests), allow running without AChoreographer
            std::lock_guard<std::mutex> lock(mutex);
            timing.state = LINUXDROID_TIMING_ACTIVE;
            init_cv.notify_all();

            while (running.load(std::memory_order_relaxed)) {
                ALooper_pollOnce(50, nullptr, nullptr, nullptr);
            }
            ALooper_release(looper);
            looper = nullptr;
            return;
        }

        auto& api = getApi();
        if (api.registerRefreshRateCallback) {
            api.registerRefreshRateCallback(
                choreographer,
                [](int64_t vsyncPeriodNanos, void* data) {
                    auto* self = static_cast<linuxdroid_vsync_bridge*>(data);
                    if (self) self->handleRefreshRateChanged(vsyncPeriodNanos);
                },
                this
            );
        }

        {
            std::lock_guard<std::mutex> lock(mutex);
            timing.state = LINUXDROID_TIMING_ACTIVE;
            LOGI("FRAME_TIMING state=ACTIVE refresh_period_ns=%" PRId64, timing.vsync_period_ns);
            init_cv.notify_all();
        }

        // Post initial choreographer callback
        postNextChoreographerCallback();

        while (running.load(std::memory_order_relaxed)) {
            // Poll looper with 50ms timeout; AChoreographer callbacks run inside this poll
            ALooper_pollOnce(50, nullptr, nullptr, nullptr);

            // If resumed from pause or callback chain unblocked, post next callback
            if (!paused.load(std::memory_order_relaxed) && !callback_pending.load(std::memory_order_relaxed)) {
                postNextChoreographerCallback();
            }
        }

        if (api.unregisterRefreshRateCallback && choreographer) {
            api.unregisterRefreshRateCallback(
                choreographer,
                [](int64_t vsyncPeriodNanos, void* data) {
                    auto* self = static_cast<linuxdroid_vsync_bridge*>(data);
                    if (self) self->handleRefreshRateChanged(vsyncPeriodNanos);
                },
                this
            );
        }

        ALooper_release(looper);
        looper = nullptr;
        choreographer = nullptr;
    }

    void postNextChoreographerCallback() {
        if (!choreographer || !running.load(std::memory_order_relaxed) || paused.load(std::memory_order_relaxed)) {
            return;
        }

        if (callback_pending.exchange(true, std::memory_order_acq_rel)) {
            return; // Already pending
        }

        auto& api = getApi();
        if (api.postVsyncCallback) {
            api.postVsyncCallback(
                choreographer,
                [](const AChoreographerFrameCallbackData* data, void* ctx) {
                    auto* self = static_cast<linuxdroid_vsync_bridge*>(ctx);
                    if (!self) return;

                    self->callback_pending.store(false, std::memory_order_release);

                    auto& a = getApi();
                    int64_t frameTime = a.getFrameTimeNanos ? a.getFrameTimeNanos(data) : 0;
                    size_t prefIdx = a.getPreferredIndex ? a.getPreferredIndex(data) : 0;
                    int64_t expectedPresent = a.getExpectedPresentationTimeNanos ?
                        a.getExpectedPresentationTimeNanos(data, prefIdx) : frameTime;
                    int64_t deadline = a.getDeadlineNanos ?
                        a.getDeadlineNanos(data, prefIdx) : frameTime;
                    AVsyncId vsyncId = a.getVsyncId ?
                        a.getVsyncId(data, prefIdx) : 0;

                    self->handleVsyncPulse(frameTime, expectedPresent, deadline, vsyncId);
                    self->postNextChoreographerCallback();
                },
                this
            );
        } else {
            AChoreographer_postFrameCallback64(
                choreographer,
                [](int64_t frameTimeNanos, void* ctx) {
                    auto* self = static_cast<linuxdroid_vsync_bridge*>(ctx);
                    if (!self) return;

                    self->callback_pending.store(false, std::memory_order_release);

                    int64_t period = self->timing.vsync_period_ns;
                    self->handleVsyncPulse(frameTimeNanos, frameTimeNanos + period, frameTimeNanos + period, 0);
                    self->postNextChoreographerCallback();
                },
                this
            );
        }
    }

    void stop() {
        if (!running.exchange(false)) {
            return;
        }

        if (looper) {
            ALooper_wake(looper);
        }

        if (looper_thread.joinable()) {
            looper_thread.join();
        }

        std::lock_guard<std::mutex> lock(mutex);
        timing.state = LINUXDROID_TIMING_UNINITIALIZED;
    }
};

extern "C" {

linuxdroid_vsync_bridge_t*
linuxdroid_vsync_bridge_create(struct weston_compositor* compositor, struct weston_output* output)
{
    return new linuxdroid_vsync_bridge(compositor, output);
}

void
linuxdroid_vsync_bridge_destroy(linuxdroid_vsync_bridge_t* bridge)
{
    delete bridge;
}

int
linuxdroid_vsync_bridge_start(linuxdroid_vsync_bridge_t* bridge)
{
    if (!bridge) return -EINVAL;

    std::unique_lock<std::mutex> lock(bridge->mutex);
    if (bridge->running.load()) {
        return 0;
    }

    bridge->running = true;
    bridge->paused = false;
    bridge->wake_requested = false;

    bridge->looper_thread = std::thread(&linuxdroid_vsync_bridge::looperMain, bridge);

    bridge->init_cv.wait(lock, [bridge]() {
        return bridge->timing.state != LINUXDROID_TIMING_UNINITIALIZED;
    });

    return (bridge->timing.state == LINUXDROID_TIMING_ACTIVE) ? 0 : -EIO;
}

void
linuxdroid_vsync_bridge_stop(linuxdroid_vsync_bridge_t* bridge)
{
    if (bridge) {
        bridge->stop();
    }
}

void
linuxdroid_vsync_bridge_pause(linuxdroid_vsync_bridge_t* bridge)
{
    if (!bridge) return;
    std::lock_guard<std::mutex> lock(bridge->mutex);
    bridge->paused = true;
    bridge->timing.state = LINUXDROID_TIMING_PAUSED;
    LOGI("FRAME_TIMING state=PAUSED refresh_period_ns=%" PRId64, bridge->timing.vsync_period_ns);
}

void
linuxdroid_vsync_bridge_resume(linuxdroid_vsync_bridge_t* bridge)
{
    if (!bridge) return;
    {
        std::lock_guard<std::mutex> lock(bridge->mutex);
        bridge->paused = false;
        bridge->timing.state = LINUXDROID_TIMING_ACTIVE;
        LOGI("FRAME_TIMING state=ACTIVE (resumed) refresh_period_ns=%" PRId64, bridge->timing.vsync_period_ns);
    }
    if (bridge->choreographer && bridge->looper) {
        ALooper_wake(bridge->looper);
    }
}

int
linuxdroid_vsync_bridge_get_event_fd(const linuxdroid_vsync_bridge_t* bridge)
{
    return bridge ? bridge->event_fd : -1;
}

int
linuxdroid_vsync_bridge_get_timing(linuxdroid_vsync_bridge_t* bridge, struct linuxdroid_vsync_timing* out_timing)
{
    if (!bridge || !out_timing) return -EINVAL;
    std::lock_guard<std::mutex> lock(bridge->mutex);
    *out_timing = bridge->timing;
    return 0;
}

int
linuxdroid_vsync_bridge_get_last_timestamp(linuxdroid_vsync_bridge_t* bridge, struct timespec* out_ts)
{
    if (!bridge || !out_ts) return -EINVAL;
    std::lock_guard<std::mutex> lock(bridge->mutex);
    if (bridge->timing.last_vsync_timestamp_ns <= 0) {
        return -EAGAIN;
    }
    linuxdroid_nanos_to_timespec(bridge->timing.last_vsync_timestamp_ns, out_ts);
    return 0;
}

void
linuxdroid_vsync_bridge_request_wake(linuxdroid_vsync_bridge_t* bridge)
{
    if (!bridge) return;
    bridge->wake_requested.store(true, std::memory_order_release);
}

void
linuxdroid_vsync_bridge_record_render(linuxdroid_vsync_bridge_t* bridge, int64_t duration_ns, bool submitted)
{
    if (!bridge) return;
    std::lock_guard<std::mutex> lock(bridge->mutex);
    bridge->timing.stats.frames_rendered++;
    if (submitted) {
        bridge->timing.stats.frames_submitted++;
    }
    bridge->timing.stats.total_render_duration_ns += duration_ns;
    if (duration_ns > bridge->timing.stats.max_render_duration_ns) {
        bridge->timing.stats.max_render_duration_ns = duration_ns;
    }

    if (duration_ns > bridge->timing.vsync_period_ns) {
        bridge->timing.stats.missed_deadlines++;
    }

    // Sampled diagnostics output every 60 submitted frames
    if (bridge->timing.stats.frames_submitted > 0 && (bridge->timing.stats.frames_submitted % 60) == 0) {
        int64_t avg_render_ns = bridge->timing.stats.total_render_duration_ns / bridge->timing.stats.frames_rendered;
        LOGI("FRAME_TIMING_STATS vsync_count=%" PRIu64 " frames_rendered=%" PRIu64 " frames_submitted=%" PRIu64
             " missed_deadlines=%" PRIu64 " avg_render_ns=%" PRId64 " max_render_ns=%" PRId64,
             bridge->timing.stats.vsync_count,
             bridge->timing.stats.frames_rendered,
             bridge->timing.stats.frames_submitted,
             bridge->timing.stats.missed_deadlines,
             avg_render_ns,
             bridge->timing.stats.max_render_duration_ns);
    }
}

void
linuxdroid_vsync_bridge_notify_refresh_rate_changed(linuxdroid_vsync_bridge_t* bridge, int64_t vsync_period_ns)
{
    if (bridge) {
        bridge->handleRefreshRateChanged(vsync_period_ns);
    }
}

void
linuxdroid_vsync_bridge_inject_vsync(linuxdroid_vsync_bridge_t* bridge, int64_t frame_time_ns, int64_t vsync_period_ns)
{
    if (!bridge) return;
    if (vsync_period_ns > 0) {
        bridge->handleRefreshRateChanged(vsync_period_ns);
    }
    bridge->handleVsyncPulse(frame_time_ns,
                            frame_time_ns + bridge->timing.vsync_period_ns,
                            frame_time_ns + bridge->timing.vsync_period_ns,
                            0);
}

bool
linuxdroid_vsync_bridge_is_active(const linuxdroid_vsync_bridge_t* bridge)
{
    if (!bridge) return false;
    std::lock_guard<std::mutex> lock(bridge->mutex);
    return bridge->running.load(std::memory_order_relaxed) &&
           !bridge->paused.load(std::memory_order_relaxed) &&
           (bridge->timing.state == LINUXDROID_TIMING_ACTIVE);
}

} // extern "C"
