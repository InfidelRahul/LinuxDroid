package com.linuxdroid.app.di

import android.content.Context
import com.linuxdroid.core.database.LinuxDroidDatabase
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.diagnostics.DiagnosticsManager
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.runtime.ProotRuntimeBackend
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.core.storage.AndroidStorageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Application-level Hilt dependency injection module.
 *
 * Provides singletons for the entire application lifetime.
 * All subsystems are lazily initialized on first injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideEnvironmentStorage(
        @ApplicationContext context: Context,
    ): EnvironmentStorage = EnvironmentStorage(
        baseDir = java.io.File(context.filesDir, "environments").also { it.mkdirs() }
    )

    @Provides
    @Singleton
    fun provideRuntimeBackend(
        @ApplicationContext context: Context,
        storage: EnvironmentStorage,
    ): RuntimeBackend = ProotRuntimeBackend(context, storage)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): LinuxDroidDatabase = LinuxDroidDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideEnvironmentDao(
        database: LinuxDroidDatabase,
    ): EnvironmentDao = database.environmentDao()

    @Provides
    @Singleton
    fun provideAndroidStorageManager(
        @ApplicationContext context: Context,
    ): AndroidStorageManager = AndroidStorageManager(context)

    @Provides
    @Singleton
    fun provideDiagnosticsManager(
        storage: EnvironmentStorage,
        runtime: RuntimeBackend,
        storageManager: AndroidStorageManager,
    ): DiagnosticsManager = DiagnosticsManager(storage, runtime, storageManager)
}
