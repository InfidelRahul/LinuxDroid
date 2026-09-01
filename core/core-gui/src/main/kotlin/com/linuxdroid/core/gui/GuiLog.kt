package com.linuxdroid.core.gui

import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.EnvironmentId
import java.io.File
import java.io.IOException

/**
 * GUI log categories. Each maps to its own file so that GUI diagnostics stay
 * separated from the general runtime logs (console.log / proot.log).
 */
enum class GuiLogCategory(val fileName: String, val subsystem: LogSubsystem) {
    GUI("gui.log", LogSubsystem.GUI),
    GRAPHICS("graphics.log", LogSubsystem.GRAPHICS),
    INPUT("input.log", LogSubsystem.INPUT),
    WAYLAND("wayland.log", LogSubsystem.WAYLAND),
    COMPOSITOR("compositor.log", LogSubsystem.COMPOSITOR),
    SESSION("session.log", LogSubsystem.SESSION),
}

/**
 * Writes GUI events to the structured logger and to a per-category file.
 *
 * This is deliberately thin: it reuses [LinuxDroidLogger] for structure and
 * [EnvironmentStorage] for path resolution instead of re-implementing either.
 */
interface GuiLog {
    fun info(category: GuiLogCategory, message: String)
    fun warn(category: GuiLogCategory, message: String, throwable: Throwable? = null)
    fun error(category: GuiLogCategory, message: String, throwable: Throwable? = null)

    /** Records a failure. Implementations must never downgrade this to info. */
    fun failure(category: GuiLogCategory, failure: GuiFailure)
}

/**
 * Default [GuiLog] writing under `<environment>/logs/gui/<category>.log`.
 *
 * File-write problems are reported through the structured logger and never
 * mask the event being logged.
 */
class FileGuiLog(
    private val environmentId: EnvironmentId,
    private val storage: EnvironmentStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) : GuiLog {

    /** Directory holding the GUI-specific log files. */
    fun guiLogDir(): File = File(storage.logsDir(environmentId), "gui")

    fun logFile(category: GuiLogCategory): File = File(guiLogDir(), category.fileName)

    override fun info(category: GuiLogCategory, message: String) {
        logger(category).info(message)
        append(category, "I", message, null)
    }

    override fun warn(category: GuiLogCategory, message: String, throwable: Throwable?) {
        logger(category).warn(message, throwable)
        append(category, "W", message, throwable)
    }

    override fun error(category: GuiLogCategory, message: String, throwable: Throwable?) {
        logger(category).error(message, throwable)
        append(category, "E", message, throwable)
    }

    override fun failure(category: GuiLogCategory, failure: GuiFailure) {
        error(category, failure.describe(), failure.cause)
    }

    private fun logger(category: GuiLogCategory) =
        LinuxDroidLogger(category.subsystem, environmentId)

    private fun append(category: GuiLogCategory, level: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(clock()).append(' ').append(level).append(' ')
            append('[').append(category.name).append("] ").append(message)
            throwable?.let { append(" | ").append(it.javaClass.simpleName).append(": ").append(it.message) }
            append('\n')
        }
        try {
            val dir = guiLogDir()
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("Cannot create GUI log directory: ${dir.path}")
            }
            logFile(category).appendText(line)
        } catch (e: IOException) {
            LinuxDroidLogger(LogSubsystem.GUI, environmentId)
                .warn("Failed to persist ${category.fileName} entry", e)
        }
    }
}
