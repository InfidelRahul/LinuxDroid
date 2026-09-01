#pragma once
#include <wayland-client.h>
struct weston_capture_v1; struct weston_capture_source_v1;
extern const struct wl_interface weston_capture_v1_interface;
enum { WESTON_CAPTURE_V1_SOURCE_WRITEBACK=0, WESTON_CAPTURE_V1_SOURCE_FRAMEBUFFER=1 };
struct weston_capture_source_v1_listener {
  void (*format)(void*, struct weston_capture_source_v1*, uint32_t);
  void (*size)(void*, struct weston_capture_source_v1*, int32_t, int32_t);
  void (*complete)(void*, struct weston_capture_source_v1*);
  void (*retry)(void*, struct weston_capture_source_v1*);
  void (*failed)(void*, struct weston_capture_source_v1*, const char*);
};
struct weston_capture_source_v1* weston_capture_v1_create(struct weston_capture_v1*, struct wl_output*, uint32_t);
int weston_capture_source_v1_add_listener(struct weston_capture_source_v1*, const struct weston_capture_source_v1_listener*, void*);
void weston_capture_source_v1_capture(struct weston_capture_source_v1*, struct wl_buffer*);
