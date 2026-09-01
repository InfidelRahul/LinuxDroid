#pragma once

#include <mutex>

namespace linuxdroid {

/**
 * WestonHost — native libweston compositor host.
 *
 * This is the native C++ layer that owns the embedded libweston compositor
 * instance. It sits directly under the GuiHost lifecycle boundary and, once
 * started, runs a minimal libweston compositor with the LinuxDroid custom
 * Android backend:
 *
 *   GuiHost (SurfaceView) -> WestonHost -> libweston 16.0.0 -> linuxdroid backend
 *
 * Lifecycle:
 *   start()  - create + configure the compositor, register the backend, start
 *              the libweston event loop on a dedicated worker thread.
 *   stop()   - terminate + join the event loop, destroy the compositor, and
 *              release all native resources. Idempotent and safe to call
 *              repeatedly; a stopped host can be started again.
 *
 * Threading note: the worker thread exists purely to run libweston's own event
 * loop (Wayland socket dispatch). It is NOT a rendering/frame thread and there
 * is no frame scheduler. That belongs to a later phase.
 *
 * Compile modes:
 *   - When LINUXDROID_HAS_LIBWESTON is defined, the real libweston integration
 *     in weston_host.cpp is compiled and linked against the pinned libweston
 *     16.0.0 (see native/weston/).
 *   - Otherwise weston_host.cpp compiles to a no-libweston fallback that logs a
 *     "libweston not built" message and never starts the compositor. This keeps
 *     the default Android build (which has no libweston artifacts) green.
 */
class WestonHost {
public:
    static WestonHost& getInstance();

    /**
     * Initialize and start the embedded libweston compositor.
     * Returns true if the compositor is running, false on failure.
     * The call is synchronous for initialization; the event loop runs on the
     * worker thread after this returns.
     */
    bool start();

    /**
     * Shut down the compositor cleanly and release all native resources.
     * Safe to call repeatedly and when not running. Does not throw.
     */
    void stop();

    /** True while the compositor is running (and not being torn down). */
    bool isRunning() const;

private:
    WestonHost();
    ~WestonHost();

    WestonHost(const WestonHost&) = delete;
    WestonHost& operator=(const WestonHost&) = delete;

    // Implementation detail selected by LINUXDROID_HAS_LIBWESTON (in .cpp).
    bool startImpl();
    void stopImpl();

    mutable std::mutex mutex_;
    bool running_ = false;
    // Opaque pointer to the compositor state owned by the real implementation.
    // Held as void* so the header does not depend on libweston types. Null when
    // no compositor is live.
    void* impl_ = nullptr;
};

} // namespace linuxdroid
