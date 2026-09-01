#include "gui_host.h"

#include <android/log.h>

#define TAG "LinuxDroid/GuiHost"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

GuiHost& GuiHost::getInstance() {
    static GuiHost instance;
    return instance;
}

GuiHost::GuiHost() = default;

GuiHost::~GuiHost() {
    onSurfaceDestroyed();
}

void GuiHost::onGuiHostCreated() {
    std::lock_guard<std::mutex> lock(mutex_);
    hostActive_ = true;
    LOGI("GUI host created (surface generation=%u)", surfaceGeneration_);
}

void GuiHost::onGuiHostDestroyed() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        LOGI("GUI host destroyed: releasing ANativeWindow");
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    surfaceActive_ = false;
    hostActive_ = false;
    // Bump the generation so any previously-derived surface is invalidated and
    // can never be reused after the host is torn down.
    surfaceGeneration_++;
    LOGI("GUI host destroyed (surface generation=%u)", surfaceGeneration_);
}

void GuiHost::attachSurface(JNIEnv* env, jobject surface, int width, int height) {
    // Always release a previously held window. A destroyed surface must never
    // be retained or reused when Android delivers a fresh one.
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }

    if (surface == nullptr) {
        surfaceActive_ = false;
        LOGW("attachSurface called with null surface; leaving no window");
        return;
    }

    ANativeWindow* newWindow = ANativeWindow_fromSurface(env, surface);
    if (newWindow != nullptr) {
        window_ = newWindow;
        surfaceActive_ = true;
        surfaceGeneration_++;
        width_ = width;
        height_ = height;
        ANativeWindow_setBuffersGeometry(window_, width_, height_, format_);
        LOGI("Surface attached: %dx%d (generation=%u)", width_, height_, surfaceGeneration_);
    } else {
        surfaceActive_ = false;
        LOGE("ANativeWindow_fromSurface returned null; surface not attached");
    }
}

void GuiHost::onSurfaceCreated(JNIEnv* env, jobject surface, int width, int height) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!hostActive_) {
        // Defensive: a surface can arrive before an explicit host-created
        // notification (e.g. on some device configs). Treat it as the host
        // becoming active so the lifecycle stays coherent.
        hostActive_ = true;
    }
    attachSurface(env, surface, width, height);
}

void GuiHost::onSurfaceChanged(JNIEnv* env, jobject surface, int width, int height, int format) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (format != 0) {
        format_ = format;
    }
    // A size change frequently accompanies surface recreation and delivers a
    // new surface object. Re-attach so the destroyed old surface is never used.
    attachSurface(env, surface, width, height);
}

void GuiHost::onSurfaceDestroyed() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        LOGI("Surface destroyed: releasing ANativeWindow (generation=%u)", surfaceGeneration_);
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    surfaceActive_ = false;
    surfaceGeneration_++;
}

bool GuiHost::isHostActive() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return hostActive_;
}

bool GuiHost::isReady() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return hostActive_ && surfaceActive_ && window_ != nullptr;
}

int GuiHost::getWidth() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return width_;
}

int GuiHost::getHeight() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return height_;
}

} // namespace linuxdroid
