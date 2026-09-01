#pragma once
#include <cstdint>
typedef struct ANativeWindow ANativeWindow;
typedef struct ARect { int32_t left, top, right, bottom; } ARect;
typedef struct ANativeWindow_Buffer {
    int32_t width; int32_t height; int32_t stride; int32_t format;
    void* bits; uint32_t reserved[6];
} ANativeWindow_Buffer;
enum { WINDOW_FORMAT_RGBA_8888 = 1, WINDOW_FORMAT_RGBX_8888 = 2, WINDOW_FORMAT_RGB_565 = 4 };
extern "C" {
int32_t ANativeWindow_setBuffersGeometry(ANativeWindow*, int32_t, int32_t, int32_t);
int32_t ANativeWindow_lock(ANativeWindow*, ANativeWindow_Buffer*, ARect*);
int32_t ANativeWindow_unlockAndPost(ANativeWindow*);
void ANativeWindow_release(ANativeWindow*);
}
