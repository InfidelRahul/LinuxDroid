package com.linuxdroid.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisting environment metadata and configuration.
 *
 * IMPORTANT: Only Android-side metadata is stored here.
 * The Linux rootfs itself is NEVER stored in or referenced through the database
 * as a serialized blob. The rootfsPath column only stores the filesystem path.
 */
@Entity(tableName = "environments")
data class EnvironmentEntity(
    /** Immutable environment ID. Primary key. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Human-readable name. */
    @ColumnInfo(name = "name")
    val name: String,

    /** Distribution (serialized enum name). */
    @ColumnInfo(name = "distribution")
    val distribution: String,

    /** Architecture (serialized enum name). */
    @ColumnInfo(name = "architecture")
    val architecture: String,

    /** Current lifecycle state (serialized enum name). */
    @ColumnInfo(name = "state")
    val state: String,

    /** Absolute path to the rootfs directory. */
    @ColumnInfo(name = "rootfs_path")
    val rootfsPath: String,

    /** Absolute path to the metadata directory. */
    @ColumnInfo(name = "metadata_path")
    val metadataPath: String,

    /** Serialized RuntimeConfig as JSON. */
    @ColumnInfo(name = "runtime_config_json")
    val runtimeConfigJson: String,

    /** Serialized DisplayConfig as JSON. */
    @ColumnInfo(name = "display_config_json")
    val displayConfigJson: String,

    /** Serialized GpuConfig as JSON. */
    @ColumnInfo(name = "gpu_config_json")
    val gpuConfigJson: String,

    /** Serialized AudioConfig as JSON. */
    @ColumnInfo(name = "audio_config_json")
    val audioConfigJson: String,

    /** Serialized NetworkConfig as JSON. */
    @ColumnInfo(name = "network_config_json")
    val networkConfigJson: String,

    /** Serialized DesktopConfig as JSON. */
    @ColumnInfo(name = "desktop_config_json")
    val desktopConfigJson: String,

    /** Linux username inside the environment. */
    @ColumnInfo(name = "linux_user")
    val linuxUser: String,

    /** Linux user home directory inside the chroot. */
    @ColumnInfo(name = "home_dir")
    val homeDir: String,

    /** When the environment was created (epoch ms). */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** When the state last changed (epoch ms). */
    @ColumnInfo(name = "last_state_change_at")
    val lastStateChangeAt: Long,

    /** Failure message if state == FAILED. */
    @ColumnInfo(name = "failure_message")
    val failureMessage: String?,
)
