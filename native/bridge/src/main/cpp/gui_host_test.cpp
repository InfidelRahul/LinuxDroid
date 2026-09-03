#include "gui_host.h"
#include <cassert>
#include <iostream>

using namespace linuxdroid::gui;

int main() {
    std::cout << "[GuiHostTest] Starting native GuiHost lifecycle test..." << std::endl;
    GuiHost& host = GuiHost::getInstance();

    // Test 1 — Native host creation
    std::cout << "[GuiHostTest] Test 1: Native host creation..." << std::endl;
    bool start1 = host.start();
    assert(start1);
    assert(host.isRunning());
    assert(host.getState() == LifecycleState::RUNNING);
    std::cout << "[GuiHostTest] Test 1: PASS" << std::endl;

    // Test 2 — Duplicate start
    std::cout << "[GuiHostTest] Test 2: Duplicate start..." << std::endl;
    bool start2 = host.start();
    assert(start2);
    assert(host.isRunning());
    assert(host.getState() == LifecycleState::RUNNING);
    std::cout << "[GuiHostTest] Test 2: PASS" << std::endl;

    // Test 3 — Stop
    std::cout << "[GuiHostTest] Test 3: Stop..." << std::endl;
    bool stop1 = host.stop();
    assert(stop1);
    assert(!host.isRunning());
    assert(host.getState() == LifecycleState::STOPPED);
    std::cout << "[GuiHostTest] Test 3: PASS" << std::endl;

    // Test 4 — Duplicate stop
    std::cout << "[GuiHostTest] Test 4: Duplicate stop..." << std::endl;
    bool stop2 = host.stop();
    assert(stop2);
    assert(!host.isRunning());
    assert(host.getState() == LifecycleState::STOPPED);
    std::cout << "[GuiHostTest] Test 4: PASS" << std::endl;

    // Test 5 — Restart (start -> stop -> start -> stop)
    std::cout << "[GuiHostTest] Test 5: Restart cycle..." << std::endl;
    assert(host.start());
    assert(host.isRunning());
    assert(host.stop());
    assert(!host.isRunning());
    assert(host.start());
    assert(host.isRunning());
    assert(host.stop());
    assert(!host.isRunning());
    std::cout << "[GuiHostTest] Test 5: PASS" << std::endl;

    std::cout << "[GuiHostTest] ALL TESTS PASSED!" << std::endl;
    return 0;
}
