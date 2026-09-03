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
 * FREE -> ACQUIRED -> LOCKED -> SUBMITTED -> RELEASED -> FREE
 */
typedef enum {
    LINUXDROID_BUFFER_STATE_FREE = 0,
    LINUXDROID_BUFFER_STATE_ACQUIRED = 1,
    LINUXDROID_BUFFER_STATE_LOCKED = 2,
    LINUXDROID_BUFFER_STATE_SUBMITTED = 3,
    LINUXDROID_BUFFER_STATE_RELEASED = 4
} LinuxDroidBufferState;

/**
 * Information on an allocated hardware buffer slot.
 */
typedef struct {
    int index;
    struct AHardwareBuffer* buffer;
    LinuxDroidBufferState state;
    int release_fence_fd;
    void* mapped_address;
    int32_t stride_bytes;
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
 * Locks an ACQUIRED buffer for CPU write access.
 * Maps the buffer into process virtual address space and returns pointer and stride.
 * Transitions slot state from ACQUIRED to LOCKED.
 *
 * @param pres Presentation handle.
 * @param slot_index The slot index returned by acquire_buffer.
 * @param out_pixels Output pointer to the mapped pixel memory.
 * @param out_stride_bytes Output pointer to stride in bytes.
 * @return 0 on success, negative on failure.
 */
int android_presentation_lock_buffer(android_presentation_t* pres,
                                     int slot_index,
                                     void** out_pixels,
                                     int32_t* out_stride_bytes);

/**
 * Unlocks a LOCKED buffer, flushing CPU caches and preparing it for submission.
 * Transitions slot state from LOCKED back to ACQUIRED.
 *
 * @param pres Presentation handle.
 * @param slot_index The slot index.
 * @param out_release_fence Output pointer to sync fence (-1 if CPU flush synchronous).
 * @return 0 on success, negative on failure.
 */
int android_presentation_unlock_buffer(android_presentation_t* pres,
                                       int slot_index,
                                       int* out_release_fence);

/**
 * Returns the AHardwareBuffer pointer for a given slot index.
 */
struct AHardwareBuffer* android_presentation_get_buffer(android_presentation_t* pres,
                                                        int slot_index);

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

