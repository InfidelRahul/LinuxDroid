package com.linuxdroid.core.database

import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Domain coordinator for persistent Linux environments.
 * Manages creation, lifecycle state, database records, and filesystem storage.
 */
interface EnvironmentManager {
    suspend fun createEnvironment(
        name: String,
        distribution: Distribution = Distribution.default(),
        architecture: Architecture = Architecture.current(),
        configuration: EnvironmentConfiguration = EnvironmentConfiguration(),
    ): Environment

    suspend fun installEnvironment(
        environmentId: EnvironmentId,
        installerAction: suspend (Environment) -> Unit,
    ): Environment

    suspend fun validateEnvironment(environmentId: EnvironmentId): Boolean

    suspend fun getEnvironment(environmentId: EnvironmentId): Environment?

    suspend fun listEnvironments(): List<Environment>

    fun observeEnvironments(): Flow<List<Environment>>

    suspend fun resetEnvironment(environmentId: EnvironmentId): Environment

    suspend fun deleteEnvironment(environmentId: EnvironmentId)

    suspend fun cloneEnvironment(sourceId: EnvironmentId, newName: String): Environment
}

/**
 * Default implementation of [EnvironmentManager] with transactional state guarantees and concurrency locks.
 */
class DefaultEnvironmentManager(
    private val dao: EnvironmentDao,
    private val storage: EnvironmentStorage,
) : EnvironmentManager {

    private val log = LinuxDroidLogger(LogSubsystem.DATABASE)
    private val locks = ConcurrentHashMap<EnvironmentId, Mutex>()

    private fun getLock(id: EnvironmentId): Mutex = locks.computeIfAbsent(id) { Mutex() }

    override suspend fun createEnvironment(
        name: String,
        distribution: Distribution,
        architecture: Architecture,
        configuration: EnvironmentConfiguration,
    ): Environment = withContext(Dispatchers.IO) {
        val id = EnvironmentId.generate()
        log.info("Creating environment $id ('$name', $distribution, $architecture)")

        storage.initializeEnvironmentDirs(id)

        val metadata = EnvironmentMetadata(
            id = id,
            name = name,
            distribution = distribution,
            architecture = architecture,
        )

        val env = Environment(
            metadata = metadata,
            configuration = configuration,
            state = EnvironmentState.CREATED,
            rootfsPath = storage.rootfsDir(id).absolutePath,
            metadataPath = storage.metadataDir(id).absolutePath,
        )

        dao.insert(EnvironmentMapper.toEntity(env))
        env
    }

    override suspend fun installEnvironment(
        environmentId: EnvironmentId,
        installerAction: suspend (Environment) -> Unit,
    ): Environment = getLock(environmentId).withLock {
        withContext(Dispatchers.IO) {
            val entity = dao.getById(environmentId.value)
                ?: throw RuntimeError(environmentId, "Environment not found in database")
            var env = EnvironmentMapper.toDomain(entity)

            log.info("Starting installation for ${environmentId.value}")
            env = env.withState(EnvironmentState.INSTALLING)
            dao.updateState(env.id.value, env.state.name, env.lastStateChangeAt, null)

            try {
                installerAction(env)

                if (!storage.verifyRootfs(environmentId)) {
                    throw FilesystemError(
                        path = storage.rootfsDir(environmentId).absolutePath,
                        message = "Rootfs validation failed after installation",
                    )
                }

                env = env.withState(EnvironmentState.READY)
                dao.updateState(env.id.value, env.state.name, env.lastStateChangeAt, null)
                log.info("Installation completed successfully for ${environmentId.value}")
                env
            } catch (e: Exception) {
                log.error("Installation failed for ${environmentId.value}", e)
                env = env.withState(EnvironmentState.FAILED, e.message)
                dao.updateState(env.id.value, env.state.name, env.lastStateChangeAt, e.message)
                throw e
            }
        }
    }

    override suspend fun validateEnvironment(environmentId: EnvironmentId): Boolean = withContext(Dispatchers.IO) {
        storage.verifyRootfs(environmentId)
    }

    override suspend fun getEnvironment(environmentId: EnvironmentId): Environment? = withContext(Dispatchers.IO) {
        dao.getById(environmentId.value)?.let { EnvironmentMapper.toDomain(it) }
    }

    override suspend fun listEnvironments(): List<Environment> = withContext(Dispatchers.IO) {
        dao.getAll().map { EnvironmentMapper.toDomain(it) }
    }

    override fun observeEnvironments(): Flow<List<Environment>> {
        return dao.observeAll().map { list -> list.map { EnvironmentMapper.toDomain(it) } }
    }

    override suspend fun resetEnvironment(environmentId: EnvironmentId): Environment = getLock(environmentId).withLock {
        withContext(Dispatchers.IO) {
            log.info("Resetting environment ${environmentId.value}")
            storage.cleanRuntimeState(environmentId)
            val entity = dao.getById(environmentId.value)
                ?: throw RuntimeError(environmentId, "Environment not found in database")
            var env = EnvironmentMapper.toDomain(entity)
            if (env.state.isActive()) {
                env = env.withState(EnvironmentState.STOPPED)
                dao.updateState(env.id.value, env.state.name, env.lastStateChangeAt, null)
            }
            env
        }
    }

    override suspend fun deleteEnvironment(environmentId: EnvironmentId): Unit {
        getLock(environmentId).withLock {
            withContext(Dispatchers.IO) {
                log.info("Deleting environment ${environmentId.value}")
                dao.deleteById(environmentId.value)
                storage.deleteEnvironment(environmentId)
                locks.remove(environmentId)
            }
        }
    }

    override suspend fun cloneEnvironment(sourceId: EnvironmentId, newName: String): Environment = getLock(sourceId).withLock {
        withContext(Dispatchers.IO) {
            val source = getEnvironment(sourceId)
                ?: throw RuntimeError(sourceId, "Source environment not found")

            val newEnv = createEnvironment(
                name = newName,
                distribution = source.distribution,
                architecture = source.architecture,
                configuration = source.configuration,
            )

            val sourceRootfs = storage.rootfsDir(sourceId)
            val targetRootfs = storage.rootfsDir(newEnv.id)

            if (sourceRootfs.exists() && sourceRootfs.isDirectory) {
                sourceRootfs.copyRecursively(targetRootfs, overwrite = true)
                val readyEnv = newEnv.withState(EnvironmentState.READY)
                dao.updateState(readyEnv.id.value, readyEnv.state.name, readyEnv.lastStateChangeAt, null)
                readyEnv
            } else {
                newEnv
            }
        }
    }
}
