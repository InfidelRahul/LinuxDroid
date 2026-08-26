#pragma once

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
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

