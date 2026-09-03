#include "android_presentation.h"

#include <android/native_window.h>
#include <android/surface_control.h>
#include <android/hardware_buffer.h>
#include <android/log.h>

#include <dlfcn.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <memory>
#include <chrono>
#include <array>

#define TAG "LinuxDroid/Presentation"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

// Dynamic function pointer signature for Android 16 (API 36+) buffer release
typedef void (*ASurfaceTransaction_setBufferWithRelease_fn)(
    ASurfaceTransaction* transaction,
    ASurfaceControl* surface_control,
    AHardwareBuffer* buffer,
    int acquire_fence_fd,
    void* context,
    ASurfaceTransaction_OnBufferRelease func);

static ASurfaceTransaction_setBufferWithRelease_fn resolveSetBufferWithRelease() {
    static auto fn = reinterpret_cast<ASurfaceTransaction_setBufferWithRelease_fn>(
        dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setBufferWithRelease"));
    return fn;
}

class AndroidPresentationBackend;

/**
 * Lifetime token shared between presentation backend and asynchronous release callbacks.
 * Guarantees zero use-after-free even if Android signals release after backend destruction.
 */
struct PresentationLifetimeToken {
    std::mutex token_mutex;
    AndroidPresentationBackend* backend{nullptr};
    std::atomic<bool> is_alive{true};

    void handleBufferRelease(int slot_index, int release_fence_fd);
};

struct ReleaseCallbackContext {
    std::weak_ptr<PresentationLifetimeToken> token;
    int slot_index{-1};
};

class AndroidPresentationBackend {
public:
    AndroidPresentationBackend() {
        token_ = std::make_shared<PresentationLifetimeToken>();
        token_->backend = this;
        for (int i = 0; i < LINUXDROID_BUFFER_POOL_CAPACITY; ++i) {
            slots_[i].index = i;
            slots_[i].buffer = nullptr;
            slots_[i].state = LINUXDROID_BUFFER_STATE_FREE;
            slots_[i].release_fence_fd = -1;
        }
        LOGI("ANDROID_OUTPUT_CREATE: created Android presentation backend instance");
    }

    ~AndroidPresentationBackend() {
        destroy();
    }

    int enable(ANativeWindow* window, int32_t width, int32_t height) {
        std::unique_lock<std::mutex> lock(mutex_);

        if (is_enabled_) {
            LOGW("ANDROID_OUTPUT_ENABLE: already enabled (%dx%d)", width_, height_);
            return 0;
        }

        if (window == nullptr) {
            LOGE("ANDROID_PRESENTATION_ERROR: enable failed: null ANativeWindow parent");
            return -EINVAL;
        }

        if (width <= 0 || height <= 0) {
            width = LINUXDROID_DEFAULT_WIDTH;
            height = LINUXDROID_DEFAULT_HEIGHT;
        }

        native_window_ = window;
        width_ = width;
        height_ = height;

        // 1. Create child ASurfaceControl attached to the parent ANativeWindow
        surface_control_ = ASurfaceControl_createFromWindow(native_window_, "LinuxDroidOutputLayer");
        if (surface_control_ == nullptr) {
            LOGE("ANDROID_PRESENTATION_ERROR: ASurfaceControl_createFromWindow failed");
            return -ENOMEM;
        }
        LOGI("ANDROID_SURFACECONTROL_CREATE: created ASurfaceControl for window %p (%dx%d)",
             native_window_, width_, height_);

        // 2. Configure initial layer attributes in an atomic transaction
        ASurfaceTransaction* tx = ASurfaceTransaction_create();
        if (tx != nullptr) {
            ASurfaceTransaction_setVisibility(tx, surface_control_, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
            ASurfaceTransaction_setZOrder(tx, surface_control_, 1);
            ASurfaceTransaction_apply(tx);
            ASurfaceTransaction_delete(tx);
        }

        // 3. Allocate AHardwareBuffer pool
        if (!allocateBufferPoolLocked()) {
            LOGE("ANDROID_PRESENTATION_ERROR: failed to allocate AHardwareBuffer pool");
            if (surface_control_ != nullptr) {
                ASurfaceControl_release(surface_control_);
                surface_control_ = nullptr;
            }
            return -ENOMEM;
        }

        is_enabled_ = true;
        LOGI("ANDROID_OUTPUT_ENABLE: output enabled successfully (%dx%d, %d buffers)",
             width_, height_, LINUXDROID_BUFFER_POOL_CAPACITY);
        LOGI("ANDROID_OUTPUT_READY: presentation surface ready for buffer submission");
        return 0;
    }

    void disable() {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!is_enabled_) return;

        LOGI("ANDROID_OUTPUT_DISABLE: disabling presentation output");
        is_enabled_ = false;

        // Drain any submitted in-flight buffers
        drainOutstandingBuffersLocked(lock, 150);

        // Release ASurfaceControl
        if (surface_control_ != nullptr) {
            ASurfaceTransaction* tx = ASurfaceTransaction_create();
            if (tx != nullptr) {
                ASurfaceTransaction_setVisibility(tx, surface_control_, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
                ASurfaceTransaction_apply(tx);
                ASurfaceTransaction_delete(tx);
            }
            ASurfaceControl_release(surface_control_);
            surface_control_ = nullptr;
            LOGI("ANDROID_SURFACECONTROL_DESTROY: released ASurfaceControl");
        }

        // Free hardware buffers
        freeBufferPoolLocked();
        native_window_ = nullptr;
        cv_.notify_all();
    }

    void destroy() {
        {
            std::unique_lock<std::mutex> lock(mutex_);
            if (is_destroyed_) return;
            is_destroyed_ = true;
        }

        disable();

        // Invalidate token so late callbacks drop fences safely
        if (token_) {
            std::lock_guard<std::mutex> lock(token_->token_mutex);
            token_->backend = nullptr;
            token_->is_alive.store(false, std::memory_order_release);
        }
    }

    bool isEnabled() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return is_enabled_ && surface_control_ != nullptr;
    }

    int resize(int32_t new_width, int32_t new_height) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (new_width <= 0 || new_height <= 0) return -EINVAL;
        if (width_ == new_width && height_ == new_height && is_enabled_) return 0;

        LOGI("ANDROID_OUTPUT_RESIZE: resizing from %dx%d to %dx%d",
             width_, height_, new_width, new_height);

        // 1. Drain submitted buffers before reallocation
        drainOutstandingBuffersLocked(lock, 200);

        // 2. Free old buffers
        freeBufferPoolLocked();

        // 3. Update dimensions
        width_ = new_width;
        height_ = new_height;

        // 4. Reallocate buffers at new size if enabled
        if (is_enabled_ && surface_control_ != nullptr) {
            if (!allocateBufferPoolLocked()) {
                LOGE("ANDROID_BUFFER_ALLOCATE_FAILED: resize buffer allocation failed (%dx%d)",
                     width_, height_);
                return -ENOMEM;
            }
        }

        cv_.notify_all();
        return 0;
    }

    int setWindow(ANativeWindow* window) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (native_window_ == window) return 0;

        LOGI("ANDROID_OUTPUT_READY: re-binding native window %p -> %p", native_window_, window);
        native_window_ = window;

        if (is_enabled_) {
            // Recreate ASurfaceControl from new window
            if (surface_control_ != nullptr) {
                ASurfaceControl_release(surface_control_);
                surface_control_ = nullptr;
            }
            if (native_window_ != nullptr) {
                surface_control_ = ASurfaceControl_createFromWindow(native_window_, "LinuxDroidOutputLayer");
                if (surface_control_ != nullptr) {
                    ASurfaceTransaction* tx = ASurfaceTransaction_create();
                    if (tx != nullptr) {
                        ASurfaceTransaction_setVisibility(tx, surface_control_, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
                        ASurfaceTransaction_setZOrder(tx, surface_control_, 1);
                        ASurfaceTransaction_apply(tx);
                        ASurfaceTransaction_delete(tx);
                    }
                    LOGI("ANDROID_SURFACECONTROL_CREATE: recreated ASurfaceControl for new window");
                }
            }
        }
        return 0;
    }

    int acquireBuffer(int* out_index, AHardwareBuffer** out_buffer, uint32_t timeout_ms) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!is_enabled_ || surface_control_ == nullptr) {
            return -ENODEV;
        }

        auto start = std::chrono::steady_clock::now();
        while (true) {
            for (int i = 0; i < LINUXDROID_BUFFER_POOL_CAPACITY; ++i) {
                if (slots_[i].state == LINUXDROID_BUFFER_STATE_FREE && slots_[i].buffer != nullptr) {
                    slots_[i].state = LINUXDROID_BUFFER_STATE_ACQUIRED;
                    if (out_index) *out_index = i;
                    if (out_buffer) *out_buffer = slots_[i].buffer;
                    return 0;
                }
            }

            if (timeout_ms == 0) return -ETIMEDOUT;

            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start).count();
            if (elapsed >= timeout_ms) return -ETIMEDOUT;

            uint32_t remaining = timeout_ms - static_cast<uint32_t>(elapsed);
            if (cv_.wait_for(lock, std::chrono::milliseconds(remaining)) == std::cv_status::timeout) {
                return -ETIMEDOUT;
            }
            if (!is_enabled_) return -ESHUTDOWN;
        }
    }

    int lockBuffer(int slot_index, void** out_pixels, int32_t* out_stride_bytes) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!is_enabled_ || slot_index < 0 || slot_index >= LINUXDROID_BUFFER_POOL_CAPACITY) {
            LOGE("PIXMAN_LOCK_FAILURE: invalid presentation state or slot index %d", slot_index);
            return -EINVAL;
        }

        LinuxDroidBufferSlot& slot = slots_[slot_index];
        if (slot.state != LINUXDROID_BUFFER_STATE_ACQUIRED || slot.buffer == nullptr) {
            LOGE("PIXMAN_LOCK_FAILURE: slot %d not in ACQUIRED state (state=%d)", slot_index, slot.state);
            return -EINVAL;
        }

        void* virtual_addr = nullptr;
        int err = AHardwareBuffer_lock(slot.buffer,
                                       AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                                       -1,
                                       nullptr,
                                       &virtual_addr);
        if (err != 0 || virtual_addr == nullptr) {
            LOGE("PIXMAN_LOCK_FAILURE: AHardwareBuffer_lock failed on slot %d: %d", slot_index, err);
            return (err != 0) ? err : -EIO;
        }

        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(slot.buffer, &desc);
        int32_t stride_bytes = static_cast<int32_t>(desc.stride * 4);

        slot.mapped_address = virtual_addr;
        slot.stride_bytes = stride_bytes;
        slot.state = LINUXDROID_BUFFER_STATE_LOCKED;

        if (out_pixels) *out_pixels = virtual_addr;
        if (out_stride_bytes) *out_stride_bytes = stride_bytes;

        LOGI("PIXMAN_BUFFER_LOCK: slot=%d, mapped=%p, stride=%d bytes (%d px)",
             slot_index, virtual_addr, stride_bytes, desc.stride);
        return 0;
    }

    int unlockBuffer(int slot_index, int* out_release_fence) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (slot_index < 0 || slot_index >= LINUXDROID_BUFFER_POOL_CAPACITY) {
            return -EINVAL;
        }

        LinuxDroidBufferSlot& slot = slots_[slot_index];
        if (slot.state != LINUXDROID_BUFFER_STATE_LOCKED || slot.buffer == nullptr) {
            LOGE("PIXMAN_UNLOCK_FAILURE: slot %d not in LOCKED state (state=%d)", slot_index, slot.state);
            return -EINVAL;
        }

        int fence_fd = -1;
        int err = AHardwareBuffer_unlock(slot.buffer, &fence_fd);
        if (err != 0) {
            LOGE("PIXMAN_UNLOCK_FAILURE: AHardwareBuffer_unlock failed on slot %d: %d", slot_index, err);
            return err;
        }

        slot.mapped_address = nullptr;
        slot.state = LINUXDROID_BUFFER_STATE_ACQUIRED;
        if (out_release_fence) {
            *out_release_fence = fence_fd;
        } else if (fence_fd >= 0) {
            close(fence_fd);
        }

        LOGI("PIXMAN_BUFFER_UNLOCK: slot=%d unlocked, fence=%d", slot_index, fence_fd);
        return 0;
    }

    AHardwareBuffer* getBuffer(int slot_index) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (slot_index < 0 || slot_index >= LINUXDROID_BUFFER_POOL_CAPACITY) {
            return nullptr;
        }
        return slots_[slot_index].buffer;
    }

    int submitBuffer(int slot_index, int acquire_fence_fd) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!is_enabled_ || surface_control_ == nullptr) {
            if (acquire_fence_fd >= 0) close(acquire_fence_fd);
            return -ENODEV;
        }

        if (slot_index < 0 || slot_index >= LINUXDROID_BUFFER_POOL_CAPACITY) {
            if (acquire_fence_fd >= 0) close(acquire_fence_fd);
            return -EINVAL;
        }

        LinuxDroidBufferSlot& slot = slots_[slot_index];
        if (slot.state != LINUXDROID_BUFFER_STATE_ACQUIRED || slot.buffer == nullptr) {
            if (acquire_fence_fd >= 0) close(acquire_fence_fd);
            return -EINVAL;
        }

        ASurfaceTransaction* tx = ASurfaceTransaction_create();
        if (tx == nullptr) {
            if (acquire_fence_fd >= 0) close(acquire_fence_fd);
            LOGE("ANDROID_PRESENTATION_ERROR: ASurfaceTransaction_create failed");
            return -ENOMEM;
        }

        auto* cbCtx = new ReleaseCallbackContext();
        cbCtx->token = token_;
        cbCtx->slot_index = slot_index;

        slot.state = LINUXDROID_BUFFER_STATE_SUBMITTED;
        submitted_count_++;

        auto setBufferWithReleaseFn = resolveSetBufferWithRelease();
        if (setBufferWithReleaseFn != nullptr) {
            // Android 16 (API 36+) zero-copy buffer release notification
            setBufferWithReleaseFn(
                tx,
                surface_control_,
                slot.buffer,
                acquire_fence_fd,
                cbCtx,
                [](void* context, int release_fence_fd) {
                    auto* c = static_cast<ReleaseCallbackContext*>(context);
                    if (!c) {
                        if (release_fence_fd >= 0) close(release_fence_fd);
                        return;
                    }
                    auto tok = c->token.lock();
                    if (tok) {
                        tok->handleBufferRelease(c->slot_index, release_fence_fd);
                    } else {
                        if (release_fence_fd >= 0) close(release_fence_fd);
                    }
                    delete c;
                }
            );
        } else {
            // Graceful fallback: setBuffer + onComplete
            ASurfaceTransaction_setBuffer(tx, surface_control_, slot.buffer, acquire_fence_fd);
            ASurfaceTransaction_setOnComplete(
                tx,
                cbCtx,
                [](void* context, [[maybe_unused]] ASurfaceTransactionStats* stats) {
                    auto* c = static_cast<ReleaseCallbackContext*>(context);
                    if (!c) return;
                    auto tok = c->token.lock();
                    if (tok) {
                        tok->handleBufferRelease(c->slot_index, -1);
                    }
                    delete c;
                }
            );
        }

        ASurfaceTransaction_apply(tx);
        ASurfaceTransaction_delete(tx);

        LOGI("ANDROID_BUFFER_SUBMIT: slot=%d, buffer=%p (%dx%d, in-flight=%d)",
             slot_index, slot.buffer, width_, height_, submitted_count_);
        return 0;
    }

    void onBufferReleasedInternal(int slot_index, int release_fence_fd) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (slot_index >= 0 && slot_index < LINUXDROID_BUFFER_POOL_CAPACITY) {
            LinuxDroidBufferSlot& slot = slots_[slot_index];
            slot.state = LINUXDROID_BUFFER_STATE_FREE;
            slot.release_fence_fd = -1;
            if (submitted_count_ > 0) {
                submitted_count_--;
            }
            LOGI("ANDROID_BUFFER_RELEASE: slot=%d released by Android (in-flight=%d)",
                 slot_index, submitted_count_);
        }
        if (release_fence_fd >= 0) {
            close(release_fence_fd);
        }
        cv_.notify_all();
    }

    int waitIdle(uint32_t timeout_ms) {
        std::unique_lock<std::mutex> lock(mutex_);
        return drainOutstandingBuffersLocked(lock, timeout_ms);
    }

    void getDimensions(int32_t* out_w, int32_t* out_h) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (out_w) *out_w = width_;
        if (out_h) *out_h = height_;
    }

    void getStats(int* out_allocated, int* out_free, int* out_submitted) const {
        std::lock_guard<std::mutex> lock(mutex_);
        int allocated = 0, freeCount = 0;
        for (int i = 0; i < LINUXDROID_BUFFER_POOL_CAPACITY; ++i) {
            if (slots_[i].buffer != nullptr) {
                allocated++;
                if (slots_[i].state == LINUXDROID_BUFFER_STATE_FREE) {
                    freeCount++;
                }
            }
        }
        if (out_allocated) *out_allocated = allocated;
        if (out_free) *out_free = freeCount;
        if (out_submitted) *out_submitted = submitted_count_;
    }

