#pragma once

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <mutex>

namespace linuxdroid {

/**
 * Native GUI host.
 *
 * This is the native C/C++ lifecycle boundary that owns the Android
 * SurfaceView backing surface and will, in later milestones, host libweston.
 * It sits between the Android SurfaceView (above) and libweston (below):
 *
 *   Android Activity/UI -> SurfaceView -> GuiHost -> libweston (16.0.0)
 *
 * Milestone 2 scope: host + surface lifecycle only. No rendering thread, no
 * frame scheduler, no buffer submission to SurfaceFlinger, no libweston
 * startup. It owns an ANativeWindow acquired from the SurfaceView and
 * releases it cleanly on destruction.
 *
 * Surface recreation contract: a surface that is destroyed can never be
 * reused. Each time the Android side reports a (new) surface, this host
 * releases any previously held ANativeWindow and re-acquires from the given
 * surface, bumping an internal generation counter. Surface size changes are
 * re-attached through the same path, so a stale/destroyed handle is never
 * retained across a recreation.
 */
class GuiHost {
public:
    static GuiHost& getInstance();

    // Host lifecycle boundary (Android container/view attach + detach).
    void onGuiHostCreated();
    void onGuiHostDestroyed();

    // Android SurfaceView surface lifecycle.
    void onSurfaceCreated(JNIEnv* env, jobject surface, int width, int height);
    void onSurfaceChanged(JNIEnv* env, jobject surface, int width, int height, int format);
    void onSurfaceDestroyed();

    // Observability (kept simple; no rendering thread yet).
    bool isHostActive() const;
    bool isReady() const;
    int getWidth() const;
    int getHeight() const;

private:
    GuiHost();
    ~GuiHost();

    GuiHost(const GuiHost&) = delete;
    GuiHost& operator=(const GuiHost&) = delete;

    // Must be called with mutex_ held.
    void attachSurface(JNIEnv* env, jobject surface, int width, int height);

    mutable std::mutex mutex_;
    bool hostActive_ = false;
    bool surfaceActive_ = false;
    unsigned int surfaceGeneration_ = 0;
    ANativeWindow* window_ = nullptr;
    int width_ = 1920;
    int height_ = 1080;
    int format_ = WINDOW_FORMAT_RGBA_8888;
};

} // namespace linuxdroid
