#pragma once

#include <cstdint>
#include <linux/input-event-codes.h>

namespace linuxdroid {

class InputTranslator {
public:
    /**
     * Translates an Android KeyEvent keycode (AKEYCODE_*) to a Linux evdev keycode (KEY_*).
     * Returns KEY_RESERVED (0) if no valid mapping exists.
     */
    static uint32_t androidKeycodeToLinux(int32_t androidKeycode);

    /**
     * Maps Android MotionEvent button state mask (AMOTION_EVENT_BUTTON_*)
     * to Linux evdev pointer button code (BTN_*).
     */
    static uint32_t androidButtonToLinux(int32_t androidButton);

    /**
     * Clamps and normalizes an input coordinate (x or y) to the output bounds [0, maxBound].
     * If the coordinate is NaN or infinite, it is safely normalized to 0.0.
     */
    static double clampCoordinate(float coord, int32_t maxBound);

    /**
     * Translates Android scroll delta to Wayland pointer axis value.
     * Inverts the vertical scroll sign to match Wayland convention.
     */
    static double translateScrollAxis(float androidScrollDelta);
};

} // namespace linuxdroid
