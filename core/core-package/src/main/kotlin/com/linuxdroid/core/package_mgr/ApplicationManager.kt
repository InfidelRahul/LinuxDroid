package com.linuxdroid.core.package_mgr

import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class LinuxDesktopApp(
    val id: String,
    val name: String,
    val executable: String,
    val comment: String = "",
    val iconName: String = "",
    val categories: List<String> = emptyList(),
    val isTerminal: Boolean = false,
)

interface ApplicationManager {
    suspend fun discoverApplications(environment: Environment): List<LinuxDesktopApp>
    suspend fun parseDesktopFile(file: File): LinuxDesktopApp?
}

class DefaultApplicationManager(
    private val storage: EnvironmentStorage,
) : ApplicationManager {

    private val log = LinuxDroidLogger(LogSubsystem.APPLICATION)

    override suspend fun discoverApplications(environment: Environment): List<LinuxDesktopApp> = withContext(Dispatchers.IO) {
        val rootfs = storage.rootfsDir(environment.id)
        if (!rootfs.exists()) return@withContext emptyList()

        val appDirs = listOf(
            File(rootfs, "usr/share/applications"),
            File(rootfs, "usr/local/share/applications"),
            File(rootfs, "home/user/.local/share/applications")
        )

        val discovered = mutableListOf<LinuxDesktopApp>()
        for (dir in appDirs) {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles()?.filter { it.name.endsWith(".desktop") } ?: emptyList()
                for (desktopFile in files) {
                    parseDesktopFile(desktopFile)?.let { discovered.add(it) }
                }
            }
        }
        log.info("Discovered ${discovered.size} Linux desktop applications in ${environment.id}")
        discovered
    }

    override suspend fun parseDesktopFile(file: File): LinuxDesktopApp? = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) return@withContext null
        try {
            var inDesktopEntry = false
            var name = ""
            var exec = ""
            var comment = ""
            var icon = ""
            var categories = emptyList<String>()
            var terminal = false
            var noDisplay = false

            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inDesktopEntry = trimmed == "[Desktop Entry]"
                } else if (inDesktopEntry && trimmed.contains("=")) {
                    val key = trimmed.substringBefore("=").trim()
                    val value = trimmed.substringAfter("=").trim()
                    when (key) {
                        "Name" -> if (name.isEmpty()) name = value
                        "Exec" -> if (exec.isEmpty()) exec = value
                        "Comment" -> if (comment.isEmpty()) comment = value
                        "Icon" -> if (icon.isEmpty()) icon = value
                        "Terminal" -> terminal = value.equals("true", ignoreCase = true)
                        "NoDisplay" -> noDisplay = value.equals("true", ignoreCase = true)
                        "Categories" -> categories = value.split(";").filter { it.isNotBlank() }
                    }
                }
            }

            if (name.isNotBlank() && exec.isNotBlank() && !noDisplay) {
                // Strip field codes (%f, %F, %u, %U, etc.) from Exec
                val cleanedExec = exec.replace(Regex("%[a-zA-Z]"), "").trim()
                LinuxDesktopApp(
                    id = file.nameWithoutExtension,
                    name = name,
                    executable = cleanedExec,
                    comment = comment,
                    iconName = icon,
                    categories = categories,
                    isTerminal = terminal,
                )
            } else null
        } catch (e: Exception) {
            log.warn("Error parsing desktop entry file: ${file.name}", e)
            null
        }
    }
}

