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

    suspend fun reconcileEnvironments(): List<Environment>
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

            storage.discardStaging(environmentId)

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
                storage.discardStaging(environmentId)
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
            val entity = dao.getById(environmentId.value)
                ?: throw RuntimeError(environmentId, "Environment not found in database")
            var env = EnvironmentMapper.toDomain(entity)

            if (env.state.isActive()) {
                env = env.withState(EnvironmentState.STOPPED)
                dao.updateState(env.id.value, env.state.name, env.lastStateChangeAt, null)
            }

            env = env.withState(EnvironmentState.RESETTING)
            dao.updateState(env.id.value, env.state.name, env.lastStateChangeAt, null)

            storage.cleanRuntimeState(environmentId)
            storage.discardStaging(environmentId)

            val isValid = storage.verifyRootfs(environmentId)
            val finalState = if (isValid) EnvironmentState.READY else EnvironmentState.FAILED
            val failureMsg = if (isValid) null else "Rootfs invalid after reset"

            env = env.withState(finalState, failureMsg)
            dao.updateState(env.id.value, env.state.name, env.lastStateChangeAt, failureMsg)
            env
        }
    }

    override suspend fun deleteEnvironment(environmentId: EnvironmentId): Unit {
        getLock(environmentId).withLock {
            withContext(Dispatchers.IO) {
                log.info("Initiating transactional deletion for ${environmentId.value}")
                val entity = dao.getById(environmentId.value)
                if (entity != null) {
                    val env = EnvironmentMapper.toDomain(entity)
                    if (env.state.isActive()) {
                        throw RuntimeError(environmentId, "Cannot delete active environment ${environmentId.value}. Stop it first.")
                    }
                    dao.updateState(environmentId.value, EnvironmentState.DELETING.name, System.currentTimeMillis(), null)
                }

                storage.deleteEnvironment(environmentId)
                dao.deleteById(environmentId.value)
                locks.remove(environmentId)
                log.info("Deleted environment ${environmentId.value} cleanly from storage and database")
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

            getLock(newEnv.id).withLock {
                var targetEnv = newEnv.withState(EnvironmentState.CLONING)
                dao.updateState(targetEnv.id.value, targetEnv.state.name, targetEnv.lastStateChangeAt, null)

                try {
                    val sourceRootfs = storage.rootfsDir(sourceId)
                    val targetStaging = storage.stagingRootfsDir(newEnv.id)

                    if (sourceRootfs.exists() && sourceRootfs.isDirectory) {
                        targetStaging.mkdirs()
                        sourceRootfs.copyRecursively(targetStaging, overwrite = true)

                        if (!storage.promoteStagedRootfs(newEnv.id)) {
                            throw FilesystemError(
                                path = targetStaging.absolutePath,
                                message = "Failed to promote staged clone rootfs",
                            )
                        }

                        targetEnv = targetEnv.withState(EnvironmentState.READY)
                        dao.updateState(targetEnv.id.value, targetEnv.state.name, targetEnv.lastStateChangeAt, null)
                        targetEnv
                    } else {
                        targetEnv = targetEnv.withState(EnvironmentState.READY)
                        dao.updateState(targetEnv.id.value, targetEnv.state.name, targetEnv.lastStateChangeAt, null)
                        targetEnv
                    }
                } catch (e: Exception) {
                    log.error("Cloning failed for new environment ${newEnv.id.value}", e)
                    storage.discardStaging(newEnv.id)
                    targetEnv = targetEnv.withState(EnvironmentState.FAILED, e.message)
                    dao.updateState(targetEnv.id.value, targetEnv.state.name, targetEnv.lastStateChangeAt, e.message)
                    throw e
                }
            }
        }
    }

    override suspend fun reconcileEnvironments(): List<Environment> = withContext(Dispatchers.IO) {
        log.info("Reconciling interrupted environment states on startup")
        val entities = dao.getAll()
        val reconciled = mutableListOf<Environment>()

        for (entity in entities) {
            val env = EnvironmentMapper.toDomain(entity)
            val id = env.id

            when (env.state) {
                EnvironmentState.DELETING -> {
                    log.info("Resuming interrupted deletion for environment ${id.value}")
                    storage.deleteEnvironment(id)
                    dao.deleteById(id.value)
                }
                EnvironmentState.INSTALLING, EnvironmentState.CLONING, EnvironmentState.RESETTING -> {
                    log.warn("Reconciling interrupted state ${env.state} for environment ${id.value}")
                    storage.discardStaging(id)
                    val isValid = storage.verifyRootfs(id)
                    val nextState = if (isValid) EnvironmentState.READY else EnvironmentState.FAILED
                    val failureMsg = if (isValid) null else "Interrupted during ${env.state.name}"
                    val updated = env.withState(nextState, failureMsg)
                    dao.updateState(id.value, updated.state.name, updated.lastStateChangeAt, failureMsg)
                    reconciled.add(updated)
                }
                else -> {
                    reconciled.add(env)
                }
            }
        }
        reconciled
    }
}
