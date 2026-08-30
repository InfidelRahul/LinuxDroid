package com.linuxdroid.core.model

import kotlinx.serialization.Serializable

/**
 * A declarative filesystem binding into the guest Linux environment.
 */
@Serializable
data class RuntimeBinding(
    val hostPath: String,
    val guestPath: String,
    val readOnly: Boolean = false,
) {
    override fun toString(): String = "$hostPath:$guestPath${if (readOnly) ":ro" else ""}"
}

/**
 * Early userspace bootstrap handoff policy.
 */
@Serializable
enum class BootstrapPolicy {
    /** Direct userspace handoff to the requested command/shell (default). */
    BOOTSTRAP_USERSPACE,
    /** Execute the workload directly. */
    BOOTSTRAP_DIRECT_EXEC,
    /** Handoff to the distribution's native init when explicitly supported. */
    BOOTSTRAP_NATIVE_INIT,
}

/**
 * RuntimeSpec completely and immutably describes a Linux runtime instance before execution starts.
 */
@Serializable
data class RuntimeSpec(
    val environmentId: EnvironmentId,
    val rootfsPath: String,
    val architecture: Architecture,
    val workingDirectory: String = "/",
    val user: String = "root",
    val environmentVariables: Map<String, String> = emptyMap(),
    val bindings: List<RuntimeBinding> = emptyList(),
    val command: List<String> = listOf("/bin/sh"),
    val bootstrapPolicy: BootstrapPolicy = BootstrapPolicy.BOOTSTRAP_USERSPACE,
    val customProotPath: String? = null,
    val customLoaderPath: String? = null,
    val tmpDirPath: String? = null,
    val logFilePath: String? = null,
    val sharedStorageEnabled: Boolean = true,
) {
    init {
        require(rootfsPath.isNotBlank()) { "rootfsPath must not be blank" }
        require(command.isNotEmpty()) { "command must not be empty" }
    }

    companion object {
        /**
         * Creates a default [RuntimeSpec] from an [Environment] domain object.
         */
        fun fromEnvironment(
            environment: Environment,
            command: List<String> = listOf("/bin/sh"),
            workingDirectory: String = environment.configuration.homeDir.ifBlank { "/root" },
            extraEnv: Map<String, String> = emptyMap(),
            extraBindings: List<RuntimeBinding> = emptyList(),
            tmpDirPath: String? = null,
            logFilePath: String? = null,
        ): RuntimeSpec {
            val configuredUser = environment.configuration.linuxUser.ifBlank { "root" }
            val configuredHome = environment.configuration.homeDir.ifBlank {
                if (configuredUser == "root") "/root" else "/home/$configuredUser"
            }
            val configuredShell = environment.configuration.shell.ifBlank { "/bin/bash" }

            val envVars = buildMap {
                put("HOME", configuredHome)
                put("USER", configuredUser)
                put("LOGNAME", configuredUser)
                put("SHELL", configuredShell)
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("TERM", "xterm-256color")
                put("LANG", "C.UTF-8")
                put("TMPDIR", "/tmp")
                put("PWD", workingDirectory)
                putAll(environment.configuration.runtime.extraEnv)
                putAll(extraEnv)
            }

            val defaultBindings = buildList {
                add(RuntimeBinding("/dev", "/dev"))
                add(RuntimeBinding("/proc", "/proc"))
                add(RuntimeBinding("/sys", "/sys"))
                if (tmpDirPath != null) {
                    add(RuntimeBinding(tmpDirPath, "/tmp"))
                }
                addAll(extraBindings)
            }

            return RuntimeSpec(
                environmentId = environment.id,
                rootfsPath = environment.rootfsPath,
                architecture = environment.architecture,
                workingDirectory = workingDirectory,
                user = environment.configuration.linuxUser,
                environmentVariables = envVars,
                bindings = defaultBindings,
                command = command,
                customProotPath = environment.configuration.runtime.customProotPath,
                tmpDirPath = tmpDirPath,
                logFilePath = logFilePath,
                sharedStorageEnabled = environment.configuration.runtime.sharedStorageEnabled,
            )
        }
    }
}