private:
    bool allocateBufferPoolLocked() {
        AHardwareBuffer_Desc desc{};
        desc.width = static_cast<uint32_t>(width_);
        desc.height = static_cast<uint32_t>(height_);
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                     AHARDWAREBUFFER_USAGE_CPU_READ_NEVER |
                     AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;

        for (int i = 0; i < LINUXDROID_BUFFER_POOL_CAPACITY; ++i) {
            AHardwareBuffer* buf = nullptr;
            int err = AHardwareBuffer_allocate(&desc, &buf);
            if (err != 0 || buf == nullptr) {
                LOGE("ANDROID_BUFFER_ALLOCATE_FAILED: slot %d allocation failed (%dx%d, err=%d)",
                     i, width_, height_, err);
                freeBufferPoolLocked();
                return false;
            }
            slots_[i].index = i;
            slots_[i].buffer = buf;
            slots_[i].state = LINUXDROID_BUFFER_STATE_FREE;
            slots_[i].release_fence_fd = -1;
            LOGI("ANDROID_BUFFER_ALLOCATE: slot %d allocated (%p, %dx%d)", i, buf, width_, height_);
        }
        return true;
    }

    void freeBufferPoolLocked() {
        for (int i = 0; i < LINUXDROID_BUFFER_POOL_CAPACITY; ++i) {
            if (slots_[i].buffer != nullptr) {
                if (slots_[i].state == LINUXDROID_BUFFER_STATE_LOCKED) {
                    int fence = -1;
                    AHardwareBuffer_unlock(slots_[i].buffer, &fence);
                    if (fence >= 0) close(fence);
                    slots_[i].mapped_address = nullptr;
                }
                AHardwareBuffer_release(slots_[i].buffer);
                slots_[i].buffer = nullptr;
            }
            slots_[i].state = LINUXDROID_BUFFER_STATE_FREE;
            slots_[i].mapped_address = nullptr;
            slots_[i].stride_bytes = 0;
            if (slots_[i].release_fence_fd >= 0) {
                close(slots_[i].release_fence_fd);
                slots_[i].release_fence_fd = -1;
            }
        }
        submitted_count_ = 0;
    }

    int drainOutstandingBuffersLocked(std::unique_lock<std::mutex>& lock, uint32_t timeout_ms) {
        if (submitted_count_ == 0) return 0;
        auto start = std::chrono::steady_clock::now();
        while (submitted_count_ > 0) {
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start).count();
            if (elapsed >= timeout_ms) {
                LOGW("ANDROID_PRESENTATION_ERROR: drain timeout with %d buffers in-flight", submitted_count_);
                return -ETIMEDOUT;
            }
            uint32_t remaining = timeout_ms - static_cast<uint32_t>(elapsed);
            if (cv_.wait_for(lock, std::chrono::milliseconds(remaining)) == std::cv_status::timeout) {
                return -ETIMEDOUT;
            }
        }
        return 0;
    }

    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::shared_ptr<PresentationLifetimeToken> token_;

    ANativeWindow* native_window_{nullptr};
    ASurfaceControl* surface_control_{nullptr};

    int32_t width_{LINUXDROID_DEFAULT_WIDTH};
    int32_t height_{LINUXDROID_DEFAULT_HEIGHT};

    bool is_enabled_{false};
    bool is_destroyed_{false};
    int submitted_count_{0};

    std::array<LinuxDroidBufferSlot, LINUXDROID_BUFFER_POOL_CAPACITY> slots_{};
};

