package com.linuxdroid.core.package_mgr

import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.Environment

/**
 * PackageManager provides a LinuxDroid abstraction over the distribution's native package manager.
 *
 * Does NOT replace the Linux package manager. Delegates all operations
 * to the package manager running inside the proot environment.
 *
 * Distribution mapping:
 * - Debian/Ubuntu → apt
 * - Arch Linux → pacman
 * - Alpine → apk
 *
 * Implementation: Phase 19 of the development roadmap.
 */
interface PackageManager {
    fun getPackageManagerCommand(distribution: Distribution): String
    suspend fun install(environment: Environment, packageName: String): Boolean
    suspend fun remove(environment: Environment, packageName: String): Boolean
    suspend fun update(environment: Environment): Boolean
    suspend fun search(environment: Environment, query: String): List<PackageInfo>
    suspend fun isPackageInstalled(environment: Environment, packageName: String): Boolean
    suspend fun installMinimalGui(environment: Environment, onProgress: (String) -> Unit = {}): Boolean
}

data class PackageInfo(
    val name: String,
    val version: String,
    val description: String,
    val isInstalled: Boolean,
)
