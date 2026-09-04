#include <wayland-client.h>
#include <xkbcommon/xkbcommon.h>

#include <android/log.h>
#include <sys/mman.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <cassert>

#define TAG "LinuxDroid/WaylandClient"
#define LOGI(fmt, ...) do { \
    printf("[CLIENT_INFO] " fmt "\n", ##__VA_ARGS__); \
    __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__); \
} while (0)
#define LOGW(fmt, ...) do { \
    printf("[CLIENT_WARN] " fmt "\n", ##__VA_ARGS__); \
    __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__); \
} while (0)
#define LOGE(fmt, ...) do { \
    printf("[CLIENT_ERROR] " fmt "\n", ##__VA_ARGS__); \
    __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__); \
} while (0)

struct ClientState {
    struct wl_display *display = nullptr;
    struct wl_registry *registry = nullptr;
    struct wl_compositor *compositor = nullptr;
    struct wl_shm *shm = nullptr;
    struct wl_seat *seat = nullptr;
    struct wl_surface *surface = nullptr;

    struct wl_keyboard *keyboard = nullptr;
    struct wl_pointer *pointer = nullptr;
    struct wl_touch *touch = nullptr;

    struct xkb_context *xkb_ctx = nullptr;
    struct xkb_keymap *xkb_keymap = nullptr;
    struct xkb_state *xkb_state = nullptr;

    uint32_t events_received = 0;
    bool running = true;
};

// --- Keyboard Listeners ---
static void keyboard_keymap(void *data, struct wl_keyboard *keyboard,
                            uint32_t format, int32_t fd, uint32_t size) {
    (void)keyboard;
    auto *state = static_cast<ClientState*>(data);

    if (format != WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1) {
        LOGW("keyboard_keymap: non-xkb_v1 format received: %u", format);
        close(fd);
        return;
    }

    char *map_str = static_cast<char*>(mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0));
    if (map_str == MAP_FAILED) {
        LOGE("keyboard_keymap: mmap failed");
        close(fd);
        return;
    }

    if (state->xkb_keymap) xkb_keymap_unref(state->xkb_keymap);
    if (state->xkb_state) xkb_state_unref(state->xkb_state);

    state->xkb_keymap = xkb_keymap_new_from_string(state->xkb_ctx, map_str,
                                                   XKB_KEYMAP_FORMAT_TEXT_V1,
                                                   XKB_KEYMAP_COMPILE_NO_FLAGS);
    munmap(map_str, size);
    close(fd);

    if (state->xkb_keymap) {
        state->xkb_state = xkb_state_new(state->xkb_keymap);
        LOGI("CLIENT_KEYBOARD_KEYMAP: format=xkb_v1 size=%u (keymap compiled successfully)", size);
    } else {
        LOGE("CLIENT_KEYBOARD_KEYMAP: failed to compile XKB keymap");
    }
}

static void keyboard_enter(void *data, struct wl_keyboard *keyboard,
                           uint32_t serial, struct wl_surface *surface,
                           struct wl_array *keys) {
    (void)keyboard;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_KEYBOARD_ENTER: surface=%p serial=%u held_keys=%zu",
         surface, serial, keys->size / sizeof(uint32_t));
}

static void keyboard_leave(void *data, struct wl_keyboard *keyboard,
                           uint32_t serial, struct wl_surface *surface) {
    (void)keyboard;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_KEYBOARD_LEAVE: surface=%p serial=%u", surface, serial);
}

static void keyboard_key(void *data, struct wl_keyboard *keyboard,
                         uint32_t serial, uint32_t time, uint32_t key,
                         uint32_t key_state) {
    (void)keyboard;
    (void)serial;
    (void)time;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;

    const char *state_str = (key_state == WL_KEYBOARD_KEY_STATE_PRESSED) ? "PRESSED" : "RELEASED";

    // XKB uses keycode space with +8 offset relative to Linux evdev
    xkb_keysym_t sym = XKB_KEY_NoSymbol;
    char sym_name[64] = "unknown";
    if (state->xkb_state) {
        sym = xkb_state_key_get_one_sym(state->xkb_state, key + 8);
        xkb_keysym_get_name(sym, sym_name, sizeof(sym_name));
    }

    LOGI("CLIENT_KEY: evdev_key=%u xkb_key=%u sym=0x%x (%s) state=%s",
         key, key + 8, sym, sym_name, state_str);
}

