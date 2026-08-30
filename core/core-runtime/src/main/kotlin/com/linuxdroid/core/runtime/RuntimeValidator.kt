package com.linuxdroid.core.runtime

import com.linuxdroid.core.model.FilesystemError
import com.linuxdroid.core.model.RuntimeError
import com.linuxdroid.core.model.RuntimeSpec
import java.io.File

/**
 * Validates a [RuntimeSpec] before execution to prevent invalid invocations.
 */
class RuntimeValidator {

    /**
     * Resolves the guest executable inside [rootfs].
     * Returns the host [File] if found, or null if it cannot be found.
     */
    fun resolveExecutableInRootfs(rootfs: File, command: String): File? {
        if (command.startsWith("/")) {
            val relative = command.removePrefix("/")
            val direct = File(rootfs, relative)
            if (direct.exists()) return direct

            // Handle standard merged-usr /bin -> /usr/bin or /sbin -> /usr/sbin lookups
            if (command.startsWith("/bin/")) {
                val usrBin = File(rootfs, "usr/bin/${command.removePrefix("/bin/")}")
                if (usrBin.exists()) return usrBin
            } else if (command.startsWith("/usr/bin/")) {
                val bin = File(rootfs, "bin/${command.removePrefix("/usr/bin/")}")
                if (bin.exists()) return bin
            } else if (command.startsWith("/sbin/")) {
                val usrSbin = File(rootfs, "usr/sbin/${command.removePrefix("/sbin/")}")
                if (usrSbin.exists()) return usrSbin
            } else if (command.startsWith("/usr/sbin/")) {
                val sbin = File(rootfs, "sbin/${command.removePrefix("/usr/sbin/")}")
                if (sbin.exists()) return sbin
            }
            return null
        }

        val candidateDirs = listOf("bin", "usr/bin", "sbin", "usr/sbin")
        for (dir in candidateDirs) {
            val candidate = File(rootfs, "$dir/$command")
            if (candidate.exists()) return candidate
        }
        return null
    }

    /**
     * Resolves an available shell in [rootfs], defaulting to [requestedShell] or falling back
     * to `/bin/bash`, `/usr/bin/bash`, `/bin/sh`, or `/usr/bin/sh`.
     */
    fun resolveShell(rootfs: File, requestedShell: String): String {
        val cleanRequested = requestedShell.ifBlank { "/bin/bash" }
        if (resolveExecutableInRootfs(rootfs, cleanRequested) != null) {
            return cleanRequested
        }

        val fallbacks = listOf("/bin/bash", "/usr/bin/bash", "/bin/sh", "/usr/bin/sh", "/bin/dash", "/usr/bin/dash")
        for (candidate in fallbacks) {
            if (resolveExecutableInRootfs(rootfs, candidate) != null) {
                return candidate
            }
        }

        return cleanRequested
    }

    /**
     * Validates [spec] against the local environment and filesystem.
     *
     * @throws RuntimeError or [FilesystemError] if validation fails.
     */
    fun validate(spec: RuntimeSpec) {
        val rootfs = File(spec.rootfsPath)
        if (!rootfs.exists() || !rootfs.isDirectory) {
            throw FilesystemError(
                path = spec.rootfsPath,
                message = "Rootfs directory does not exist or is not a directory: ${spec.rootfsPath}",
            )
        }

        if (spec.command.isEmpty()) {
            throw RuntimeError(
                environmentId = spec.environmentId,
                message = "Runtime command cannot be empty",
            )
        }

        spec.customProotPath?.let { path ->
            val bin = File(path)
            if (!bin.exists() || !bin.canExecute()) {
                throw RuntimeError(
                    environmentId = spec.environmentId,
                    message = "Custom PRoot binary not found or not executable: $path",
                )
            }
        }
    }
}
