package com.linuxdroid.core.runtime

import com.linuxdroid.core.model.RuntimeSpec
import java.io.File

/**
 * Concrete deterministic command builder for the PRoot native backend.
 */
class ProotCommandBuilder : RuntimeCommandBuilder {

    override fun build(spec: RuntimeSpec, executableOverride: File?): List<String> {
        val prootBin = executableOverride?.absolutePath
            ?: spec.customProotPath
            ?: "proot"

        return buildList {
            add(prootBin)
            add("-0")
            add("--kill-on-exit")
            add("--link2symlink")
            add("-r")
            add(spec.rootfsPath)

            // Dynamic bindings from spec
            spec.bindings.forEach { binding ->
                add("-b")
                if (binding.hostPath == binding.guestPath) {
                    add(binding.hostPath)
                } else {
                    add("${binding.hostPath}:${binding.guestPath}${if (binding.readOnly) ":ro" else ""}")
                }
            }

            // Shared storage binding if requested and directory is accessible
            if (spec.sharedStorageEnabled) {
                try {
                    val sharedDir = File(android.os.Environment.getExternalStorageDirectory(), "LinuxDroid")
                    if (sharedDir.exists() && sharedDir.canRead()) {
                        add("-b")
                        add("${sharedDir.absolutePath}:/home/user/Android")
                    }
                } catch (_: Throwable) {
                    // In unit tests android.os.Environment may not be mocked; ignore silently
                }
            }

            add("-w")
            add(spec.workingDirectory.ifBlank { "/" })

            // Target workload / shell
            addAll(spec.command)
        }
    }
}
