#include "gui_host.h"
#include "vsync_bridge.h"
#include "input_bridge.h"
#include "desktop_window_tracker.h"
#include "android_presentation.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <chrono>
#include <thread>
#include <vector>
#include <sys/wait.h>
#include <unistd.h>

#define TEST_ASSERT(cond, msg) \
    do { \
        if (!(cond)) { \
            fprintf(stderr, "[FAIL] %s:%d: Assertion '%s' failed: %s\n", __FILE__, __LINE__, #cond, msg); \
            return false; \
        } \
    } while (0)

#define RUN_TEST(fn) \
    do { \
        printf("--- Running %s ---\n", #fn); \
        if (fn()) { \
            printf("[PASS] %s\n\n", #fn); \
            passed++; \
        } else { \
            printf("[FAIL] %s\n\n", #fn); \
            failed++; \
        } \
    } while (0)

// Test 1: Input motion coalescing and bounded capacity
static bool test_input_coalescing_and_overflow() {
    auto& bridge = linuxdroid::InputBridge::getInstance();
    bridge.clear();

    TEST_ASSERT(bridge.getPendingEventCount() == 0, "Initial queue should be empty");

    // Push first mouse move
    bridge.sendMouseEvent(2 /* ACTION_MOVE */, 0, 100.0f, 100.0f, 0.0f, 0.0f);
    TEST_ASSERT(bridge.getPendingEventCount() == 1, "Expected 1 event in queue");

    // Push second mouse move: should coalesce into the same queue entry
    bridge.sendMouseEvent(2 /* ACTION_MOVE */, 0, 150.0f, 200.0f, 0.0f, 0.0f);
    TEST_ASSERT(bridge.getPendingEventCount() == 1, "Mouse move should coalesce into existing entry");

    // Pop and verify latest coalesced coordinates
    linuxdroid::NativeInputEvent evt;
    TEST_ASSERT(bridge.popEvent(&evt), "Should pop event");
    TEST_ASSERT(evt.type == linuxdroid::InputEventType::MOUSE_MOVE, "Expected MOUSE_MOVE");
    TEST_ASSERT(evt.x == 150.0f && evt.y == 200.0f, "Expected latest coalesced coordinates (150, 200)");
    TEST_ASSERT(bridge.getPendingEventCount() == 0, "Queue should now be empty");

    // Test Touch coalescing
    bridge.sendTouchEvent(2 /* ACTION_MOVE */, 0, 50.0f, 50.0f, 1.0f);
    bridge.sendTouchEvent(2 /* ACTION_MOVE */, 0, 75.0f, 80.0f, 1.0f);
    TEST_ASSERT(bridge.getPendingEventCount() == 1, "Touch move for same ID should coalesce");

    // Touch move for different pointer ID should NOT coalesce
    bridge.sendTouchEvent(2 /* ACTION_MOVE */, 1, 300.0f, 300.0f, 1.0f);
    TEST_ASSERT(bridge.getPendingEventCount() == 2, "Touch move for different pointer ID must not coalesce");

    bridge.clear();
    TEST_ASSERT(bridge.getPendingEventCount() == 0, "Queue should be cleared");

    // Test queue bound: push 600 key events
    for (int i = 0; i < 600; ++i) {
        bridge.sendKeyEvent(30 /* KEY_A */, true, 0, 'a');
    }
    TEST_ASSERT(bridge.getPendingEventCount() <= 512, "Queue size must not exceed MAX_QUEUE_SIZE (512)");
    TEST_ASSERT(bridge.getDroppedEventCount() > 0, "Dropped events counter should reflect overflow");

    bridge.clear();
    return true;
}

// Test 2: VSync Pause / Resume Recovery & State Transitions
static bool test_vsync_pause_resume_recovery() {
    auto* bridge = linuxdroid_vsync_bridge_create(nullptr, nullptr);
    TEST_ASSERT(bridge != nullptr, "Failed to create VSync bridge");

    int err = linuxdroid_vsync_bridge_start(bridge);
    TEST_ASSERT(err == 0, "VSync bridge should start cleanly");

    // Repeat pause and resume 5 times
    for (int i = 0; i < 5; ++i) {
        linuxdroid_vsync_bridge_pause(bridge);
        struct linuxdroid_vsync_timing timing{};
        linuxdroid_vsync_bridge_get_timing(bridge, &timing);
        TEST_ASSERT(timing.state == LINUXDROID_TIMING_PAUSED, "Timing state should be PAUSED");

        linuxdroid_vsync_bridge_resume(bridge);
        linuxdroid_vsync_bridge_get_timing(bridge, &timing);
        TEST_ASSERT(timing.state == LINUXDROID_TIMING_ACTIVE, "Timing state should resume to ACTIVE");
        TEST_ASSERT(linuxdroid_vsync_bridge_is_active(bridge), "Bridge is_active should return true");

        // Inject synthetic pulse to verify eventfd wakes after resume
        linuxdroid_vsync_bridge_request_wake(bridge);
        linuxdroid_vsync_bridge_inject_vsync(bridge, (i + 1) * 16666666LL, 16666666LL);

        int fd = linuxdroid_vsync_bridge_get_event_fd(bridge);
        TEST_ASSERT(fd >= 0, "Eventfd must be valid");
        uint64_t val = 0;
        ssize_t n = read(fd, &val, sizeof(val));
        TEST_ASSERT(n == sizeof(val) && val > 0, "Eventfd should receive wake event after resume");
    }

    linuxdroid_vsync_bridge_stop(bridge);
    linuxdroid_vsync_bridge_destroy(bridge);
    return true;
}

// Test 3: Desktop Window Tracker & Thread Safety
static bool test_window_tracker_thread_safety() {
    auto& tracker = linuxdroid::DesktopWindowTracker::getInstance();
    tracker.clear();

    TEST_ASSERT(tracker.getWindowCount() == 0, "Tracker should be empty initially");

    // Concurrently register, activate, and unregister windows across 4 threads
    std::vector<std::thread> workers;
    for (int t = 0; t < 4; ++t) {
        workers.emplace_back([t, &tracker]() {
            for (int i = 0; i < 50; ++i) {
                uint64_t id = static_cast<uint64_t>(t * 1000 + i);
                tracker.registerWindow(id, "app_" + std::to_string(id), "Title " + std::to_string(id), nullptr);
                tracker.setWindowActive(id, true);
                linuxdroid::DesktopWindowEntry entry;
                bool found = tracker.getWindow(id, &entry);
                assert(found);
                tracker.unregisterWindow(id);
            }
        });
    }

    for (auto& w : workers) {
        w.join();
    }

    tracker.clear();
    TEST_ASSERT(tracker.getWindowCount() == 0, "Tracker should be clean after test");
    return true;
}

// Test 4: Zombie Process Reaping
static bool test_zombie_process_reaping() {
    pid_t pid = fork();
    TEST_ASSERT(pid >= 0, "Fork should succeed");

    if (pid == 0) {
        // Child exits immediately
        _exit(42);
    }

    // Wait a moment for child to terminate
    usleep(20000);

    // Reap using non-blocking waitpid
    int status = 0;
    pid_t reaped = waitpid(pid, &status, WNOHANG);
    TEST_ASSERT(reaped == pid, "Child process should be reaped without blocking");
    TEST_ASSERT(WIFEXITED(status) && WEXITSTATUS(status) == 42, "Child exit status should match 42");

    // Second waitpid should return -1 or 0 (no lingering zombie)
    pid_t check = waitpid(pid, &status, WNOHANG);
    TEST_ASSERT(check <= 0, "No duplicate zombie should exist");
    return true;
}

// Test 5: Nanosecond to Timespec Arithmetic
static bool test_timestamp_conversions() {
    int64_t nanos = 1234567890123456789LL;
    struct timespec ts{};
    linuxdroid_nanos_to_timespec(nanos, &ts);

    TEST_ASSERT(ts.tv_sec == 1234567890LL, "tv_sec conversion mismatch");
    TEST_ASSERT(ts.tv_nsec == 123456789LL, "tv_nsec conversion mismatch");

    int64_t roundtrip = linuxdroid_timespec_to_nanos(&ts);
    TEST_ASSERT(roundtrip == nanos, "Roundtrip nanosecond conversion mismatch");
    return true;
}

int main() {
    printf("=====================================================\n");
    printf("LinuxDroid Phase 10: GUI Hardening Test Suite\n");
    printf("=====================================================\n\n");

    int passed = 0;
    int failed = 0;

    RUN_TEST(test_input_coalescing_and_overflow);
    RUN_TEST(test_vsync_pause_resume_recovery);
    RUN_TEST(test_window_tracker_thread_safety);
    RUN_TEST(test_zombie_process_reaping);
    RUN_TEST(test_timestamp_conversions);

    printf("=====================================================\n");
    printf("Hardening Test Suite Results: %d passed, %d failed\n", passed, failed);
    printf("=====================================================\n");

    return (failed == 0) ? 0 : 1;
}
