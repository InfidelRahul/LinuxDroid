#include "ui_painter.h"
#include "font_8x16.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace linuxdroid {

UIPainter::UIPainter(uint32_t* pixels, int width, int height, int stride_pixels)
    : pixels_(pixels), width_(width), height_(height),
      stride_((stride_pixels > 0) ? stride_pixels : width) {}

void UIPainter::clear(uint32_t color) {
    if (!pixels_ || width_ <= 0 || height_ <= 0) return;
    for (int y = 0; y < height_; ++y) {
        uint32_t* row = pixels_ + y * stride_;
        std::fill_n(row, width_, color);
    }
}

void UIPainter::drawPixel(int x, int y, uint32_t color) {
    if (!pixels_ || x < 0 || x >= width_ || y < 0 || y >= height_) return;
    uint32_t* dst = pixels_ + y * stride_ + x;
    *dst = blend(*dst, color);
}

void UIPainter::drawRect(int x, int y, int w, int h, uint32_t color) {
    if (!pixels_ || w <= 0 || h <= 0) return;
    for (int i = 0; i < w; ++i) {
        drawPixel(x + i, y, color);
        drawPixel(x + i, y + h - 1, color);
    }
    for (int j = 0; j < h; ++j) {
        drawPixel(x, y + j, color);
        drawPixel(x + w - 1, y + j, color);
    }
}

void UIPainter::drawFilledRect(int x, int y, int w, int h, uint32_t color) {
    if (!pixels_ || w <= 0 || h <= 0) return;
    int x0 = std::max(0, x);
    int y0 = std::max(0, y);
    int x1 = std::min(width_, x + w);
    int y1 = std::min(height_, y + h);

    uint32_t a = (color >> 24) & 0xFF;
    if (a == 255) {
        for (int cy = y0; cy < y1; ++cy) {
            uint32_t* row = pixels_ + cy * stride_ + x0;
            std::fill_n(row, x1 - x0, color);
        }
    } else if (a > 0) {
        for (int cy = y0; cy < y1; ++cy) {
            for (int cx = x0; cx < x1; ++cx) {
                uint32_t* dst = pixels_ + cy * stride_ + cx;
                *dst = blend(*dst, color);
            }
        }
    }
}

void UIPainter::drawRoundedRect(int x, int y, int w, int h, int radius, uint32_t color) {
    if (!pixels_ || w <= 0 || h <= 0) return;
    int r = std::min({radius, w / 2, h / 2});
    if (r <= 0) {
        drawFilledRect(x, y, w, h, color);
        return;
    }

    int x0 = std::max(0, x);
    int y0 = std::max(0, y);
    int x1 = std::min(width_, x + w);
    int y1 = std::min(height_, y + h);

    int r_sq = r * r;

    for (int cy = y0; cy < y1; ++cy) {
        for (int cx = x0; cx < x1; ++cx) {
            // Check corner regions
            bool in_corner = false;
            int dx = 0, dy = 0;

            if (cx < x + r && cy < y + r) { // Top-left
                dx = (x + r) - cx;
                dy = (y + r) - cy;
                in_corner = true;
            } else if (cx >= x + w - r && cy < y + r) { // Top-right
                dx = cx - (x + w - r - 1);
                dy = (y + r) - cy;
                in_corner = true;
            } else if (cx < x + r && cy >= y + h - r) { // Bottom-left
                dx = (x + r) - cx;
                dy = cy - (y + h - r - 1);
                in_corner = true;
            } else if (cx >= x + w - r && cy >= y + h - r) { // Bottom-right
                dx = cx - (x + w - r - 1);
                dy = cy - (y + h - r - 1);
                in_corner = true;
            }

            if (in_corner) {
                int dist_sq = dx * dx + dy * dy;
                if (dist_sq <= r_sq) {
                    drawPixel(cx, cy, color);
                }
            } else {
                drawPixel(cx, cy, color);
            }
        }
    }
}

