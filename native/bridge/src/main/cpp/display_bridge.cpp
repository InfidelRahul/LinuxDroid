#include "display_bridge.h"

#include <android/log.h>

#include <algorithm>
#include <cstring>

#define TAG "LinuxDroid/DisplayBridge"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

DisplayBridge& DisplayBridge::getInstance() {
    static DisplayBridge instance;
    return instance;
}

DisplayBridge::DisplayBridge() = default;

DisplayBridge::~DisplayBridge() {
    onSurfaceDestroyed();
}

void DisplayBridge::onSurfaceCreated(JNIEnv* env, jobject surface, int width, int height) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }

    if (surface != nullptr) {
        window_ = ANativeWindow_fromSurface(env, surface);
        if (window_ != nullptr) {
            width_ = width;
            height_ = height;
            ANativeWindow_setBuffersGeometry(window_, width_, height_, format_);
            LOGI("ANativeWindow attached successfully: %dx%d", width_, height_);
        } else {
            LOGE("ANativeWindow_fromSurface failed");
        }
    }
}

void DisplayBridge::onSurfaceChanged(JNIEnv* env, jobject surface, int width, int height, int format) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }

    if (surface != nullptr) {
        window_ = ANativeWindow_fromSurface(env, surface);
        if (window_ != nullptr) {
            width_ = width;
            height_ = height;
            format_ = (format != 0) ? format : WINDOW_FORMAT_RGBA_8888;
            ANativeWindow_setBuffersGeometry(window_, width_, height_, format_);
            LOGI("ANativeWindow changed: %dx%d, format=%d", width_, height_, format_);
        }
    }
}

void DisplayBridge::onSurfaceDestroyed() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        LOGI("Releasing ANativeWindow");
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

bool DisplayBridge::isReady() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return window_ != nullptr;
}

bool DisplayBridge::lockBuffer(ANativeWindow_Buffer* outBuffer) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr || outBuffer == nullptr) return false;
    return ANativeWindow_lock(window_, outBuffer, nullptr) == 0;
}

bool DisplayBridge::unlockAndPost() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return false;
    return ANativeWindow_unlockAndPost(window_) == 0;
}

bool DisplayBridge::configure(int width, int height) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr || width <= 0 || height <= 0) return false;
    // Always request RGBA_8888: it is the format presentFrame() converts into,
    // and it is universally supported by ANativeWindow.
    format_ = WINDOW_FORMAT_RGBA_8888;
    if (ANativeWindow_setBuffersGeometry(window_, width, height, format_) != 0) {
        LOGE("ANativeWindow_setBuffersGeometry(%dx%d) failed", width, height);
        return false;
    }
    width_ = width;
    height_ = height;
    LOGI("Output configured: %dx%d RGBA_8888", width_, height_);
    return true;
}

namespace {

// Copies one row, swapping the red and blue channels. Used when the source is
// BGRA/BGRX (DRM ARGB8888/XRGB8888 in memory order) and the destination window
// is RGBA_8888. Alpha is forced opaque for the X (no-alpha) variants, since
// those carry undefined bytes in the fourth channel.
inline void convertRowSwapRb(const uint8_t* src, uint8_t* dst, int pixels, bool forceOpaque) {
    for (int i = 0; i < pixels; ++i) {
        const uint8_t b = src[0];
        const uint8_t g = src[1];
        const uint8_t r = src[2];
        const uint8_t a = forceOpaque ? 0xFF : src[3];
        dst[0] = r;
        dst[1] = g;
        dst[2] = b;
        dst[3] = a;
        src += 4;
        dst += 4;
    }
}

inline void copyRowForceOpaque(const uint8_t* src, uint8_t* dst, int pixels) {
    for (int i = 0; i < pixels; ++i) {
        dst[0] = src[0];
        dst[1] = src[1];
        dst[2] = src[2];
        dst[3] = 0xFF;
        src += 4;
        dst += 4;
    }
}

} // namespace

DisplayBridge::PresentStatus DisplayBridge::presentFrame(const uint8_t* pixels,
                                                         size_t byteCount,
                                                         int width,
                                                         int height,
                                                         int srcStride,
                                                         int sourceFormat) {
    if (pixels == nullptr || width <= 0 || height <= 0 || srcStride < width * 4) {
        return kPresentBadGeometry;
    }
    if (byteCount < static_cast<size_t>(srcStride) * static_cast<size_t>(height)) {
        return kPresentBadGeometry;
    }
    if (sourceFormat < kSourceRgba8888 || sourceFormat > kSourceBgrx8888) {
        return kPresentUnsupportedFormat;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return kPresentNoWindow;

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        return kPresentLockFailed;
    }

    // The window may be a different size than the frame (mid-resize). Copy the
    // overlapping region only, so a stale frame is letterboxed rather than
    // overrunning the buffer.
    const int copyWidth = std::min(width, buffer.width);
    const int copyHeight = std::min(height, buffer.height);

    // ANativeWindow reports stride in pixels; the frame's is in bytes.
    const int dstStrideBytes = buffer.stride * 4;
    auto* dstBase = static_cast<uint8_t*>(buffer.bits);

    const bool swapRb = (sourceFormat == kSourceBgra8888 || sourceFormat == kSourceBgrx8888);
    const bool forceOpaque = (sourceFormat == kSourceRgbx8888 || sourceFormat == kSourceBgrx8888);

    for (int y = 0; y < copyHeight; ++y) {
        const uint8_t* srcRow = pixels + static_cast<size_t>(y) * static_cast<size_t>(srcStride);
        uint8_t* dstRow = dstBase + static_cast<size_t>(y) * static_cast<size_t>(dstStrideBytes);
        if (swapRb) {
            convertRowSwapRb(srcRow, dstRow, copyWidth, forceOpaque);
        } else if (forceOpaque) {
            copyRowForceOpaque(srcRow, dstRow, copyWidth);
        } else {
            memcpy(dstRow, srcRow, static_cast<size_t>(copyWidth) * 4);
        }
    }

    if (ANativeWindow_unlockAndPost(window_) != 0) {
        return kPresentPostFailed;
    }
    return kPresentOk;
}

} // namespace linuxdroid

