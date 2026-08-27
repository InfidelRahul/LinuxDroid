package com.linuxdroid.core.model

import kotlinx.serialization.Serializable

/**
 * Supported archive formats for distribution rootfs packages.
 */
@Serializable
enum class ArchiveFormat {
    TAR_XZ,
    TAR_GZ,
    TAR_BZ2,
}

/**
 * Declares the source format and location of a Linux distribution root filesystem.
 */
@Serializable
data class DistributionSource(
    val url: String,
    val sha256: String? = null,
    val format: ArchiveFormat = ArchiveFormat.TAR_XZ,
    val stripComponents: Int = 1,
)

/**
 * Manifest describing distribution metadata, capabilities, and requirements.
 */
@Serializable
data class DistributionManifest(
    val version: String,
    val defaultUser: String = "user",
    val defaultShell: String = "/bin/sh",
    val defaultHome: String = "/home/user",
    val requiredStorageMb: Long = 512,
    val releaseDate: String = "",
)

/**
 * A formal definition of an available Linux distribution.
 */
@Serializable
data class DistributionDefinition(
    val id: String,
    val name: String,
    val distribution: Distribution,
    val defaultArchitecture: Architecture,
    val source: DistributionSource,
    val manifest: DistributionManifest = DistributionManifest(version = "1.0"),
)

/**
 * Built-in distribution catalog containing supported distributions.
 */
object DistributionCatalog {

    fun getDefaultCatalog(): List<DistributionDefinition> = listOf(
        getDefinition(Distribution.DEBIAN, Architecture.current()),
        getDefinition(Distribution.UBUNTU, Architecture.current()),
        getDefinition(Distribution.ARCH_LINUX, Architecture.current()),
        getDefinition(Distribution.ALPINE, Architecture.current()),
    )

    fun getDefinition(distribution: Distribution, architecture: Architecture): DistributionDefinition {
        val archSuffix = when (architecture) {
            Architecture.ARM64 -> "aarch64"
            Architecture.X86_64 -> "x86_64"
        }
        val distroSlug = when (distribution) {
            Distribution.DEBIAN -> "debian"
            Distribution.UBUNTU -> "ubuntu"
            Distribution.ARCH_LINUX -> "archlinux"
            Distribution.ALPINE -> "alpine"
        }

        return DistributionDefinition(
            id = "${distroSlug}-${archSuffix}",
            name = "${distribution.displayName} (${architecture.linuxArch})",
            distribution = distribution,
            defaultArchitecture = architecture,
            source = DistributionSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.5.0/${distroSlug}-${archSuffix}-pd-v4.5.0.tar.xz",
                format = ArchiveFormat.TAR_XZ,
                stripComponents = 1,
            ),
            manifest = DistributionManifest(
                version = "v4.5.0",
                defaultShell = if (distribution == Distribution.ALPINE) "/bin/sh" else "/bin/bash",
            ),
        )
    }
}
