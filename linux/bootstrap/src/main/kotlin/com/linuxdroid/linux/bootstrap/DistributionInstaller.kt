package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.DistributionDefinition
import com.linuxdroid.core.model.Environment

/**
 * Interface for downloading, verifying, extracting, and installing Linux distributions into environments.
 */
interface DistributionInstaller {
    suspend fun install(
        definition: DistributionDefinition,
        environment: Environment,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        onLog: suspend (String) -> Unit = { _ -> },
    )
}

/**
 * Default implementation of [DistributionInstaller] leveraging transactional staging extraction.
 */
class DefaultDistributionInstaller(
    private val context: Context,
    private val storage: EnvironmentStorage,
    private val bootstrapper: RootfsBootstrapper = RootfsBootstrapper(context, storage),
) : DistributionInstaller {

    override suspend fun install(
        definition: DistributionDefinition,
        environment: Environment,
        onProgress: suspend (Float, String) -> Unit,
        onLog: suspend (String) -> Unit,
    ) {
        bootstrapper.bootstrapRootfs(environment, onProgress, onLog)
    }
}
