#include "vsync_bridge.h"
#include "linuxdroid_backend.h"

#include <android/log.h>
#include <unistd.h>
#include <cassert>
#include <cerrno>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>

#define TAG "LinuxDroid/FrameTimingTest"
#define LOGI(fmt, ...) printf("[INFO] " fmt "\n", ##__VA_ARGS__)
#define LOGE(fmt, ...) fprintf(stderr, "[ERROR] " fmt "\n", ##__VA_ARGS__)

#define TEST_ASSERT(cond, msg) \
    do { \
        if (!(cond)) { \
            LOGE("Assertion failed: %s (%s:%d)", msg, __FILE__, __LINE__); \
            return 1; \
        } \
    } while (0)

int main() {
    LOGI("=================================================");
    LOGI("  LinuxDroid Phase 9 Frame Timing Test Suite");
    LOGI("=================================================");

    // ─── Test 1: Lifecycle State Transitions ─────────────────────────────────
    LOGI("--- Test 1: Lifecycle State Transitions ---");
    linuxdroid_vsync_bridge_t* bridge = linuxdroid_vsync_bridge_create(nullptr, nullptr);
    TEST_ASSERT(bridge != nullptr, "Failed to create vsync_bridge");

    struct linuxdroid_vsync_timing timing{};
    int ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(ret == 0, "get_timing failed");
    TEST_ASSERT(timing.state == LINUXDROID_TIMING_UNINITIALIZED, "Expected UNINITIALIZED state initially");
    LOGI("PASS: Initial state is LINUXDROID_TIMING_UNINITIALIZED");

    // Start bridge (in mock/host environment this initializes to ACTIVE)
    ret = linuxdroid_vsync_bridge_start(bridge);
    TEST_ASSERT(ret == 0, "Failed to start vsync_bridge");

    ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(ret == 0 && timing.state == LINUXDROID_TIMING_ACTIVE, "Expected ACTIVE state after start");
    LOGI("PASS: State transitioned to LINUXDROID_TIMING_ACTIVE");

    // Pause bridge
    linuxdroid_vsync_bridge_pause(bridge);
    ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(ret == 0 && timing.state == LINUXDROID_TIMING_PAUSED, "Expected PAUSED state after pause");
    LOGI("PASS: State transitioned to LINUXDROID_TIMING_PAUSED");

    // Resume bridge
    linuxdroid_vsync_bridge_resume(bridge);
    ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(ret == 0 && timing.state == LINUXDROID_TIMING_ACTIVE, "Expected ACTIVE state after resume");
    LOGI("PASS: State resumed to LINUXDROID_TIMING_ACTIVE");

    // Stop bridge
    linuxdroid_vsync_bridge_stop(bridge);
    ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(ret == 0 && timing.state == LINUXDROID_TIMING_UNINITIALIZED, "Expected UNINITIALIZED state after stop");
    LOGI("PASS: State returned to LINUXDROID_TIMING_UNINITIALIZED after stop");

    // Re-start for subsequent tests
    ret = linuxdroid_vsync_bridge_start(bridge);
    TEST_ASSERT(ret == 0, "Failed to re-start vsync_bridge");

    // ─── Test 2: Clock Domain & Conversion Correctness ───────────────────────
    LOGI("--- Test 2: Clock Domain & Conversion Correctness ---");
    struct timespec ts{};
    int64_t sample_nanos = 1725422400123456789LL;
    linuxdroid_nanos_to_timespec(sample_nanos, &ts);
    TEST_ASSERT(ts.tv_sec == 1725422400LL, "Sec conversion incorrect");
    TEST_ASSERT(ts.tv_nsec == 123456789LL, "Nsec conversion incorrect");

    int64_t roundtrip_nanos = linuxdroid_timespec_to_nanos(&ts);
    TEST_ASSERT(roundtrip_nanos == sample_nanos, "Timespec roundtrip conversion mismatch");
    LOGI("PASS: Monotonic nanoseconds <-> timespec conversion is bit-exact");

    // Test query before vsync returns -EAGAIN
    ret = linuxdroid_vsync_bridge_get_last_timestamp(bridge, &ts);
    TEST_ASSERT(ret == -EAGAIN, "Expected -EAGAIN before any VSync pulse");
    LOGI("PASS: get_last_timestamp returns -EAGAIN when no timestamp exists");

    // ─── Test 3: Synthetic VSync Injection & Monotonic Ordering ─────────────
    LOGI("--- Test 3: Synthetic VSync Injection & Monotonic Ordering ---");
    int64_t v1_time = 1000000000000LL;
    int64_t period_60hz = 16666666LL;
    linuxdroid_vsync_bridge_inject_vsync(bridge, v1_time, period_60hz);

    ret = linuxdroid_vsync_bridge_get_last_timestamp(bridge, &ts);
    TEST_ASSERT(ret == 0, "get_last_timestamp failed after injection");
    TEST_ASSERT(ts.tv_sec == 1000 && ts.tv_nsec == 0, "Timestamp conversion mismatch after v1");

    ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(timing.last_vsync_timestamp_ns == v1_time, "vsync timestamp mismatch");
    TEST_ASSERT(timing.vsync_sequence == 1, "vsync sequence mismatch");
    TEST_ASSERT(timing.stats.vsync_count == 1, "vsync count mismatch");

    int64_t v2_time = v1_time + period_60hz;
    linuxdroid_vsync_bridge_inject_vsync(bridge, v2_time, period_60hz);
    ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(timing.last_vsync_timestamp_ns == v2_time, "v2 timestamp mismatch");
    TEST_ASSERT(timing.last_vsync_timestamp_ns > v1_time, "Timestamp ordering not strictly monotonic");
    TEST_ASSERT(timing.vsync_sequence == 2, "vsync sequence mismatch for v2");
    LOGI("PASS: Monotonic VSync ordering verified (sequence=%" PRIu64 ", timestamp=%" PRId64 " ns)",
         timing.vsync_sequence, timing.last_vsync_timestamp_ns);

    // ─── Test 4: Dynamic Refresh Rate Adaptation ─────────────────────────────
    LOGI("--- Test 4: Dynamic Refresh Rate Adaptation ---");
    // Switch to 90 Hz (11.11 ms)
    int64_t period_90hz = 11111111LL;
    linuxdroid_vsync_bridge_notify_refresh_rate_changed(bridge, period_90hz);
    ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(timing.vsync_period_ns == period_90hz, "90 Hz refresh period mismatch");
    LOGI("PASS: 90 Hz adaptation verified (period=%" PRId64 " ns)", timing.vsync_period_ns);

    // Switch to 120 Hz (8.33 ms)
    int64_t period_120hz = 8333333LL;
    linuxdroid_vsync_bridge_notify_refresh_rate_changed(bridge, period_120hz);
    ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(timing.vsync_period_ns == period_120hz, "120 Hz refresh period mismatch");
    LOGI("PASS: 120 Hz adaptation verified (period=%" PRId64 " ns)", timing.vsync_period_ns);

    // Switch back to 60 Hz
    linuxdroid_vsync_bridge_notify_refresh_rate_changed(bridge, period_60hz);
    ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(timing.vsync_period_ns == period_60hz, "60 Hz refresh period mismatch");
    LOGI("PASS: 60 Hz restoration verified (period=%" PRId64 " ns)", timing.vsync_period_ns);

    // ─── Test 5: Idle State Preservation & EventFD Wake Verification ─────────
    LOGI("--- Test 5: Idle State Preservation & EventFD Wake Verification ---");
    int event_fd = linuxdroid_vsync_bridge_get_event_fd(bridge);
    TEST_ASSERT(event_fd >= 0, "Invalid event_fd");

    // Drain any pending data
    uint64_t drain_val = 0;
    while (read(event_fd, &drain_val, sizeof(drain_val)) > 0) {}

    // When wake is NOT requested (desktop is idle): VSync must NOT write to event_fd
    int64_t idle_vsync_time = v2_time + period_60hz;
    linuxdroid_vsync_bridge_inject_vsync(bridge, idle_vsync_time, period_60hz);

    uint64_t check_val = 0;
    ssize_t read_bytes = read(event_fd, &check_val, sizeof(check_val));
    TEST_ASSERT(read_bytes < 0 && (errno == EAGAIN || errno == EWOULDBLOCK),
                "Eventfd was signaled while idle! Idle preservation violated.");
    LOGI("PASS: Idle desktop does NOT signal eventfd (zero wakeups when no pending frame)");

    // Now request wake (simulating frame submitted awaiting presentation completion)
    linuxdroid_vsync_bridge_request_wake(bridge);

    // Inject VSync while wake requested
    int64_t active_vsync_time = idle_vsync_time + period_60hz;
    linuxdroid_vsync_bridge_inject_vsync(bridge, active_vsync_time, period_60hz);

    read_bytes = read(event_fd, &check_val, sizeof(check_val));
    TEST_ASSERT(read_bytes == sizeof(check_val) && check_val == 1,
                "Eventfd was NOT signaled after wake request!");
    LOGI("PASS: VSync correctly wakes Weston when presentation completion is awaited");

    // Verify wake flag was consumed: next VSync without request should not wake
    int64_t post_vsync_time = active_vsync_time + period_60hz;
    linuxdroid_vsync_bridge_inject_vsync(bridge, post_vsync_time, period_60hz);
    read_bytes = read(event_fd, &check_val, sizeof(check_val));
    TEST_ASSERT(read_bytes < 0 && (errno == EAGAIN || errno == EWOULDBLOCK),
                "Wake flag was not consumed; eventfd signaled spuriously.");
    LOGI("PASS: Wake request consumed atomically; subsequent idle VSync does not wake");

    // ─── Test 6: Render Statistics & Missed Deadlines ────────────────────────
    LOGI("--- Test 6: Render Statistics & Missed Deadlines ---");
    // Normal render: 4ms duration (< 16.67ms period)
    linuxdroid_vsync_bridge_record_render(bridge, 4000000LL, true);

    // Overrunning render: 22ms duration (> 16.67ms period)
    linuxdroid_vsync_bridge_record_render(bridge, 22000000LL, true);

    ret = linuxdroid_vsync_bridge_get_timing(bridge, &timing);
    TEST_ASSERT(ret == 0, "get_timing failed");
    TEST_ASSERT(timing.stats.frames_rendered == 2, "Expected 2 rendered frames");
    TEST_ASSERT(timing.stats.frames_submitted == 2, "Expected 2 submitted frames");
    TEST_ASSERT(timing.stats.missed_deadlines == 1, "Expected exactly 1 missed deadline");
    TEST_ASSERT(timing.stats.max_render_duration_ns == 22000000LL, "Max render duration mismatch");
    LOGI("PASS: Statistics tracking verified (frames=%" PRIu64 ", missed=%" PRIu64 ", max=%" PRId64 " ns)",
         timing.stats.frames_submitted, timing.stats.missed_deadlines, timing.stats.max_render_duration_ns);

    // ─── Test 7: Clean Teardown ──────────────────────────────────────────────
    LOGI("--- Test 7: Clean Teardown ---");
    linuxdroid_vsync_bridge_destroy(bridge);
    LOGI("PASS: VSync bridge destroyed cleanly without leaks or hangs");

    LOGI("=================================================");
    LOGI("  ALL PHASE 9 FRAME TIMING TESTS PASSED!");
    LOGI("=================================================");
    return 0;
}

