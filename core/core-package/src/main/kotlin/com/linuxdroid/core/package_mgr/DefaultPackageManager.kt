package com.linuxdroid.core.package_mgr

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.runtime.RuntimeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultPackageManager(
    private val runtimeBackend: RuntimeBackend,
) : PackageManager {

    private val log = LinuxDroidLogger(LogSubsystem.PACKAGE)

    override fun getPackageManagerCommand(distribution: Distribution): String {
        return distribution.packageManager
    }

    override suspend fun install(environment: Environment, packageName: String): Boolean = withContext(Dispatchers.IO) {
        val sanitizedPkg = packageName.trim()
        if (sanitizedPkg.isEmpty() || !sanitizedPkg.matches(Regex("^[a-zA-Z0-9.+_-]+$"))) {
            log.warn("Invalid package name: $packageName")
            return@withContext false
        }

        log.info("Installing package '$sanitizedPkg' in ${environment.id}")
        val cmd = when (environment.distribution) {
            Distribution.DEBIAN, Distribution.UBUNTU -> listOf("apt-get", "install", "-y", sanitizedPkg)
            Distribution.ARCH_LINUX -> listOf("pacman", "-S", "--noconfirm", sanitizedPkg)
            Distribution.ALPINE -> listOf("apk", "add", sanitizedPkg)
        }

        val extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive")
        val result = runtimeBackend.executeAndWait(environment, cmd, workingDirectory = "/root", extraEnv = extraEnv)
        val success = result.exitCode == 0
        if (!success) {
            log.error("Package install failed (${result.exitCode}): ${result.stderr}")
        }
        success
    }

    override suspend fun remove(environment: Environment, packageName: String): Boolean = withContext(Dispatchers.IO) {
        val sanitizedPkg = packageName.trim()
        if (sanitizedPkg.isEmpty() || !sanitizedPkg.matches(Regex("^[a-zA-Z0-9.+_-]+$"))) {
            log.warn("Invalid package name: $packageName")
            return@withContext false
        }

        log.info("Removing package '$sanitizedPkg' from ${environment.id}")
        val cmd = when (environment.distribution) {
            Distribution.DEBIAN, Distribution.UBUNTU -> listOf("apt-get", "remove", "-y", sanitizedPkg)
            Distribution.ARCH_LINUX -> listOf("pacman", "-R", "--noconfirm", sanitizedPkg)
            Distribution.ALPINE -> listOf("apk", "del", sanitizedPkg)
        }

        val extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive")
        val result = runtimeBackend.executeAndWait(environment, cmd, workingDirectory = "/root", extraEnv = extraEnv)
        result.exitCode == 0
    }

    override suspend fun update(environment: Environment): Boolean = withContext(Dispatchers.IO) {
        log.info("Updating package indices for ${environment.id}")
        val cmd = when (environment.distribution) {
            Distribution.DEBIAN, Distribution.UBUNTU -> listOf("apt-get", "update")
            Distribution.ARCH_LINUX -> listOf("pacman", "-Sy")
            Distribution.ALPINE -> listOf("apk", "update")
        }

        val extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive")
        val result = runtimeBackend.executeAndWait(environment, cmd, workingDirectory = "/root", extraEnv = extraEnv)
        result.exitCode == 0
    }

    override suspend fun search(environment: Environment, query: String): List<PackageInfo> = withContext(Dispatchers.IO) {
        val sanitizedQuery = query.trim()
        if (sanitizedQuery.isEmpty()) return@withContext emptyList()

        log.info("Searching packages with query '$sanitizedQuery' in ${environment.id}")
        val cmd = when (environment.distribution) {
            Distribution.DEBIAN, Distribution.UBUNTU -> listOf("apt-cache", "search", sanitizedQuery)
            Distribution.ARCH_LINUX -> listOf("pacman", "-Ss", sanitizedQuery)
            Distribution.ALPINE -> listOf("apk", "search", "-v", sanitizedQuery)
        }

        val result = runtimeBackend.executeAndWait(environment, cmd, workingDirectory = "/root")
        if (result.exitCode != 0) return@withContext emptyList()

        result.stdout.lines().mapNotNull { line ->
            val parts = line.split(" - ", limit = 2)
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val name = parts[0].trim()
                val desc = if (parts.size > 1) parts[1].trim() else ""
                PackageInfo(name = name, version = "", description = desc, isInstalled = false)
            } else null
        }
    }
}

