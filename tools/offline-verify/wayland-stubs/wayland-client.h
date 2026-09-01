#pragma once
#include <stdint.h>
struct wl_display; struct wl_registry; struct wl_shm; struct wl_shm_pool;
struct wl_buffer; struct wl_output; struct wl_proxy;
struct wl_interface { const char* name; };
extern const struct wl_interface wl_shm_interface, wl_output_interface;
enum { WL_SHM_FORMAT_ARGB8888=0, WL_SHM_FORMAT_XRGB8888=1 };
struct wl_registry_listener {
  void (*global)(void*, struct wl_registry*, uint32_t, const char*, uint32_t);
  void (*global_remove)(void*, struct wl_registry*, uint32_t);
};
struct wl_display* wl_display_connect(const char*);
void wl_display_disconnect(struct wl_display*);
struct wl_registry* wl_display_get_registry(struct wl_display*);
int wl_display_roundtrip(struct wl_display*);
int wl_display_dispatch(struct wl_display*);
int wl_registry_add_listener(struct wl_registry*, const struct wl_registry_listener*, void*);
void* wl_registry_bind(struct wl_registry*, uint32_t, const struct wl_interface*, uint32_t);
struct wl_shm_pool* wl_shm_create_pool(struct wl_shm*, int32_t, int32_t);
void wl_shm_pool_destroy(struct wl_shm_pool*);
struct wl_buffer* wl_shm_pool_create_buffer(struct wl_shm_pool*, int32_t, int32_t, int32_t, int32_t, uint32_t);
void wl_buffer_destroy(struct wl_buffer*);
