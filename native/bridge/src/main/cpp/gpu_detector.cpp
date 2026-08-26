#include "gpu_detector.h"

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <cstring>

#define TAG "LinuxDroid/GpuDetector"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

NativeGpuInfo GpuDetector::detect() {
    NativeGpuInfo info;
    info.vendor = "Unknown";
    info.renderer = "Unknown";
    info.version = "OpenGL ES";
    info.vulkanSupported = false;
    info.hardwareAccelerated = true;

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        LOGW("eglGetDisplay failed");
        return info;
    }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(display, &major, &minor)) {
        LOGW("eglInitialize failed");
        return info;
    }

    const char* eglVendor = eglQueryString(display, EGL_VENDOR);
    const char* eglVersion = eglQueryString(display, EGL_VERSION);
    const char* eglExt = eglQueryString(display, EGL_EXTENSIONS);

    if (eglVendor) info.vendor = eglVendor;
    if (eglVersion) info.version = eglVersion;
    if (eglExt) {
        info.extensions = eglExt;
        if (std::strstr(eglExt, "VK_") != nullptr || std::strstr(eglExt, "EGL_KHR_fence_sync") != nullptr) {
            info.vulkanSupported = true;
        }
    }

    // Check configuration
    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs = 0;
    if (eglChooseConfig(display, attribs, &config, 1, &numConfigs) && numConfigs > 0) {
        const EGLint pbufferAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        EGLSurface surface = eglCreatePbufferSurface(display, config, pbufferAttribs);
        const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
        EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);

        if (surface != EGL_NO_SURFACE && context != EGL_NO_CONTEXT) {
            if (eglMakeCurrent(display, surface, surface, context)) {
                const char* glVendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
                const char* glRenderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
                const char* glVer = reinterpret_cast<const char*>(glGetString(GL_VERSION));

                if (glVendor) info.vendor = glVendor;
                if (glRenderer) info.renderer = glRenderer;
                if (glVer) info.version = glVer;

                LOGI("GPU detected: Vendor=%s, Renderer=%s, Version=%s",
                     info.vendor.c_str(), info.renderer.c_str(), info.version.c_str());

                eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            }
            eglDestroyContext(display, context);
            eglDestroySurface(display, surface);
        }
    }

    eglTerminate(display);
    return info;
}

} // namespace linuxdroid

