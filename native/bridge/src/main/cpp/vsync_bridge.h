#ifndef LINUXDROID_VSYNC_BRIDGE_H
#define LINUXDROID_VSYNC_BRIDGE_H

#include <stdint.h>
#include <stdbool.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

struct weston_compositor;
struct weston_output;

/**
 * State machine for display timing subsystem.
 */
enum linuxdroid_timing_state {
    LINUXDROID_TIMING_UNINITIALIZED = 0,
    LINUXDROID_TIMING_ACTIVE = 1,
    LINUXDROID_TIMING_PAUSED = 2,
    LINUXDROID_TIMING_FAILED = 3,
};

/**
 * Minimal timing statistics for diagnostics and regression verification.
 */
struct linuxdroid_vsync_timing_stats {
    uint64_t vsync_count;
    uint64_t repaints_scheduled;
    uint64_t frames_rendered;
    uint64_t frames_submitted;
    uint64_t missed_deadlines;
    int64_t total_render_duration_ns;
    int64_t max_render_duration_ns;
};

/**
 * Core frame timing and VSync state model.
 * All timestamps are in nanoseconds using the CLOCK_MONOTONIC clock domain.
 */
struct linuxdroid_vsync_timing {
    enum linuxdroid_timing_state state;
    int64_t vsync_period_ns;           // e.g. 16666666 for 60Hz, 11111111 for 90Hz, 8333333 for 120Hz
    int64_t last_vsync_timestamp_ns;   // Nanoseconds in CLOCK_MONOTONIC
    int64_t expected_present_time_ns;  // Nanoseconds in CLOCK_MONOTONIC
    int64_t deadline_ns;               // Nanoseconds in CLOCK_MONOTONIC
    int64_t vsync_id;                  // Android AVsyncId
    uint64_t vsync_sequence;
    struct linuxdroid_vsync_timing_stats stats;
};

typedef struct linuxdroid_vsync_bridge linuxdroid_vsync_bridge_t;

/**
 * Creates a new VSync timing bridge associated with the given compositor and output.
 */
linuxdroid_vsync_bridge_t*
linuxdroid_vsync_bridge_create(struct weston_compositor* compositor, struct weston_output* output);

/**
 * Destroys the VSync timing bridge and releases all resources.
 */
void
linuxdroid_vsync_bridge_destroy(linuxdroid_vsync_bridge_t* bridge);

/**
 * Starts the VSync looper thread and registers Android Choreographer callbacks.
 * Transitions state from UNINITIALIZED to ACTIVE.
 *
 * @return 0 on success, negative errno on failure.
 */
int
linuxdroid_vsync_bridge_start(linuxdroid_vsync_bridge_t* bridge);

/**
 * Stops the VSync looper thread and unregisters callbacks.
 */
void
linuxdroid_vsync_bridge_stop(linuxdroid_vsync_bridge_t* bridge);

/**
 * Pauses VSync callbacks (e.g. when display is backgrounded or detached).
 */
void
linuxdroid_vsync_bridge_pause(linuxdroid_vsync_bridge_t* bridge);

/**
 * Resumes VSync callbacks when display becomes active again.
 */
void
linuxdroid_vsync_bridge_resume(linuxdroid_vsync_bridge_t* bridge);

/**
 * Returns the eventfd file descriptor used to wake Weston's event loop on VSync events.
 */
int
linuxdroid_vsync_bridge_get_event_fd(const linuxdroid_vsync_bridge_t* bridge);

/**
 * Atomically retrieves a snapshot of the current timing state.
 */
int
linuxdroid_vsync_bridge_get_timing(linuxdroid_vsync_bridge_t* bridge, struct linuxdroid_vsync_timing* out_timing);

/**
 * Returns the latest authoritative monotonic VSync timestamp as a struct timespec.
 * Returns -EAGAIN if no valid VSync has been received yet (TIMING_UNINITIALIZED).
 */
int
linuxdroid_vsync_bridge_get_last_timestamp(linuxdroid_vsync_bridge_t* bridge, struct timespec* out_ts);

/**
 * Requests that the timing bridge wake the Weston compositor event loop on the next VSync pulse.
 * Call this when a frame has been rendered and submitted (REPAINT_AWAITING_COMPLETION).
 */
void
linuxdroid_vsync_bridge_request_wake(linuxdroid_vsync_bridge_t* bridge);

/**
 * Records render duration and submission in timing statistics.
 */
void
linuxdroid_vsync_bridge_record_render(linuxdroid_vsync_bridge_t* bridge, int64_t duration_ns, bool submitted);

/**
 * Notifies the bridge of a display refresh rate change.
 */
void
linuxdroid_vsync_bridge_notify_refresh_rate_changed(linuxdroid_vsync_bridge_t* bridge, int64_t vsync_period_ns);

/**
 * Injects a synthetic VSync event for deterministic host/unit testing.
 */
void
linuxdroid_vsync_bridge_inject_vsync(linuxdroid_vsync_bridge_t* bridge, int64_t frame_time_ns, int64_t vsync_period_ns);

/**
 * Converts nanoseconds in CLOCK_MONOTONIC to struct timespec.
 */
static inline void
linuxdroid_nanos_to_timespec(int64_t nanos, struct timespec* out_ts)
{
    if (!out_ts) return;
    if (nanos < 0) nanos = 0;
    out_ts->tv_sec = (time_t)(nanos / 1000000000LL);
    out_ts->tv_nsec = (long)(nanos % 1000000000LL);
}

/**
 * Converts struct timespec to nanoseconds in CLOCK_MONOTONIC.
 */
static inline int64_t
linuxdroid_timespec_to_nanos(const struct timespec* ts)
{
    if (!ts) return 0;
    return ((int64_t)ts->tv_sec * 1000000000LL) + (int64_t)ts->tv_nsec;
}

#ifdef __cplusplus
}
#endif

#endif /* LINUXDROID_VSYNC_BRIDGE_H */

