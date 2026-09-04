#include "android_presentation.h"
#include "linuxdroid_backend.h"

#include <android/log.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <cassert>
#include <cstdio>
#include <cstdlib>

#define TAG "LinuxDroid/GlesTest"
#define LOGI(fmt, ...) printf("[INFO] " fmt "\n", ##__VA_ARGS__)
#define LOGE(fmt, ...) fprintf(stderr, "[ERROR] " fmt "\n", ##__VA_ARGS__)

#define TEST_ASSERT(cond, msg) \
    do { \
        if (!(cond)) { \
            LOGE("Assertion failed: %s (%s:%d)", msg, __FILE__, __LINE__); \
            return 1; \
        } \
    } while (0)

int main() {
    LOGI("=================================================");
    LOGI("  LinuxDroid Phase 8 GLES Renderer Test Suite");
    LOGI("=================================================");

    // 1. Initialize EGL Display & Context
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    TEST_ASSERT(dpy != EGL_NO_DISPLAY, "Failed to get default EGL display");

    EGLint major = 0, minor = 0;
    EGLBoolean initialized = eglInitialize(dpy, &major, &minor);
    TEST_ASSERT(initialized == EGL_TRUE, "Failed to initialize EGL display");
    LOGI("PASS: EGL initialized (version %d.%d)", major, minor);

    // Bind GLES API
    eglBindAPI(EGL_OPENGL_ES_API);

    // Choose config
    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config;
    EGLint num_configs = 0;
    TEST_ASSERT(eglChooseConfig(dpy, attribs, &config, 1, &num_configs) == EGL_TRUE && num_configs > 0,
                "Failed to choose EGL config");

    // Create 1x1 pbuffer surface for surfaceless fallback
    const EGLint pbuffer_attribs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    EGLSurface surf = eglCreatePbufferSurface(dpy, config, pbuffer_attribs);
    TEST_ASSERT(surf != EGL_NO_SURFACE, "Failed to create EGL pbuffer surface");

    const EGLint ctx_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };
    EGLContext ctx = eglCreateContext(dpy, config, EGL_NO_CONTEXT, ctx_attribs);
    TEST_ASSERT(ctx != EGL_NO_CONTEXT, "Failed to create EGL context");

    TEST_ASSERT(eglMakeCurrent(dpy, surf, surf, ctx) == EGL_TRUE,
                "Failed to make EGL context current");
    LOGI("PASS: EGL context current on GPU");

    const char* gl_vendor = (const char*)glGetString(GL_VENDOR);
    const char* gl_renderer = (const char*)glGetString(GL_RENDERER);
    const char* gl_version = (const char*)glGetString(GL_VERSION);
    LOGI("PASS: GPU device: vendor='%s', renderer='%s', version='%s'",
         gl_vendor ? gl_vendor : "unknown",
         gl_renderer ? gl_renderer : "unknown",
         gl_version ? gl_version : "unknown");

    // 2. Test Presentation Buffer Pool GLES Targets
    android_presentation_t* pres = android_presentation_create();
    TEST_ASSERT(pres != nullptr, "Failed to create android_presentation");

    int err = android_presentation_enable(pres, nullptr, 1080, 2400);
    TEST_ASSERT(err == 0, "Failed to enable android_presentation pool");
    LOGI("PASS: Android presentation buffer pool enabled (1080x2400)");

    err = android_presentation_init_gles_targets(pres, dpy);
    TEST_ASSERT(err == 0, "Failed to initialize GLES targets on presentation pool");
    LOGI("PASS: GLES targets initialized on AHardwareBuffer pool");

    for (int i = 0; i < 3; ++i) {
        uint32_t fbo = android_presentation_get_fbo(pres, i);
        void* img = android_presentation_get_egl_image(pres, i);
        struct AHardwareBuffer* ahb = android_presentation_get_buffer(pres, i);

        TEST_ASSERT(fbo > 0, "Slot FBO handle must be > 0");
        TEST_ASSERT(img != nullptr, "Slot EGLImageKHR must be non-null");
        TEST_ASSERT(ahb != nullptr, "Slot AHardwareBuffer must be non-null");

        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        TEST_ASSERT(status == GL_FRAMEBUFFER_COMPLETE, "FBO status must be GL_FRAMEBUFFER_COMPLETE");

        glClearColor(0.1f * (i + 1), 0.2f * (i + 1), 0.3f * (i + 1), 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glFinish();

        LOGI("PASS: Slot %d - FBO=%u, EGLImage=%p, Complete & Renderable", i, fbo, img);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    // 3. Test Renderer State Machine Types & Values
    TEST_ASSERT(LINUXDROID_RENDERER_GLES == 0, "LINUXDROID_RENDERER_GLES must be 0");
    TEST_ASSERT(LINUXDROID_RENDERER_PIXMAN == 1, "LINUXDROID_RENDERER_PIXMAN must be 1");
    TEST_ASSERT(LINUXDROID_RENDERER_STATE_UNINITIALIZED == 0, "State UNINITIALIZED must be 0");
    TEST_ASSERT(LINUXDROID_RENDERER_STATE_GLES_INITIALIZED == 1, "State GLES_INITIALIZED must be 1");
    TEST_ASSERT(LINUXDROID_RENDERER_STATE_GLES_FAILED == 2, "State GLES_FAILED must be 2");
    TEST_ASSERT(LINUXDROID_RENDERER_STATE_PIXMAN_INITIALIZED == 3, "State PIXMAN_INITIALIZED must be 3");
    TEST_ASSERT(LINUXDROID_RENDERER_STATE_PIXMAN_FAILED == 4, "State PIXMAN_FAILED must be 4");
    LOGI("PASS: Renderer state machine constants verified");

    // 4. Test Clean Teardown
    android_presentation_destroy_gles_targets(pres, dpy);
    LOGI("PASS: GLES targets destroyed cleanly");

    android_presentation_destroy(pres);
    LOGI("PASS: Presentation destroyed cleanly");

    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(dpy, surf);
    eglDestroyContext(dpy, ctx);
    eglTerminate(dpy);
    LOGI("PASS: EGL context and display terminated cleanly");

    LOGI("=================================================");
    LOGI("  ALL PHASE 8 GLES RENDERER TESTS PASSED!");
    LOGI("=================================================");
    return 0;
}