void UIPainter::drawLinearGradient(int x, int y, int w, int h, uint32_t start_color, uint32_t end_color, bool vertical) {
    if (!pixels_ || w <= 0 || h <= 0) return;

    int x0 = std::max(0, x);
    int y0 = std::max(0, y);
    int x1 = std::min(width_, x + w);
    int y1 = std::min(height_, y + h);

    uint32_t sa = (start_color >> 24) & 0xFF, sr = (start_color >> 16) & 0xFF, sg = (start_color >> 8) & 0xFF, sb = start_color & 0xFF;
    uint32_t ea = (end_color >> 24) & 0xFF, er = (end_color >> 16) & 0xFF, eg = (end_color >> 8) & 0xFF, eb = end_color & 0xFF;

    if (vertical) {
        float span = (h > 1) ? static_cast<float>(h - 1) : 1.0f;
        for (int cy = y0; cy < y1; ++cy) {
            float t = static_cast<float>(cy - y) / span;
            uint32_t a = static_cast<uint32_t>(sa + (ea - sa) * t);
            uint32_t r = static_cast<uint32_t>(sr + (er - sr) * t);
            uint32_t g = static_cast<uint32_t>(sg + (eg - sg) * t);
            uint32_t b = static_cast<uint32_t>(sb + (eb - sb) * t);
            uint32_t row_color = rgba(r, g, b, a);

            uint32_t* row = pixels_ + cy * stride_ + x0;
            std::fill_n(row, x1 - x0, row_color);
        }
    } else {
        float span = (w > 1) ? static_cast<float>(w - 1) : 1.0f;
        for (int cx = x0; cx < x1; ++cx) {
            float t = static_cast<float>(cx - x) / span;
            uint32_t a = static_cast<uint32_t>(sa + (ea - sa) * t);
            uint32_t r = static_cast<uint32_t>(sr + (er - sr) * t);
            uint32_t g = static_cast<uint32_t>(sg + (eg - sg) * t);
            uint32_t b = static_cast<uint32_t>(sb + (eb - sb) * t);
            uint32_t col_color = rgba(r, g, b, a);

            for (int cy = y0; cy < y1; ++cy) {
                drawPixel(cx, cy, col_color);
            }
        }
    }
}

void UIPainter::drawText(int x, int y, const char* text, uint32_t color, int scale) {
    if (!pixels_ || !text || scale <= 0) return;
    int cur_x = x;

    for (size_t i = 0; text[i] != '\0'; ++i) {
        uint8_t c = static_cast<uint8_t>(text[i]);
        int glyph_idx = (c >= 32 && c <= 126) ? (c - 32) : ('?' - 32);
        const uint8_t* glyph = FONT_8X16[glyph_idx];
        for (int row = 0; row < 16; ++row) {
            uint8_t bits = glyph[row];
            for (int col = 0; col < 8; ++col) {
                if ((bits >> (7 - col)) & 1) {
                    if (scale == 1) {
                        drawPixel(cur_x + col, y + row, color);
                    } else {
                        drawFilledRect(cur_x + col * scale, y + row * scale, scale, scale, color);
                    }
                }
            }
        }
        cur_x += 8 * scale;
    }
}

void UIPainter::drawTextTruncated(int x, int y, const char* text, int max_chars, uint32_t color, int scale) {
    if (!text || max_chars <= 0) return;
    std::string str(text);
    if (static_cast<int>(str.length()) > max_chars) {
        int keep = std::max(0, max_chars - 3);
        str = str.substr(0, keep) + "...";
    }
    drawText(x, y, str.c_str(), color, scale);
}

int UIPainter::getTextWidth(const char* text, int scale) const {
    if (!text || scale <= 0) return 0;
    return static_cast<int>(strlen(text)) * 8 * scale;
}

void UIPainter::drawLauncherIcon(int x, int y, int size, uint32_t color) {
    int pad = size / 5;
    int box_size = (size - 3 * pad) / 2;
    if (box_size <= 0) box_size = 2;

    int r = box_size / 4;
    drawRoundedRect(x + pad, y + pad, box_size, box_size, r, color);
    drawRoundedRect(x + 2 * pad + box_size, y + pad, box_size, box_size, r, color);
    drawRoundedRect(x + pad, y + 2 * pad + box_size, box_size, box_size, r, color);
    drawRoundedRect(x + 2 * pad + box_size, y + 2 * pad + box_size, box_size, box_size, r, color);
}

void UIPainter::drawCloseIcon(int x, int y, int size, uint32_t color) {
    int pad = size / 4;
    int stroke = std::max(1, size / 8);
    for (int i = pad; i < size - pad; ++i) {
        for (int s = 0; s < stroke; ++s) {
            drawPixel(x + i, y + i + s, color);
            drawPixel(x + (size - 1 - i), y + i + s, color);
        }
    }
}

void UIPainter::drawMinimizeIcon(int x, int y, int size, uint32_t color) {
    int bar_h = std::max(2, size / 6);
    int bar_w = size / 2;
    int bx = x + (size - bar_w) / 2;
    int by = y + size - bar_h - size / 4;
    drawFilledRect(bx, by, bar_w, bar_h, color);
}

void UIPainter::drawMaximizeIcon(int x, int y, int size, uint32_t color) {
    int pad = size / 4;
    drawRect(x + pad, y + pad, size - 2 * pad, size - 2 * pad, color);
    // Draw top title-line
    drawFilledRect(x + pad, y + pad, size - 2 * pad, std::max(2, size / 8), color);
}

