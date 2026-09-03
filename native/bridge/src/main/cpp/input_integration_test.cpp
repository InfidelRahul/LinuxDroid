#include "input_translator.h"
#include "input_bridge.h"
#include "linuxdroid_backend.h"
#include "gui_host.h"

#include <wayland-server.h>
#include <libweston/libweston.h>
#include <libweston/weston-log.h>
#include <android/keycodes.h>
#include <linux/input-event-codes.h>

#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>

using namespace linuxdroid;

static void test_keycode_translation() {
    printf("[RUN] test_keycode_translation\n");

    // Letters
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_A) == KEY_A);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_Z) == KEY_Z);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_M) == KEY_M);

    // Digits
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_0) == KEY_0);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_9) == KEY_9);

    // Navigation & editing
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_ENTER) == KEY_ENTER);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_SPACE) == KEY_SPACE);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_DEL) == KEY_BACKSPACE);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_TAB) == KEY_TAB);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_ESCAPE) == KEY_ESC);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_DPAD_UP) == KEY_UP);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_DPAD_DOWN) == KEY_DOWN);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_DPAD_LEFT) == KEY_LEFT);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_DPAD_RIGHT) == KEY_RIGHT);

    // Modifiers
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_SHIFT_LEFT) == KEY_LEFTSHIFT);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_CTRL_LEFT) == KEY_LEFTCTRL);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_ALT_LEFT) == KEY_LEFTALT);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_CAPS_LOCK) == KEY_CAPSLOCK);

    // Symbols
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_MINUS) == KEY_MINUS);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_EQUALS) == KEY_EQUAL);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_SLASH) == KEY_SLASH);

    // Unknown keycode
    assert(InputTranslator::androidKeycodeToLinux(99999) == KEY_RESERVED);
    assert(InputTranslator::androidKeycodeToLinux(-1) == KEY_RESERVED);

    printf("[PASS] test_keycode_translation\n");
}

static void test_button_and_coordinate_translation() {
    printf("[RUN] test_button_and_coordinate_translation\n");

    // Mouse buttons
    assert(InputTranslator::androidButtonToLinux(1) == BTN_LEFT);
    assert(InputTranslator::androidButtonToLinux(2) == BTN_RIGHT);
    assert(InputTranslator::androidButtonToLinux(4) == BTN_MIDDLE);
    assert(InputTranslator::androidButtonToLinux(8) == BTN_SIDE);
    assert(InputTranslator::androidButtonToLinux(16) == BTN_EXTRA);

    // Coordinate clamping
    assert(InputTranslator::clampCoordinate(100.0f, 1920) == 100.0);
    assert(InputTranslator::clampCoordinate(-50.0f, 1920) == 0.0);
    assert(InputTranslator::clampCoordinate(2500.0f, 1920) == 1920.0);
    assert(InputTranslator::clampCoordinate(NAN, 1920) == 0.0);
    assert(InputTranslator::clampCoordinate(INFINITY, 1920) == 0.0);
    assert(InputTranslator::clampCoordinate(-INFINITY, 1920) == 0.0);

    // Scroll axis inversion
    assert(InputTranslator::translateScrollAxis(1.0f) < 0.0); // Forward scroll becomes negative
    assert(InputTranslator::translateScrollAxis(-1.0f) > 0.0);

    printf("[PASS] test_button_and_coordinate_translation\n");
}

static void test_input_bridge_queue_and_overflow() {
    printf("[RUN] test_input_bridge_queue_and_overflow\n");

    auto& bridge = InputBridge::getInstance();
    bridge.clear();
    assert(bridge.getPendingEventCount() == 0);

    // Push touch down
    bridge.sendTouchEvent(0, 0, 100.0f, 200.0f, 1.0f);
    assert(bridge.getPendingEventCount() == 1);

    // Push mouse move
    bridge.sendMouseEvent(2, 0, 105.0f, 205.0f, 0.0f, 0.0f);
    assert(bridge.getPendingEventCount() == 2);

    // Push key down
    bridge.sendKeyEvent(AKEYCODE_A, true, 0, 'a');
    assert(bridge.getPendingEventCount() == 3);

    // Pop and verify FIFO order
    NativeInputEvent e1, e2, e3;
    assert(bridge.popEvent(&e1));
    assert(e1.type == InputEventType::TOUCH_DOWN);
    assert(e1.x == 100.0f && e1.y == 200.0f);

    assert(bridge.popEvent(&e2));
    assert(e2.type == InputEventType::MOUSE_MOVE);

    assert(bridge.popEvent(&e3));
    assert(e3.type == InputEventType::KEY_PRESS);
    assert(e3.keyCode == AKEYCODE_A);

    assert(bridge.getPendingEventCount() == 0);

    // Push over MAX_QUEUE_SIZE (512) motion events to verify bounded behavior
    for (int i = 0; i < 600; ++i) {
        bridge.sendTouchEvent(2, 0, static_cast<float>(i), static_cast<float>(i), 1.0f);
    }
    assert(bridge.getPendingEventCount() <= 512);
    assert(bridge.getDroppedEventCount() > 0);

    bridge.clear();
    assert(bridge.getPendingEventCount() == 0);

    printf("[PASS] test_input_bridge_queue_and_overflow\n");
}