static void keyboard_modifiers(void *data, struct wl_keyboard *keyboard,
                               uint32_t serial, uint32_t mods_depressed,
                               uint32_t mods_latched, uint32_t mods_locked,
                               uint32_t group) {
    (void)keyboard;
    (void)serial;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;

    if (state->xkb_state) {
        xkb_state_update_mask(state->xkb_state, mods_depressed, mods_latched,
                              mods_locked, 0, 0, group);
    }
    LOGI("CLIENT_KEYBOARD_MODS: depressed=0x%x latched=0x%x locked=0x%x group=%u",
         mods_depressed, mods_latched, mods_locked, group);
}

static void keyboard_repeat_info(void *data, struct wl_keyboard *keyboard,
                                 int32_t rate, int32_t delay) {
    (void)keyboard;
    (void)data;
    LOGI("CLIENT_KEYBOARD_REPEAT: rate=%d delay=%d", rate, delay);
}

static const struct wl_keyboard_listener keyboard_listener = {
    .keymap = keyboard_keymap,
    .enter = keyboard_enter,
    .leave = keyboard_leave,
    .key = keyboard_key,
    .modifiers = keyboard_modifiers,
    .repeat_info = keyboard_repeat_info,
};

// --- Pointer Listeners ---
static void pointer_enter(void *data, struct wl_pointer *pointer,
                          uint32_t serial, struct wl_surface *surface,
                          wl_fixed_t sx, wl_fixed_t sy) {
    (void)pointer;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_POINTER_ENTER: surface=%p serial=%u x=%.2f y=%.2f",
         surface, serial, wl_fixed_to_double(sx), wl_fixed_to_double(sy));
}

static void pointer_leave(void *data, struct wl_pointer *pointer,
                          uint32_t serial, struct wl_surface *surface) {
    (void)pointer;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_POINTER_LEAVE: surface=%p serial=%u", surface, serial);
}

static void pointer_motion(void *data, struct wl_pointer *pointer,
                           uint32_t time, wl_fixed_t sx, wl_fixed_t sy) {
    (void)pointer;
    (void)time;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_POINTER_MOTION: x=%.2f y=%.2f",
         wl_fixed_to_double(sx), wl_fixed_to_double(sy));
}

static void pointer_button(void *data, struct wl_pointer *pointer,
                           uint32_t serial, uint32_t time, uint32_t button,
                           uint32_t btn_state) {
    (void)pointer;
    (void)serial;
    (void)time;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_POINTER_BUTTON: button=0x%x state=%s",
         button, (btn_state == WL_POINTER_BUTTON_STATE_PRESSED) ? "PRESSED" : "RELEASED");
}

static void pointer_axis(void *data, struct wl_pointer *pointer,
                         uint32_t time, uint32_t axis, wl_fixed_t value) {
    (void)pointer;
    (void)time;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_POINTER_AXIS: axis=%u value=%.2f", axis, wl_fixed_to_double(value));
}

static void pointer_frame(void *data, struct wl_pointer *pointer) {
    (void)pointer;
    (void)data;
}

static void pointer_axis_source(void *data, struct wl_pointer *pointer, uint32_t axis_source) {
    (void)pointer;
    (void)data;
    (void)axis_source;
}

static void pointer_axis_stop(void *data, struct wl_pointer *pointer, uint32_t time, uint32_t axis) {
    (void)pointer;
    (void)data;
    (void)time;
    (void)axis;
}

static void pointer_axis_discrete(void *data, struct wl_pointer *pointer, uint32_t axis, int32_t discrete) {
    (void)pointer;
    (void)data;
    (void)axis;
    (void)discrete;
}

