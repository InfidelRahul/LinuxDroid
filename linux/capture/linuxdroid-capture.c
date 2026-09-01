/*
 * linuxdroid-capture — pulls Weston output frames into a shared frame buffer.
 *
 * Weston has no Android-surface backend, so LinuxDroid runs it on the headless
 * backend and captures the output instead. This client implements
 * weston_output_capture_v1, Weston's supported mechanism for reading an
 * output's pixels, and writes each captured frame into a memory-mapped file
 * that the Android side reads (see SharedMemoryFrameSource.kt).
 *
 * It runs inside the rootfs alongside Weston, so the frame buffer file is
 * ordinary shared memory between the two.
 *
 * File layout — 32-byte little-endian header, then pixels. Must stay in sync
 * with SharedMemoryFrameSource:
 *
 *   0  u32 magic 'LDFB'   4  u32 version   8  u32 sequence  12 u32 width
 *   16 u32 height        20 u32 stride    24 u32 format    28 u32 status
 *
 * Writer protocol: status=WRITING, write pixels, bump sequence, status=READY.
 * The reader re-checks the sequence after copying, so a frame that is
 * overwritten mid-read is dropped rather than shown torn.
 *
 * Build (inside the rootfs):
 *   cc -O2 -o linuxdroid-capture linuxdroid-capture.c \
 *      weston-output-capture-protocol.c -lwayland-client -lrt
 */

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <wayland-client.h>

#include "weston-output-capture-client-protocol.h"

#define LDFB_MAGIC 0x4246444CU /* 'LDFB' little-endian */
#define LDFB_VERSION 1U
#define LDFB_HEADER_BYTES 32U

#define LDFB_OFF_MAGIC 0
#define LDFB_OFF_VERSION 1
#define LDFB_OFF_SEQUENCE 2
#define LDFB_OFF_WIDTH 3
#define LDFB_OFF_HEIGHT 4
#define LDFB_OFF_STRIDE 5
#define LDFB_OFF_FORMAT 6
#define LDFB_OFF_STATUS 7

#define LDFB_STATUS_WRITING 0U
#define LDFB_STATUS_READY 1U

struct context {
    struct wl_display *display;
    struct wl_registry *registry;
    struct wl_shm *shm;
    struct weston_capture_v1 *capture;
    struct wl_output *output;
    struct weston_capture_source_v1 *source;

    /* Buffer Weston captures into. */
    struct wl_buffer *buffer;
    void *buffer_data;
    int buffer_fd;
    size_t buffer_size;

    /* Negotiated capture parameters. */
    int32_t width;
    int32_t height;
    int32_t stride;
    uint32_t drm_format;
    bool have_format;
    bool have_size;

    /* Output frame buffer file shared with Android. */
    const char *out_path;
    volatile uint32_t *out_header;
    uint8_t *out_pixels;
    size_t out_size;

    uint32_t sequence;
    bool capture_pending;
    bool needs_realloc;
    bool running;
    bool failed;
};

static void die(const char *msg)
{
    fprintf(stderr, "linuxdroid-capture: %s: %s\n", msg, strerror(errno));
    exit(1);
}

/* ── anonymous shm for the Wayland capture buffer ──────────────────────── */