void UIPainter::drawTerminalIcon(int x, int y, int size, uint32_t color) {
    int pad = size / 6;
    drawRoundedRect(x + pad, y + pad, size - 2 * pad, size - 2 * pad, 3, rgba(30, 41, 59, 230));
    drawRect(x + pad, y + pad, size - 2 * pad, size - 2 * pad, color);

    // Draw prompt `>`
    int px = x + pad + 3;
    int py = y + pad + size / 4;
    for (int i = 0; i < 4; ++i) {
        drawPixel(px + i, py + i, color);
        drawPixel(px + i, py + 8 - i, color);
    }
    // Draw cursor `_`
    drawFilledRect(px + 6, py + 8, 4, 2, color);
}

void UIPainter::drawFolderIcon(int x, int y, int size, uint32_t color) {
    int pad = size / 6;
    int tab_w = size / 3;
    int tab_h = size / 8;
    drawFilledRect(x + pad, y + pad, tab_w, tab_h, color);
    drawRoundedRect(x + pad, y + pad + tab_h, size - 2 * pad, size - 2 * pad - tab_h, 2, color);
}

void UIPainter::drawSettingsIcon(int x, int y, int size, uint32_t color) {
    int cx = x + size / 2;
    int cy = y + size / 2;
    int r = size / 3;
    for (int dy = -r; dy <= r; ++dy) {
        for (int dx = -r; dx <= r; ++dx) {
            int d = dx * dx + dy * dy;
            if (d <= r * r && d >= (r / 2) * (r / 2)) {
                drawPixel(cx + dx, cy + dy, color);
            }
        }
    }
    // 4 teeth
    int tw = std::max(2, size / 8);
    drawFilledRect(cx - tw / 2, y + 2, tw, size / 5, color);
    drawFilledRect(cx - tw / 2, y + size - size / 5 - 2, tw, size / 5, color);
    drawFilledRect(x + 2, cy - tw / 2, size / 5, tw, color);
    drawFilledRect(x + size - size / 5 - 2, cy - tw / 2, size / 5, tw, color);
}

void UIPainter::drawClockIcon(int x, int y, int size, uint32_t color) {
    int cx = x + size / 2;
    int cy = y + size / 2;
    int r = size / 2 - 2;

    for (int dy = -r; dy <= r; ++dy) {
        for (int dx = -r; dx <= r; ++dx) {
            int d = dx * dx + dy * dy;
            if (d <= r * r && d >= (r - 2) * (r - 2)) {
                drawPixel(cx + dx, cy + dy, color);
            }
        }
    }
    // Minute hand (vertical up)
    drawFilledRect(cx - 1, cy - r + 3, 2, r - 3, color);
    // Hour hand (horizontal right)
    drawFilledRect(cx, cy - 1, r / 2, 2, color);
}

void UIPainter::drawBatteryIcon(int x, int y, int width, int height, int percent, bool charging, uint32_t color) {
    int term_w = 2;
    int term_h = height / 3;
    int body_w = width - term_w - 2;

    // Outer body
    drawRoundedRect(x, y, body_w, height, 2, color);
    // Terminal tip
    drawFilledRect(x + body_w, y + (height - term_h) / 2, term_w, term_h, color);

    // Inner fill
    int fill_pad = 2;
    int inner_max_w = body_w - 2 * fill_pad;
    int inner_h = height - 2 * fill_pad;
    int fill_w = std::max(0, (inner_max_w * std::clamp(percent, 0, 100)) / 100);

    uint32_t fill_color = charging ? rgba(52, 211, 153, 255) : // Emerald green charging
                         (percent <= 20) ? rgba(248, 113, 113, 255) : // Red low
                         rgba(56, 189, 248, 255); // Cyan normal

    drawFilledRect(x + fill_pad, y + fill_pad, fill_w, inner_h, fill_color);
}

void UIPainter::drawWifiIcon(int x, int y, int size, bool connected, uint32_t color) {
    int cx = x + size / 2;
    int cy = y + size - 3;
    uint32_t draw_color = connected ? color : rgba(148, 163, 184, 180);

    // Dot at base
    drawFilledRect(cx - 1, cy - 1, 3, 3, draw_color);

    // 2 Arcs
    int r1 = size / 3;
    int r2 = size / 2;
    for (int a = -r1; a <= r1; ++a) {
        int h = static_cast<int>(std::sqrt(std::max(0, r1 * r1 - a * a)));
        drawPixel(cx + a, cy - h, draw_color);
    }
    for (int a = -r2; a <= r2; ++a) {
        int h = static_cast<int>(std::sqrt(std::max(0, r2 * r2 - a * a)));
        drawPixel(cx + a, cy - h, draw_color);
    }
}

} // namespace linuxdroid