static const struct wl_pointer_listener pointer_listener = {
    .enter = pointer_enter,
    .leave = pointer_leave,
    .motion = pointer_motion,
    .button = pointer_button,
    .axis = pointer_axis,
    .frame = pointer_frame,
    .axis_source = pointer_axis_source,
    .axis_stop = pointer_axis_stop,
    .axis_discrete = pointer_axis_discrete,
};

// --- Touch Listeners ---
static void touch_down(void *data, struct wl_touch *touch,
                       uint32_t serial, uint32_t time, struct wl_surface *surface,
                       int32_t id, wl_fixed_t x, wl_fixed_t y) {
    (void)touch;
    (void)serial;
    (void)time;
    (void)surface;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_TOUCH_DOWN: id=%d x=%.2f y=%.2f",
         id, wl_fixed_to_double(x), wl_fixed_to_double(y));
}

static void touch_up(void *data, struct wl_touch *touch,
                     uint32_t serial, uint32_t time, int32_t id) {
    (void)touch;
    (void)serial;
    (void)time;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_TOUCH_UP: id=%d", id);
}

static void touch_motion(void *data, struct wl_touch *touch,
                         uint32_t time, int32_t id, wl_fixed_t x, wl_fixed_t y) {
    (void)touch;
    (void)time;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_TOUCH_MOTION: id=%d x=%.2f y=%.2f",
         id, wl_fixed_to_double(x), wl_fixed_to_double(y));
}

static void touch_frame(void *data, struct wl_touch *touch) {
    (void)touch;
    (void)data;
}

static void touch_cancel(void *data, struct wl_touch *touch) {
    (void)touch;
    auto *state = static_cast<ClientState*>(data);
    state->events_received++;
    LOGI("CLIENT_TOUCH_CANCEL");
}

static const struct wl_touch_listener touch_listener = {
    .down = touch_down,
    .up = touch_up,
    .motion = touch_motion,
    .frame = touch_frame,
    .cancel = touch_cancel,
};

// --- Seat Listener ---
static void seat_handle_capabilities(void *data, struct wl_seat *seat, uint32_t caps) {
    auto *state = static_cast<ClientState*>(data);

    if ((caps & WL_SEAT_CAPABILITY_KEYBOARD) && !state->keyboard) {
        state->keyboard = wl_seat_get_keyboard(seat);
        wl_keyboard_add_listener(state->keyboard, &keyboard_listener, state);
        LOGI("CLIENT_SEAT: bound wl_keyboard");
    } else if (!(caps & WL_SEAT_CAPABILITY_KEYBOARD) && state->keyboard) {
        wl_keyboard_destroy(state->keyboard);
        state->keyboard = nullptr;
    }

    if ((caps & WL_SEAT_CAPABILITY_POINTER) && !state->pointer) {
        state->pointer = wl_seat_get_pointer(seat);
        wl_pointer_add_listener(state->pointer, &pointer_listener, state);
        LOGI("CLIENT_SEAT: bound wl_pointer");
    } else if (!(caps & WL_SEAT_CAPABILITY_POINTER) && state->pointer) {
        wl_pointer_destroy(state->pointer);
        state->pointer = nullptr;
    }

    if ((caps & WL_SEAT_CAPABILITY_TOUCH) && !state->touch) {
        state->touch = wl_seat_get_touch(seat);
        wl_touch_add_listener(state->touch, &touch_listener, state);
        LOGI("CLIENT_SEAT: bound wl_touch");
    } else if (!(caps & WL_SEAT_CAPABILITY_TOUCH) && state->touch) {
        wl_touch_destroy(state->touch);
        state->touch = nullptr;
    }
}

static void seat_handle_name(void *data, struct wl_seat *seat, const char *name) {
    (void)seat;
    (void)data;
    LOGI("CLIENT_SEAT: seat name='%s'", name);
}

static const struct wl_seat_listener seat_listener = {
    .capabilities = seat_handle_capabilities,
    .name = seat_handle_name,
};

