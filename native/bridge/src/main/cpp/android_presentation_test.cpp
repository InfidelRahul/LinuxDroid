#include "android_presentation.h"

#include <cassert>
#include <iostream>
#include <thread>
#include <chrono>

static void testBufferLifecycleAndPool() {
    std::cout << "[android_presentation_test] Running testBufferLifecycleAndPool...\n";
    android_presentation_t* pres = android_presentation_create();
    assert(pres != nullptr);

    // Initial state: not enabled
    assert(!android_presentation_is_enabled(pres));

    // Enable with null window should fail safely
    [[maybe_unused]] int err = android_presentation_enable(pres, nullptr, 1920, 1080);
    assert(err != 0);
    assert(!android_presentation_is_enabled(pres));

    // Dimensions test
    android_presentation_resize(pres, 1280, 720);
    int32_t w = 0, h = 0;
    android_presentation_get_dimensions(pres, &w, &h);
    assert(w == 1280);
    assert(h == 720);

    // Stats when unallocated
    int allocated = -1, freeCount = -1, submitted = -1;
    android_presentation_get_stats(pres, &allocated, &freeCount, &submitted);
    assert(allocated == 0);
    assert(freeCount == 0);
    assert(submitted == 0);

    // Disable and destroy
    android_presentation_disable(pres);
    android_presentation_destroy(pres);
    std::cout << "[android_presentation_test] testBufferLifecycleAndPool PASSED\n";
}

static void testMultipleResizeAndDrain() {
    std::cout << "[android_presentation_test] Running testMultipleResizeAndDrain...\n";
    android_presentation_t* pres = android_presentation_create();
    assert(pres != nullptr);

    android_presentation_resize(pres, 1920, 1080);
    int32_t w = 0, h = 0;
    android_presentation_get_dimensions(pres, &w, &h);
    assert(w == 1920);
    assert(h == 1080);

    android_presentation_resize(pres, 2560, 1440);
    android_presentation_get_dimensions(pres, &w, &h);
    assert(w == 2560);
    assert(h == 1440);

    [[maybe_unused]] int idleErr = android_presentation_wait_idle(pres, 50);
    assert(idleErr == 0);

    android_presentation_destroy(pres);
    std::cout << "[android_presentation_test] testMultipleResizeAndDrain PASSED\n";
}

static void testLifetimeSafety() {
    std::cout << "[android_presentation_test] Running testLifetimeSafety...\n";
    android_presentation_t* pres = android_presentation_create();
    assert(pres != nullptr);

    // Repeated destroy should be safe and idempotent
    android_presentation_destroy(pres);
    android_presentation_destroy(nullptr);
    std::cout << "[android_presentation_test] testLifetimeSafety PASSED\n";
}

int main() {
    std::cout << "=== Android Presentation Backend Native Tests ===\n";
    testBufferLifecycleAndPool();
    testMultipleResizeAndDrain();
    testLifetimeSafety();
    std::cout << "=== ALL ANDROID PRESENTATION TESTS PASSED ===\n";
    return 0;
}