static int create_anon_file(size_t size)
{
    int fd = memfd_create("linuxdroid-capture", MFD_CLOEXEC);
    if (fd < 0)
        return -1;
    if (ftruncate(fd, (off_t)size) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void destroy_capture_buffer(struct context *ctx)
{
    if (ctx->buffer) {
        wl_buffer_destroy(ctx->buffer);
        ctx->buffer = NULL;
    }
    if (ctx->buffer_data) {
        munmap(ctx->buffer_data, ctx->buffer_size);
        ctx->buffer_data = NULL;
    }
    if (ctx->buffer_fd >= 0) {
        close(ctx->buffer_fd);
        ctx->buffer_fd = -1;
    }
    ctx->buffer_size = 0;
}

/* Maps a DRM fourcc to the matching wl_shm format. */
static bool shm_format_for(uint32_t drm_format, uint32_t *out)
{
    switch (drm_format) {
    case 0x34325258: /* XRGB8888 */
        *out = WL_SHM_FORMAT_XRGB8888;
        return true;
    case 0x34325241: /* ARGB8888 */
        *out = WL_SHM_FORMAT_ARGB8888;
        return true;
    default:
        /* Other formats are passed through by fourcc; wl_shm codes match DRM
         * for everything except the two legacy values above. */
        *out = drm_format;
        return true;
    }
}

static bool alloc_capture_buffer(struct context *ctx)
{
    destroy_capture_buffer(ctx);

    /* weston_capture_source_v1 requires 4-byte row alignment and no extra
     * padding, so the stride is exactly width * 4 for the 32-bit formats. */
    ctx->stride = ctx->width * 4;
    ctx->buffer_size = (size_t)ctx->stride * (size_t)ctx->height;

    ctx->buffer_fd = create_anon_file(ctx->buffer_size);
    if (ctx->buffer_fd < 0) {
        fprintf(stderr, "linuxdroid-capture: cannot create shm file\n");
        return false;
    }
    ctx->buffer_data = mmap(NULL, ctx->buffer_size, PROT_READ | PROT_WRITE,
                            MAP_SHARED, ctx->buffer_fd, 0);
    if (ctx->buffer_data == MAP_FAILED) {
        ctx->buffer_data = NULL;
        return false;
    }

    uint32_t shm_format;
    if (!shm_format_for(ctx->drm_format, &shm_format))
        return false;

    struct wl_shm_pool *pool =
        wl_shm_create_pool(ctx->shm, ctx->buffer_fd, (int32_t)ctx->buffer_size);
    if (!pool)
        return false;
    ctx->buffer = wl_shm_pool_create_buffer(pool, 0, ctx->width, ctx->height,
                                            ctx->stride, shm_format);
    wl_shm_pool_destroy(pool);
    return ctx->buffer != NULL;
}

/* ── output frame buffer shared with Android ───────────────────────────── */

static bool remap_output_file(struct context *ctx)
{
    if (ctx->out_header) {
        munmap((void *)ctx->out_header, ctx->out_size);
        ctx->out_header = NULL;
        ctx->out_pixels = NULL;
    }

    size_t pixels = (size_t)ctx->stride * (size_t)ctx->height;
    ctx->out_size = LDFB_HEADER_BYTES + pixels;

    int fd = open(ctx->out_path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (fd < 0) {
        fprintf(stderr, "linuxdroid-capture: cannot open %s: %s\n",
                ctx->out_path, strerror(errno));
        return false;
    }
    if (ftruncate(fd, (off_t)ctx->out_size) < 0) {
        close(fd);
        return false;
    }
    void *map = mmap(NULL, ctx->out_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (map == MAP_FAILED)
        return false;

    ctx->out_header = (volatile uint32_t *)map;
    ctx->out_pixels = (uint8_t *)map + LDFB_HEADER_BYTES;

    ctx->out_header[LDFB_OFF_STATUS] = LDFB_STATUS_WRITING;
    ctx->out_header[LDFB_OFF_MAGIC] = LDFB_MAGIC;
    ctx->out_header[LDFB_OFF_VERSION] = LDFB_VERSION;
    ctx->out_header[LDFB_OFF_WIDTH] = (uint32_t)ctx->width;
    ctx->out_header[LDFB_OFF_HEIGHT] = (uint32_t)ctx->height;
    ctx->out_header[LDFB_OFF_STRIDE] = (uint32_t)ctx->stride;
    ctx->out_header[LDFB_OFF_FORMAT] = ctx->drm_format;

    fprintf(stderr, "linuxdroid-capture: output %dx%d stride=%d format=0x%08x -> %s\n",
            ctx->width, ctx->height, ctx->stride, ctx->drm_format, ctx->out_path);
    return true;
}

static void publish_frame(struct context *ctx)
{
    if (!ctx->out_pixels || !ctx->buffer_data)
        return;

    size_t bytes = (size_t)ctx->stride * (size_t)ctx->height;

    ctx->out_header[LDFB_OFF_STATUS] = LDFB_STATUS_WRITING;
    /* Ensure the reader sees WRITING before the pixels start changing. */
    __atomic_thread_fence(__ATOMIC_RELEASE);

    memcpy(ctx->out_pixels, ctx->buffer_data, bytes);

    ctx->sequence++;
    /* Publish the pixels before the sequence and the READY flag. */
    __atomic_thread_fence(__ATOMIC_RELEASE);
    ctx->out_header[LDFB_OFF_SEQUENCE] = ctx->sequence;
    ctx->out_header[LDFB_OFF_STATUS] = LDFB_STATUS_READY;
}

/* ── weston_capture_source_v1 ──────────────────────────────────────────── */

static void request_capture(struct context *ctx);

static void source_format(void *data, struct weston_capture_source_v1 *src,
                          uint32_t drm_format)
{
    (void)src;
    struct context *ctx = data;
    if (ctx->drm_format != drm_format) {
        ctx->drm_format = drm_format;
        ctx->needs_realloc = true;
    }
    ctx->have_format = true;
}

static void source_size(void *data, struct weston_capture_source_v1 *src,
                        int32_t width, int32_t height)
{
    (void)src;
    struct context *ctx = data;
    if (width <= 0 || height <= 0) {
        fprintf(stderr, "linuxdroid-capture: invalid capture size %dx%d\n", width, height);
        ctx->failed = true;
        ctx->running = false;
        return;
    }
    if (ctx->width != width || ctx->height != height) {
        ctx->width = width;
        ctx->height = height;
        ctx->needs_realloc = true;
    }
    ctx->have_size = true;
}

static void source_complete(void *data, struct weston_capture_source_v1 *src)
{
    (void)src;
    struct context *ctx = data;
    ctx->capture_pending = false;
    publish_frame(ctx);
}

static void source_retry(void *data, struct weston_capture_source_v1 *src)
{
    (void)src;
    struct context *ctx = data;
    /* Weston already sent updated size/format events; rebuild and retry. */
    ctx->capture_pending = false;
    ctx->needs_realloc = true;
}

static void source_failed(void *data, struct weston_capture_source_v1 *src,
                          const char *msg)
{
    (void)src;
    struct context *ctx = data;
    ctx->capture_pending = false;
    fprintf(stderr, "linuxdroid-capture: capture failed: %s\n", msg ? msg : "(no detail)");
    /* Not fatal on its own — a transient failure should not kill the session,
     * so back off and let the loop retry. */
}

static const struct weston_capture_source_v1_listener source_listener = {
    .format = source_format,
    .size = source_size,
    .complete = source_complete,
    .retry = source_retry,
    .failed = source_failed,
};

static void request_capture(struct context *ctx)
{
    if (ctx->capture_pending || !ctx->buffer)
        return;
    weston_capture_source_v1_capture(ctx->source, ctx->buffer);
    ctx->capture_pending = true;
}

/* ── registry ──────────────────────────────────────────────────────────── */

static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
                            const char *interface, uint32_t version)
{
    struct context *ctx = data;
    if (strcmp(interface, wl_shm_interface.name) == 0) {
        ctx->shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
    } else if (strcmp(interface, wl_output_interface.name) == 0) {
        if (!ctx->output)
            ctx->output = wl_registry_bind(registry, name, &wl_output_interface,
                                           version < 2 ? version : 2);
    } else if (strcmp(interface, weston_capture_v1_interface.name) == 0) {
        ctx->capture = wl_registry_bind(registry, name, &weston_capture_v1_interface, 1);
    }
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name)
{
    (void)data;
    (void)registry;
    (void)name;
}

static const struct wl_registry_listener registry_listener = {
    .global = registry_global,
    .global_remove = registry_global_remove,
};

/* ── main ──────────────────────────────────────────────────────────────── */

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: linuxdroid-capture <frame-buffer-path> [fps]\n");
        return 2;
    }

    struct context ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.buffer_fd = -1;
    ctx.out_path = argv[1];
    ctx.running = true;

    long fps = (argc > 2) ? strtol(argv[2], NULL, 10) : 60;
    if (fps <= 0 || fps > 240)
        fps = 60;
    const long frame_ns = 1000000000L / fps;

    ctx.display = wl_display_connect(NULL);
    if (!ctx.display)
        die("cannot connect to the Wayland display");

    ctx.registry = wl_display_get_registry(ctx.display);
    wl_registry_add_listener(ctx.registry, &registry_listener, &ctx);
    wl_display_roundtrip(ctx.display);

    if (!ctx.shm) {
        fprintf(stderr, "linuxdroid-capture: compositor does not expose wl_shm\n");
        return 1;
    }
    if (!ctx.capture) {
        fprintf(stderr,
                "linuxdroid-capture: compositor does not expose weston_capture_v1; "
                "this build of Weston cannot be captured\n");
        return 1;
    }
    if (!ctx.output) {
        fprintf(stderr, "linuxdroid-capture: no wl_output to capture\n");
        return 1;
    }

    /* 'framebuffer' is the always-available source: it copies the final
     * composited framebuffer. 'writeback' needs DRM KMS hardware we do not
     * have here. */
    ctx.source = weston_capture_v1_create(ctx.capture, ctx.output,
                                          WESTON_CAPTURE_V1_SOURCE_FRAMEBUFFER);
    if (!ctx.source) {
        fprintf(stderr, "linuxdroid-capture: cannot create a capture source\n");
        return 1;
    }
    weston_capture_source_v1_add_listener(ctx.source, &source_listener, &ctx);

    /* The initial size/format events arrive on this roundtrip. */
    wl_display_roundtrip(ctx.display);

    if (!ctx.have_format || !ctx.have_size) {
        fprintf(stderr,
                "linuxdroid-capture: compositor never reported a capture "
                "format/size; the output may not be capturable\n");
        return 1;
    }

    while (ctx.running) {
        if (ctx.needs_realloc) {
            if (!alloc_capture_buffer(&ctx)) {
                fprintf(stderr, "linuxdroid-capture: capture buffer allocation failed\n");
                return 1;
            }
            if (!remap_output_file(&ctx)) {
                fprintf(stderr, "linuxdroid-capture: frame buffer file mapping failed\n");
                return 1;
            }
            ctx.needs_realloc = false;
        }

        request_capture(&ctx);

        if (wl_display_dispatch(ctx.display) < 0) {
            fprintf(stderr, "linuxdroid-capture: display disconnected\n");
            break;
        }

        struct timespec ts = { .tv_sec = 0, .tv_nsec = frame_ns };
        nanosleep(&ts, NULL);
    }

    destroy_capture_buffer(&ctx);
    if (ctx.out_header)
        munmap((void *)ctx.out_header, ctx.out_size);
    wl_display_disconnect(ctx.display);
    return ctx.failed ? 1 : 0;
}
