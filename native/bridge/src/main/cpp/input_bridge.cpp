#include "input_bridge.h"

#include <chrono>

namespace linuxdroid {

InputBridge& InputBridge::getInstance() {
    static InputBridge instance;
    return instance;
}

InputBridge::InputBridge() = default;

static uint64_t getCurrentTimestampNs() {
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
}

void InputBridge::sendTouchEvent(int action, int pointerId, float x, float y, float pressure) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (eventQueue_.size() >= MAX_QUEUE_SIZE) {
        eventQueue_.pop();
    }

    NativeInputEvent evt;
    evt.id = pointerId;
    evt.x = x;
    evt.y = y;
    evt.pressure = pressure;
    evt.timestampNs = getCurrentTimestampNs();

    switch (action) {
        case 0: evt.type = InputEventType::TOUCH_DOWN; break; // ACTION_DOWN
        case 1: evt.type = InputEventType::TOUCH_UP; break;   // ACTION_UP
        default: evt.type = InputEventType::TOUCH_MOVE; break; // ACTION_MOVE
    }

    eventQueue_.push(evt);
}

void InputBridge::sendMouseEvent(int action, int buttonState, float x, float y, float scrollX, float scrollY) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (eventQueue_.size() >= MAX_QUEUE_SIZE) {
        eventQueue_.pop();
    }

    NativeInputEvent evt;
    evt.id = buttonState;
    evt.x = x;
    evt.y = y;
    evt.scrollX = scrollX;
    evt.scrollY = scrollY;
    evt.timestampNs = getCurrentTimestampNs();

    if (scrollX != 0.0f || scrollY != 0.0f) {
        evt.type = InputEventType::MOUSE_SCROLL;
    } else if (action == 0) {
        evt.type = InputEventType::MOUSE_DOWN;
    } else if (action == 1) {
        evt.type = InputEventType::MOUSE_UP;
    } else {
        evt.type = InputEventType::MOUSE_MOVE;
    }

    eventQueue_.push(evt);
}

void InputBridge::sendKeyEvent(int keyCode, bool isDown, int metaState, int unicodeChar) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (eventQueue_.size() >= MAX_QUEUE_SIZE) {
        eventQueue_.pop();
    }

    NativeInputEvent evt;
    evt.type = isDown ? InputEventType::KEY_DOWN : InputEventType::KEY_UP;
    evt.keyCode = keyCode;
    evt.metaState = metaState;
    evt.unicodeChar = unicodeChar;
    evt.timestampNs = getCurrentTimestampNs();

    eventQueue_.push(evt);
}

bool InputBridge::popEvent(NativeInputEvent* outEvent) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (eventQueue_.empty() || outEvent == nullptr) return false;
    *outEvent = eventQueue_.front();
    eventQueue_.pop();
    return true;
}

size_t InputBridge::getPendingEventCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return eventQueue_.size();
}

void InputBridge::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    while (!eventQueue_.empty()) {
        eventQueue_.pop();
    }
}

} // namespace linuxdroid