// --- Registry Listener ---
static void registry_handle_global(void *data, struct wl_registry *registry,
                                   uint32_t name, const char *interface, uint32_t version) {
    auto *state = static_cast<ClientState*>(data);

    if (strcmp(interface, wl_compositor_interface.name) == 0) {
        state->compositor = static_cast<struct wl_compositor*>(
            wl_registry_bind(registry, name, &wl_compositor_interface, version < 4 ? version : 4));
    } else if (strcmp(interface, wl_shm_interface.name) == 0) {
        state->shm = static_cast<struct wl_shm*>(
            wl_registry_bind(registry, name, &wl_shm_interface, 1));
    } else if (strcmp(interface, wl_seat_interface.name) == 0) {
        state->seat = static_cast<struct wl_seat*>(
            wl_registry_bind(registry, name, &wl_seat_interface, version < 7 ? version : 7));
        wl_seat_add_listener(state->seat, &seat_listener, state);
    }
}

static void registry_handle_global_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data;
    (void)registry;
    (void)name;
}

static const struct wl_registry_listener registry_listener = {
    .global = registry_handle_global,
    .global_remove = registry_handle_global_remove,
};

int main(int argc, char **argv) {
    const char *socket_name = nullptr;
    int max_events = 0;

    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--socket") == 0 && i + 1 < argc) {
            socket_name = argv[++i];
        } else if (strcmp(argv[i], "--max-events") == 0 && i + 1 < argc) {
            max_events = atoi(argv[++i]);
        }
    }

    LOGI("Starting wayland_input_test_client (socket=%s, max_events=%d)...",
         socket_name ? socket_name : "(default WAYLAND_DISPLAY)", max_events);

    ClientState state;
    state.xkb_ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    if (!state.xkb_ctx) {
        LOGE("Failed to create XKB context");
        return 1;
    }

    state.display = wl_display_connect(socket_name);
    if (!state.display) {
        LOGE("Failed to connect to Wayland display");
        xkb_context_unref(state.xkb_ctx);
        return 1;
    }

    state.registry = wl_display_get_registry(state.display);
    wl_registry_add_listener(state.registry, &registry_listener, &state);

    // First roundtrip to bind globals
    wl_display_roundtrip(state.display);

    if (!state.compositor || !state.seat) {
        LOGE("Required Wayland globals missing (compositor=%p, seat=%p)",
             state.compositor, state.seat);
        wl_display_disconnect(state.display);
        xkb_context_unref(state.xkb_ctx);
        return 1;
    }

    // Second roundtrip to process seat capabilities
    wl_display_roundtrip(state.display);

    state.surface = wl_compositor_create_surface(state.compositor);
    wl_surface_commit(state.surface);
    wl_display_roundtrip(state.display);

    LOGI("Wayland input client initialized and listening for events.");

    while (state.running && wl_display_dispatch(state.display) != -1) {
        if (max_events > 0 && state.events_received >= static_cast<uint32_t>(max_events)) {
            LOGI("Reached max requested events (%d). Exiting cleanly.", max_events);
            break;
        }
    }

    if (state.surface) wl_surface_destroy(state.surface);
    if (state.keyboard) wl_keyboard_destroy(state.keyboard);
    if (state.pointer) wl_pointer_destroy(state.pointer);
    if (state.touch) wl_touch_destroy(state.touch);
    if (state.seat) wl_seat_destroy(state.seat);
    if (state.shm) wl_shm_destroy(state.shm);
    if (state.compositor) wl_compositor_destroy(state.compositor);
    if (state.registry) wl_registry_destroy(state.registry);
    if (state.display) wl_display_disconnect(state.display);

    if (state.xkb_state) xkb_state_unref(state.xkb_state);
    if (state.xkb_keymap) xkb_keymap_unref(state.xkb_keymap);
    if (state.xkb_ctx) xkb_context_unref(state.xkb_ctx);

    LOGI("Wayland input client terminated cleanly.");
    return 0;
}