static void test_seat_and_backend_lifecycle() {
    printf("[RUN] test_seat_and_backend_lifecycle\n");

    struct wl_display *display = wl_display_create();
    assert(display != nullptr);

    struct weston_log_context *log_ctx = weston_log_ctx_create();
    assert(log_ctx != nullptr);

    struct weston_compositor *compositor = weston_compositor_create(display, log_ctx, nullptr, nullptr);
    assert(compositor != nullptr);

    struct linuxdroid_backend_config config = {
        .refresh_mhz = 60000
    };
    struct linuxdroid_backend *backend = linuxdroid_backend_create(compositor, &config);
    assert(backend != nullptr);

    // Verify seat is initialized
    struct weston_seat *seat = linuxdroid_backend_get_seat(backend);
    assert(seat != nullptr);
    assert(seat->pointer_state != nullptr);
    assert(seat->keyboard_state != nullptr);
    assert(seat->touch_state != nullptr);

    // Verify touch device is created
    struct weston_touch_device *touch_dev = linuxdroid_backend_get_touch_device(backend);
    assert(touch_dev != nullptr);

    // Verify input reset is callable and idempotent
    linuxdroid_backend_reset_input(backend);
    linuxdroid_backend_reset_input(backend);

    // Destroy compositor (destroys backend and seat)
    weston_compositor_destroy(compositor);
    weston_log_ctx_destroy(log_ctx);
    wl_display_destroy(display);

    printf("[PASS] test_seat_and_backend_lifecycle\n");
}

static void test_synthetic_input_dispatch() {
    printf("[RUN] test_synthetic_input_dispatch\n");

    struct wl_display *display = wl_display_create();
    assert(display != nullptr);

    struct weston_log_context *log_ctx = weston_log_ctx_create();
    assert(log_ctx != nullptr);

    struct weston_compositor *compositor = weston_compositor_create(display, log_ctx, nullptr, nullptr);
    assert(compositor != nullptr);

    struct linuxdroid_backend_config config = { .refresh_mhz = 60000 };
    struct linuxdroid_backend *backend = linuxdroid_backend_create(compositor, &config);
    assert(backend != nullptr);

    struct weston_seat *seat = linuxdroid_backend_get_seat(backend);
    assert(seat != nullptr);
    struct weston_touch_device *touch_dev = linuxdroid_backend_get_touch_device(backend);
    assert(touch_dev != nullptr);

    struct timespec ts = { 1, 0 };

    // 1. Synthetic Keyboard Press & Release
    struct weston_key_event key_down = {};
    key_down.base.ts = ts;
    key_down.base.seat = seat;
    key_down.key = KEY_A;
    key_down.key_state = WL_KEYBOARD_KEY_STATE_PRESSED;
    key_down.key_update_state = STATE_UPDATE_AUTOMATIC;
    notify_key(&key_down);

    struct weston_key_event key_up = {};
    key_up.base.ts = ts;
    key_up.base.seat = seat;
    key_up.key = KEY_A;
    key_up.key_state = WL_KEYBOARD_KEY_STATE_RELEASED;
    key_up.key_update_state = STATE_UPDATE_AUTOMATIC;
    notify_key(&key_up);

    // 2. Synthetic Pointer Motion & Button & Axis
    struct weston_coord_global pos = { .c = { .x = 100.0, .y = 150.0 } };
    struct weston_pointer_motion_event motion_event = {};
    motion_event.base.ts = ts;
    motion_event.base.seat = seat;
    motion_event.mask = WESTON_POINTER_MOTION_ABS;
    motion_event.abs = pos;
    notify_motion(&motion_event);
    notify_pointer_frame(seat);

    struct weston_pointer_button_event btn_event = {};
    btn_event.base.ts = ts;
    btn_event.base.seat = seat;
    btn_event.button = BTN_LEFT;
    btn_event.button_state = WL_POINTER_BUTTON_STATE_PRESSED;
    notify_button(&btn_event);
    notify_pointer_frame(seat);

    struct weston_pointer_axis_event axis_ev = {};
    axis_ev.base.ts = ts;
    axis_ev.base.seat = seat;
    axis_ev.axis = WL_POINTER_AXIS_VERTICAL_SCROLL;
    axis_ev.value = 10.0;
    notify_axis(&axis_ev);
    notify_pointer_frame(seat);

    // 3. Synthetic Touch Down, Motion, Up, Cancel
    struct weston_touch_event touch_down = {};
    touch_down.base.ts = ts;
    touch_down.base.seat = seat;
    touch_down.device = touch_dev;
    touch_down.touch_type = WL_TOUCH_DOWN;
    touch_down.touch_id = 0;
    touch_down.pos = pos;
    notify_touch(&touch_down);
    notify_touch_frame(touch_dev);

    struct weston_touch_event touch_up = {};
    touch_up.base.ts = ts;
    touch_up.base.seat = seat;
    touch_up.device = touch_dev;
    touch_up.touch_type = WL_TOUCH_UP;
    touch_up.touch_id = 0;
    touch_up.pos = pos;
    notify_touch(&touch_up);
    notify_touch_frame(touch_dev);

    notify_touch_cancel(touch_dev);

    weston_compositor_destroy(compositor);
    weston_log_ctx_destroy(log_ctx);
    wl_display_destroy(display);

    printf("[PASS] test_synthetic_input_dispatch\n");
}

int main() {
    printf("=== LinuxDroid Phase 6 Native Input Integration Test Suite ===\n");
    test_keycode_translation();
    test_button_and_coordinate_translation();
    test_input_bridge_queue_and_overflow();
    test_seat_and_backend_lifecycle();
    test_synthetic_input_dispatch();
    printf("=== All Phase 6 Input Integration Tests PASSED! ===\n");
    return 0;
}
