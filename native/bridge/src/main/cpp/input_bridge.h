#pragma once

#include <cstdint>
#include <mutex>
#include <queue>

namespace linuxdroid {

enum class InputEventType {
    TOUCH_DOWN,
    TOUCH_UP,
    TOUCH_MOVE,
    MOUSE_DOWN,
    MOUSE_UP,
    MOUSE_MOVE,
    MOUSE_SCROLL,
    KEY_DOWN,
    KEY_UP,
};

struct NativeInputEvent {
    InputEventType type;
    int32_t id = 0;
    float x = 0.0f;
    float y = 0.0f;
    float pressure = 1.0f;
    float scrollX = 0.0f;
    float scrollY = 0.0f;
    int32_t keyCode = 0;
    int32_t metaState = 0;
    int32_t unicodeChar = 0;
    uint64_t timestampNs = 0;
};

class InputBridge {
public:
    static InputBridge& getInstance();

    void sendTouchEvent(int action, int pointerId, float x, float y, float pressure);
    void sendMouseEvent(int action, int buttonState, float x, float y, float scrollX, float scrollY);
    void sendKeyEvent(int keyCode, bool isDown, int metaState, int unicodeChar);

    bool popEvent(NativeInputEvent* outEvent);
    size_t getPendingEventCount() const;
    void clear();

private:
    InputBridge();
    mutable std::mutex mutex_;
    std::queue<NativeInputEvent> eventQueue_;
    static constexpr size_t MAX_QUEUE_SIZE = 512;
};

} // namespace linuxdroid

