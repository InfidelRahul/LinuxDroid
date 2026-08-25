package com.linuxdroid.core.model

/**
 * Event emitted when a managed Linux process changes state.
 *
 * Placed in core-model to break the circular dependency that would arise
 * if core-runtime and core-process both needed to reference this type.
 */
sealed class ProcessStateEvent {
    abstract val handleId: String

    data class Started(override val handleId: String, val pid: Int) : ProcessStateEvent()
    data class Exited(override val handleId: String, val exitCode: Int) : ProcessStateEvent()
    data class Signaled(override val handleId: String, val signal: Int) : ProcessStateEvent()
    data class Failed(override val handleId: String, val error: String) : ProcessStateEvent()
}