void PresentationLifetimeToken::handleBufferRelease(int slot_index, int release_fence_fd) {
    std::lock_guard<std::mutex> lock(token_mutex);
    if (is_alive.load(std::memory_order_acquire) && backend != nullptr) {
        backend->onBufferReleasedInternal(slot_index, release_fence_fd);
    } else {
        if (release_fence_fd >= 0) {
            close(release_fence_fd);
        }
    }
}

} // namespace linuxdroid

// ─── C ABI Implementation for struct android_presentation ──────────────────

struct android_presentation {
    std::unique_ptr<linuxdroid::AndroidPresentationBackend> impl;
};

extern "C" {

android_presentation_t* android_presentation_create(void) {
    auto* pres = new android_presentation();
    pres->impl = std::make_unique<linuxdroid::AndroidPresentationBackend>();
    return pres;
}

void android_presentation_destroy(android_presentation_t* pres) {
    if (pres) {
        if (pres->impl) {
            pres->impl->destroy();
        }
        delete pres;
    }
}

int android_presentation_enable(android_presentation_t* pres,
                                struct ANativeWindow* window,
                                int32_t width,
                                int32_t height) {
    if (!pres || !pres->impl) return -EINVAL;
    return pres->impl->enable(window, width, height);
}

void android_presentation_disable(android_presentation_t* pres) {
    if (pres && pres->impl) {
        pres->impl->disable();
    }
}

bool android_presentation_is_enabled(const android_presentation_t* pres) {
    if (!pres || !pres->impl) return false;
    return pres->impl->isEnabled();
}

int android_presentation_resize(android_presentation_t* pres,
                                int32_t new_width,
                                int32_t new_height) {
    if (!pres || !pres->impl) return -EINVAL;
    return pres->impl->resize(new_width, new_height);
}

int android_presentation_set_window(android_presentation_t* pres,
                                    struct ANativeWindow* window) {
    if (!pres || !pres->impl) return -EINVAL;
    return pres->impl->setWindow(window);
}

int android_presentation_acquire_buffer(android_presentation_t* pres,
                                        int* out_index,
                                        struct AHardwareBuffer** out_buffer,
                                        uint32_t timeout_ms) {
    if (!pres || !pres->impl) return -EINVAL;
    return pres->impl->acquireBuffer(out_index, out_buffer, timeout_ms);
}

int android_presentation_lock_buffer(android_presentation_t* pres,
                                     int slot_index,
                                     void** out_pixels,
                                     int32_t* out_stride_bytes) {
    if (!pres || !pres->impl) return -EINVAL;
    return pres->impl->lockBuffer(slot_index, out_pixels, out_stride_bytes);
}

int android_presentation_unlock_buffer(android_presentation_t* pres,
                                       int slot_index,
                                       int* out_release_fence) {
    if (!pres || !pres->impl) return -EINVAL;
    return pres->impl->unlockBuffer(slot_index, out_release_fence);
}

struct AHardwareBuffer* android_presentation_get_buffer(android_presentation_t* pres,
                                                        int slot_index) {
    if (!pres || !pres->impl) return nullptr;
    return pres->impl->getBuffer(slot_index);
}

int android_presentation_submit_buffer(android_presentation_t* pres,
                                       int slot_index,
                                       int acquire_fence_fd) {
    if (!pres || !pres->impl) {
        if (acquire_fence_fd >= 0) close(acquire_fence_fd);
        return -EINVAL;
    }
    return pres->impl->submitBuffer(slot_index, acquire_fence_fd);
}

int android_presentation_wait_idle(android_presentation_t* pres, uint32_t timeout_ms) {
    if (!pres || !pres->impl) return 0;
    return pres->impl->waitIdle(timeout_ms);
}

void android_presentation_get_dimensions(const android_presentation_t* pres,
                                         int32_t* out_width,
                                         int32_t* out_height) {
    if (pres && pres->impl) {
        pres->impl->getDimensions(out_width, out_height);
    } else {
        if (out_width) *out_width = 0;
        if (out_height) *out_height = 0;
    }
}

void android_presentation_get_stats(const android_presentation_t* pres,
                                    int* out_allocated,
                                    int* out_free,
                                    int* out_submitted) {
    if (pres && pres->impl) {
        pres->impl->getStats(out_allocated, out_free, out_submitted);
    } else {
        if (out_allocated) *out_allocated = 0;
        if (out_free) *out_free = 0;
        if (out_submitted) *out_submitted = 0;
    }
}

} // extern "C"

