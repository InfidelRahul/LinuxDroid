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
