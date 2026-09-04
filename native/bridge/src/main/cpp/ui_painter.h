#pragma once

#include <cstdint>
#include <string>
#include <cstddef>

namespace linuxdroid {

class UIPainter {
public:
    UIPainter(uint32_t* pixels, int width, int height, int stride_pixels = 0);
    ~UIPainter() = default;

    int getWidth() const { return width_; }
    int getHeight() const { return height_; }

    // Primitive Drawing
    void clear(uint32_t color);
    void drawPixel(int x, int y, uint32_t color);
    void drawRect(int x, int y, int w, int h, uint32_t color);
    void drawFilledRect(int x, int y, int w, int h, uint32_t color);
    void drawRoundedRect(int x, int y, int w, int h, int radius, uint32_t color);
    void drawLinearGradient(int x, int y, int w, int h, uint32_t start_color, uint32_t end_color, bool vertical = true);

    // Text Rendering
    void drawText(int x, int y, const char* text, uint32_t color, int scale = 1);
    void drawTextTruncated(int x, int y, const char* text, int max_chars, uint32_t color, int scale = 1);
    int getTextWidth(const char* text, int scale = 1) const;

    // Built-in Vector Icons
    void drawLauncherIcon(int x, int y, int size, uint32_t color);
    void drawCloseIcon(int x, int y, int size, uint32_t color);
    void drawMinimizeIcon(int x, int y, int size, uint32_t color);
    void drawMaximizeIcon(int x, int y, int size, uint32_t color);
    void drawTerminalIcon(int x, int y, int size, uint32_t color);
    void drawFolderIcon(int x, int y, int size, uint32_t color);
    void drawSettingsIcon(int x, int y, int size, uint32_t color);
    void drawClockIcon(int x, int y, int size, uint32_t color);
    void drawBatteryIcon(int x, int y, int width, int height, int percent, bool charging, uint32_t color);
    void drawWifiIcon(int x, int y, int size, bool connected, uint32_t color);

    // Fast Color Helpers
    static inline uint32_t rgba(uint8_t r, uint8_t g, uint8_t b, uint8_t a = 255) {
        return (static_cast<uint32_t>(a) << 24) |
               (static_cast<uint32_t>(r) << 16) |
               (static_cast<uint32_t>(g) << 8)  |
               static_cast<uint32_t>(b);
    }

    static inline uint32_t blend(uint32_t bg, uint32_t fg) {
        uint32_t a = (fg >> 24) & 0xFF;
        if (a == 255) return fg;
        if (a == 0) return bg;

        uint32_t inv_a = 255 - a;
        uint32_t r = (((fg >> 16) & 0xFF) * a + ((bg >> 16) & 0xFF) * inv_a) / 255;
        uint32_t g = (((fg >> 8)  & 0xFF) * a + ((bg >> 8)  & 0xFF) * inv_a) / 255;
        uint32_t b = ((fg & 0xFF) * a + (bg & 0xFF) * inv_a) / 255;

        return (0xFF000000) | (r << 16) | (g << 8) | b;
    }

private:
    uint32_t* pixels_{nullptr};
    int width_{0};
    int height_{0};
    int stride_{0};
};

} // namespace linuxdroid

