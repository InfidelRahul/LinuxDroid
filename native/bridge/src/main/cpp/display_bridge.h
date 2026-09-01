#pragma once

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <mutex>

namespace linuxdroid {

class DisplayBridge {
public:
    static DisplayBridge& getInstance();

    void onSurfaceCreated(JNIEnv* env, jobject surface, int width, int height);
    void onSurfaceChanged(JNIEnv* env, jobject surface, int width, int height, int format);
    void onSurfaceDestroyed();

    bool isReady() const;
    int getWidth() const { return width_; }
    int getHeight() const { return height_; }

    bool lockBuffer(ANativeWindow_Buffer* outBuffer);
    bool unlockAndPost();

    // Source pixel byte order, matching FramePixelFormat on the Kotlin side.
    enum SourceFormat {
        kSourceRgba8888 = 0,
        kSourceBgra8888 = 1,
        kSourceRgbx8888 = 2,
        kSourceBgrx8888 = 3,
    };

    // Result codes shared with Kotlin; must stay in sync with
    // AndroidFrameSink.NativePresentResult.
    enum PresentStatus {
        kPresentOk = 0,
        kPresentNoWindow = -1,
        kPresentLockFailed = -2,
        kPresentBadGeometry = -3,
        kPresentPostFailed = -4,
        kPresentUnsupportedFormat = -5,
    };

    // Configures the window's buffer geometry for frames of the given size.
    bool configure(int width, int height);

    // Copies one frame into the next window buffer and posts it.
    // srcStride is the source row stride in bytes and is honoured as given.
    PresentStatus presentFrame(const uint8_t* pixels,
                               size_t byteCount,
                               int width,
                               int height,
                               int srcStride,
                               int sourceFormat);

private:
    DisplayBridge();
    ~DisplayBridge();

    DisplayBridge(const DisplayBridge&) = delete;
    DisplayBridge& operator=(const DisplayBridge&) = delete;

    mutable std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    int width_ = 1920;
    int height_ = 1080;
    int format_ = WINDOW_FORMAT_RGBA_8888;
};

} // namespace linuxdroid

