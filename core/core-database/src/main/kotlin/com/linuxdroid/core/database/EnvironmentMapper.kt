package com.linuxdroid.core.database

import com.linuxdroid.core.database.entity.EnvironmentEntity
import com.linuxdroid.core.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Maps between Room entities and domain models.
 * Handles serialization of nested configuration objects.
 */
object EnvironmentMapper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun toEntity(environment: Environment): EnvironmentEntity {
        val meta = environment.metadata
        val config = environment.configuration
        return EnvironmentEntity(
            id = meta.id.value,
            name = meta.name,
            distribution = meta.distribution.name,
            architecture = meta.architecture.name,
            state = environment.state.name,
            rootfsPath = environment.rootfsPath,
            metadataPath = environment.metadataPath,
            runtimeConfigJson = json.encodeToString(config.runtime),
            displayConfigJson = json.encodeToString(config.display),
            gpuConfigJson = json.encodeToString(config.gpu),
            audioConfigJson = json.encodeToString(config.audio),
            networkConfigJson = json.encodeToString(config.network),
            desktopConfigJson = json.encodeToString(config.desktop),
            linuxUser = config.linuxUser,
            homeDir = config.homeDir,
            createdAt = meta.createdAt,
            lastStateChangeAt = environment.lastStateChangeAt,
            failureMessage = environment.failureMessage,
        )
    }

    fun toDomain(entity: EnvironmentEntity): Environment {
        val id = EnvironmentId(entity.id)
        val distribution = Distribution.valueOf(entity.distribution)
        val architecture = Architecture.valueOf(entity.architecture)
        val state = EnvironmentState.valueOf(entity.state)

        val metadata = EnvironmentMetadata(
            id = id,
            name = entity.name,
            distribution = distribution,
            architecture = architecture,
            createdAt = entity.createdAt,
        )

        val configuration = EnvironmentConfiguration(
            runtime = json.decodeFromString(entity.runtimeConfigJson),
            display = json.decodeFromString(entity.displayConfigJson),
            gpu = json.decodeFromString(entity.gpuConfigJson),
            audio = json.decodeFromString(entity.audioConfigJson),
            network = json.decodeFromString(entity.networkConfigJson),
            desktop = json.decodeFromString(entity.desktopConfigJson),
            linuxUser = entity.linuxUser,
            homeDir = entity.homeDir,
        )

        return Environment(
            metadata = metadata,
            configuration = configuration,
            state = state,
            rootfsPath = entity.rootfsPath,
            metadataPath = entity.metadataPath,
            lastStateChangeAt = entity.lastStateChangeAt,
            failureMessage = entity.failureMessage,
        )
    }
}
