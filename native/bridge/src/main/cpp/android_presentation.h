#ifndef LINUXDROID_ANDROID_PRESENTATION_H
#define LINUXDROID_ANDROID_PRESENTATION_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

struct ANativeWindow;
struct ASurfaceControl;
struct ASurfaceTransaction;
struct AHardwareBuffer;

#define LINUXDROID_BUFFER_POOL_CAPACITY 3
#define LINUXDROID_DEFAULT_WIDTH 1920
#define LINUXDROID_DEFAULT_HEIGHT 1080

/**
 * Explicit buffer lifecycle states:
 * FREE -> ACQUIRED -> SUBMITTED -> RELEASED -> FREE
 */
typedef enum {
    LINUXDROID_BUFFER_STATE_FREE = 0,
    LINUXDROID_BUFFER_STATE_ACQUIRED = 1,
    LINUXDROID_BUFFER_STATE_SUBMITTED = 2,
    LINUXDROID_BUFFER_STATE_RELEASED = 3
} LinuxDroidBufferState;

/**
 * Information on an allocated hardware buffer slot.
 */
typedef struct {
    int index;
    struct AHardwareBuffer* buffer;
    LinuxDroidBufferState state;
    int release_fence_fd;
} LinuxDroidBufferSlot;

/**
 * Opaque Android presentation backend handle.
 */
typedef struct android_presentation android_presentation_t;

/**
 * Creates a new Android presentation backend handle for an output.
 */
android_presentation_t* android_presentation_create(void);

/**
 * Destroys an Android presentation backend handle and frees all resources.
 */
void android_presentation_destroy(android_presentation_t* pres);

/**
 * Enables Android presentation on the specified native window with given dimensions.
 * Creates ASurfaceControl, configures layer, and allocates AHardwareBuffer pool.
 *
 * @return 0 on success, negative errno on failure.
 */
int android_presentation_enable(android_presentation_t* pres,
                                struct ANativeWindow* window,
                                int32_t width,
                                int32_t height);

/**
 * Disables Android presentation: stops submissions, drains in-flight buffers,
 * hides/destroys ASurfaceControl, and frees buffer pool.
 */
void android_presentation_disable(android_presentation_t* pres);

/**
 * Returns true if the presentation backend is currently enabled and ready.
 */
bool android_presentation_is_enabled(const android_presentation_t* pres);

/**
 * Resizes the presentation target to new dimensions without corrupting buffers.
 * Drains outstanding buffers, frees obsolete buffers, resizes SurfaceControl,
 * and reallocates the buffer pool.
 *
 * @return 0 on success, negative errno on failure.
 */
int android_presentation_resize(android_presentation_t* pres,
                                int32_t new_width,
                                int32_t new_height);

/**
 * Rebinds to a newly created native window (handles Android surface recreation).
 *
 * @return 0 on success, negative errno on failure.
 */
int android_presentation_set_window(android_presentation_t* pres,
                                    struct ANativeWindow* window);

/**
 * Acquires an available FREE buffer from the pool for rendering/composition.
 * Blocks up to timeout_ms if all buffers are currently in-flight.
 *
 * @param pres Presentation handle.
 * @param out_index Output pointer to slot index.
 * @param out_buffer Output pointer to AHardwareBuffer.
 * @param timeout_ms Maximum time in milliseconds to wait for a free buffer.
 * @return 0 on success, -ETIMEDOUT if none available, negative on error.
 */
int android_presentation_acquire_buffer(android_presentation_t* pres,
                                        int* out_index,
                                        struct AHardwareBuffer** out_buffer,
                                        uint32_t timeout_ms);

/**
 * Submits an ACQUIRED buffer to the Android display subsystem via SurfaceControl transaction.
 *
 * @param pres Presentation handle.
 * @param slot_index The slot index returned by acquire_buffer.
 * @param acquire_fence_fd Sync fence signaling when producer drawing is complete (-1 if already done).
 * @return 0 on success, negative on failure.
 */
int android_presentation_submit_buffer(android_presentation_t* pres,
                                       int slot_index,
                                       int acquire_fence_fd);

/**
 * Waits until all SUBMITTED buffers have been released by Android, up to timeout_ms.
 *
 * @return 0 if all buffers are idle, -ETIMEDOUT on timeout.
 */
int android_presentation_wait_idle(android_presentation_t* pres, uint32_t timeout_ms);

/**
 * Returns the current presentation dimensions.
 */
void android_presentation_get_dimensions(const android_presentation_t* pres,
                                         int32_t* out_width,
                                         int32_t* out_height);

/**
 * Returns buffer pool diagnostic details (allocated count, free count, submitted count).
 */
void android_presentation_get_stats(const android_presentation_t* pres,
                                    int* out_allocated,
                                    int* out_free,
                                    int* out_submitted);

#ifdef __cplusplus
}
#endif

#endif /* LINUXDROID_ANDROID_PRESENTATION_H */

