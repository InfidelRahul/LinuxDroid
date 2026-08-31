package com.linuxdroid.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Metadata recorded for an installed Linux root filesystem environment.
 * Persisted inside `<env-dir>/metadata/rootfs-manifest.json`.
 */
@Serializable
data class RootfsMetadata(
    val distribution: String,
    val release: String,
    val architecture: String,
    val variant: String = "minimal",
    val source: String,
    val artifact: String,
    @SerialName("checksum_algorithm")
    val checksumAlgorithm: String,
    val checksum: String,
    @SerialName("bootstrap_version")
    val bootstrapVersion: String = "1.0.0",
    val status: String = "ready",
    @SerialName("installed_at")
    val installedAt: Long = System.currentTimeMillis(),
)

