package com.linuxdroid.core.runtime

import com.linuxdroid.core.model.BootstrapPolicy
import com.linuxdroid.core.model.ExecutionTarget
import com.linuxdroid.core.model.RuntimeSpec
import java.io.File

/**
 * Concrete deterministic command builder for the PRoot native backend.
 *
 * This is a pure [RuntimeSpec] -> argv translator. It performs **no**
 * filesystem or binary discovery:
 *  - the PRoot executable path is supplied by the caller (already resolved
 *    through [RuntimeAssetsManager]) as [executableOverride];
 *  - every binding comes from [RuntimeSpec.bindings]; Android shared-storage
 *    discovery is performed by the runtime backend, not here.
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

            // Dynamic bindings from spec (no filesystem discovery here).
            spec.bindings.forEach { binding ->
                add("-b")
                if (binding.hostPath == binding.guestPath) {
                    add(binding.hostPath)
                } else {
                    add("${binding.hostPath}:${binding.guestPath}${if (binding.readOnly) ":ro" else ""}")
                }
            }

            add("-w")
            add(spec.workingDirectory.ifBlank { "/" })

            // Host Preboot Handover to Guest Init:
            // When executing in GUEST target under BOOTSTRAP_USERSPACE or BOOTSTRAP_NATIVE_INIT,
            // handover entrypoint is /sbin/linuxdroid-init
            val guestInit = spec.guestInitPath
            val useInit = spec.executionTarget == ExecutionTarget.GUEST &&
                spec.bootstrapPolicy != BootstrapPolicy.BOOTSTRAP_DIRECT_EXEC &&
                !guestInit.isNullOrBlank()

            if (useInit && spec.command.firstOrNull() != guestInit) {
                add(guestInit!!)
            }

            // Target workload / shell arguments (strictly preserved structured array)
            addAll(spec.command)
        }
    }
}
