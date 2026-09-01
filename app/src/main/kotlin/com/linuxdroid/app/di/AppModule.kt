package com.linuxdroid.app.di

import android.content.Context
import com.linuxdroid.core.audio.AudioManager
import com.linuxdroid.core.audio.DefaultAudioManager
import com.linuxdroid.core.database.LinuxDroidDatabase
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.diagnostics.DefaultResourceManager
import com.linuxdroid.core.diagnostics.DiagnosticsManager
import com.linuxdroid.core.diagnostics.ResourceManager
import com.linuxdroid.core.display.AndroidDisplayTransport
import com.linuxdroid.core.display.AndroidGraphicsCapabilityProbe
import com.linuxdroid.core.display.DefaultDisplayManager
import com.linuxdroid.core.display.DisplayManager
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.gpu.DefaultGpuManager
import com.linuxdroid.core.gpu.GpuManager
import com.linuxdroid.core.gui.SurfaceLifecycle
import com.linuxdroid.core.host.*
import com.linuxdroid.core.input.DefaultInputManager
import com.linuxdroid.core.input.InputManager
import com.linuxdroid.core.network.DefaultNetworkManager
import com.linuxdroid.core.network.NetworkManager
import com.linuxdroid.core.package_mgr.ApplicationManager
import com.linuxdroid.core.package_mgr.DefaultApplicationManager
import com.linuxdroid.core.package_mgr.DefaultPackageManager
import com.linuxdroid.core.package_mgr.PackageManager
import com.linuxdroid.core.process.DefaultProcessManager
import com.linuxdroid.core.process.ProcessManager
import com.linuxdroid.core.runtime.DefaultRuntimeManager
import com.linuxdroid.core.runtime.ProotRuntimeBackend
import com.linuxdroid.core.runtime.RuntimeAssetsManager
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.core.runtime.RuntimeManager
import com.linuxdroid.core.session.DefaultGuiRuntimeFactory
import com.linuxdroid.core.session.DefaultSessionManager
import com.linuxdroid.core.session.GuiRuntimeFactory
import com.linuxdroid.core.session.SessionManager
import com.linuxdroid.core.storage.AndroidStorageManager
import com.linuxdroid.linux.bootstrap.RootfsBootstrapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideEnvironmentStorage(
        @ApplicationContext context: Context,
    ): EnvironmentStorage = EnvironmentStorage(
        baseDir = File(context.filesDir, "environments").also { it.mkdirs() }
    )

    @Provides
    @Singleton
    fun provideRuntimeAssetsManager(
        @ApplicationContext context: Context,
    ): RuntimeAssetsManager = RuntimeAssetsManager(context)

    @Provides
    @Singleton
    fun provideProotRuntimeBackend(
        @ApplicationContext context: Context,
        storage: EnvironmentStorage,
        assetsManager: RuntimeAssetsManager,
    ): ProotRuntimeBackend = ProotRuntimeBackend(context, storage, assetsManager)

    @Provides
    @Singleton
    fun provideRuntimeBackend(
        backend: ProotRuntimeBackend,
    ): RuntimeBackend = backend

    @Provides
    @Singleton
    fun provideRuntimeManager(
        backend: ProotRuntimeBackend,
    ): RuntimeManager = DefaultRuntimeManager(backend)

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
    fun provideThemePreferences(
        @ApplicationContext context: Context,
    ): com.linuxdroid.core.storage.ThemePreferences = com.linuxdroid.core.storage.ThemePreferences(context)

    @Provides
    @Singleton
    fun provideDisplayManager(): DisplayManager = DefaultDisplayManager()

    @Provides
    @Singleton
    fun provideGpuManager(): GpuManager = DefaultGpuManager()

    @Provides
    @Singleton
    fun provideInputManager(): InputManager = DefaultInputManager()

    @Provides
    @Singleton
    fun provideAudioManager(): AudioManager = DefaultAudioManager()

    @Provides
    @Singleton
    fun provideNetworkManager(
        @ApplicationContext context: Context,
    ): NetworkManager = DefaultNetworkManager(context)

    @Provides
    @Singleton
    fun providePackageManager(
        runtime: RuntimeBackend,
    ): PackageManager = DefaultPackageManager(runtime)

    @Provides
    @Singleton
    fun provideApplicationManager(
        storage: EnvironmentStorage,
    ): ApplicationManager = DefaultApplicationManager(storage)

    @Provides
    @Singleton
    fun provideResourceManager(
        @ApplicationContext context: Context,
        storage: EnvironmentStorage,
    ): ResourceManager = DefaultResourceManager(context, storage)

    @Provides
    @Singleton
    fun provideDiagnosticsManager(
        storage: EnvironmentStorage,
        runtime: RuntimeBackend,
        storageManager: AndroidStorageManager,
        gpuManager: GpuManager,
        audioManager: AudioManager,
        networkManager: NetworkManager,
        resourceManager: ResourceManager,
    ): DiagnosticsManager = DiagnosticsManager(
        storage = storage,
        runtimeBackend = runtime,
        storageManager = storageManager,
        gpuManager = gpuManager,
        audioManager = audioManager,
        networkManager = networkManager,
        resourceManager = resourceManager,
    )

    @Provides
    @Singleton
    fun provideRuntimeLogExporter(
        storage: EnvironmentStorage,
        diagnosticsManager: DiagnosticsManager,
    ): com.linuxdroid.core.diagnostics.RuntimeLogExporter = com.linuxdroid.core.diagnostics.RuntimeLogExporter(
        storage = storage,
        diagnosticsManager = diagnosticsManager,
    )

    @Provides
    @Singleton
    fun provideRootfsBootstrapper(
        @ApplicationContext context: Context,
        storage: EnvironmentStorage,
    ): RootfsBootstrapper = RootfsBootstrapper(context, storage)

    @Provides
    @Singleton
    fun provideDefaultProcessManager(): DefaultProcessManager = DefaultProcessManager()

    @Provides
    @Singleton
    fun provideProcessManager(
        processManager: DefaultProcessManager,
    ): ProcessManager = processManager

    // ─── GUI: display boundary and graphical session wiring ───────────────────

    /**
     * Single source of truth for the Android surface state, shared by the
     * output view (which reports the raw callbacks) and the display transport
     * (which presents frames). Both must agree or frames could be posted into
     * a destroyed window.
     */
    @Provides
    @Singleton
    fun provideSurfaceLifecycle(): SurfaceLifecycle = SurfaceLifecycle { null }

    @Provides
    @Singleton
    fun provideDisplayTransport(
        hostGraphics: HostGraphics,
        surfaceLifecycle: SurfaceLifecycle,
    ): AndroidDisplayTransport = AndroidDisplayTransport(
        hostGraphics = hostGraphics,
        guiLog = { null },
        surfaceLifecycle = surfaceLifecycle,
    )

    @Provides
    @Singleton
    fun provideGraphicsCapabilityProbe(
        hostGpu: HostGpu,
        hostGraphics: HostGraphics,
    ): AndroidGraphicsCapabilityProbe = AndroidGraphicsCapabilityProbe(hostGpu, hostGraphics)

    @Provides
    @Singleton
    fun provideGuiRuntimeFactory(
        storage: EnvironmentStorage,
        runtimeManager: RuntimeManager,
        processManager: DefaultProcessManager,
        displayTransport: AndroidDisplayTransport,
        capabilityProbe: AndroidGraphicsCapabilityProbe,
    ): GuiRuntimeFactory = DefaultGuiRuntimeFactory(
        storage = storage,
        runtimeManager = runtimeManager,
        processManager = processManager,
        displayTransport = displayTransport,
        capabilityProbe = capabilityProbe,
    )

    @Provides
    @Singleton
    fun provideSessionManager(
        runtime: RuntimeBackend,
        storage: EnvironmentStorage,
        displayManager: DisplayManager,
        gpuManager: GpuManager,
        inputManager: InputManager,
        audioManager: AudioManager,
        networkManager: NetworkManager,
        runtimeManager: RuntimeManager,
        guiRuntimeFactory: GuiRuntimeFactory,
    ): SessionManager = DefaultSessionManager(
        runtimeBackend = runtime,
        storage = storage,
        displayManager = displayManager,
        gpuManager = gpuManager,
        inputManager = inputManager,
        audioManager = audioManager,
        networkManager = networkManager,
        runtimeManager = runtimeManager,
        guiRuntimeFactory = guiRuntimeFactory,
    )

    @Provides
    @Singleton
    fun provideHostGraphics(): HostGraphics = AndroidHostGraphics()

    @Provides
    @Singleton
    fun provideHostGpu(): HostGpu = AndroidHostGpu()

    @Provides
    @Singleton
    fun provideHostAudio(): HostAudio = AndroidHostAudio()

    @Provides
    @Singleton
    fun provideHostInput(): HostInput = AndroidHostInput()

    @Provides
    @Singleton
    fun provideHostNetwork(
        @ApplicationContext context: Context,
    ): HostNetwork = AndroidHostNetwork(context)

    @Provides
    @Singleton
    fun provideHostStorage(
        storageManager: AndroidStorageManager,
    ): HostStorage = AndroidHostStorage(storageManager.sharedDirectory)
}
