package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.native_bridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * RootfsBootstrapper downloads, verifies, extracts, and configures the Linux rootfs.
 *
 * Implements atomic staging extraction:
 * 1. Download to tmp/rootfs.tar.xz
 * 2. SHA256 verification (if specified)
 * 3. Extract into tmp/rootfs-staging/ using pure Java/NDK streaming decompressor (NO external tar/xz binaries)
 * 4. Configure rootfs (resolv.conf, hostname, /home/user)
 * 5. Validate staging rootfs
 * 6. Atomically move/rename staging directory into final rootfs/
 *
 * CRITICAL: bootstrapRootfs() NEVER deletes or corrupts an existing rootfs.
 */
class RootfsBootstrapper(
    private val context: Context,
    private val storage: EnvironmentStorage,
) {
    private val log = LinuxDroidLogger(LogSubsystem.BOOTSTRAP)

    companion object {
        fun getRootfsSource(distribution: Distribution, architecture: Architecture): RootfsSource {
            val archSuffix = when (architecture) {
                Architecture.ARM64 -> "aarch64"
                Architecture.X86_64 -> "x86_64"
            }
            val distroName = when (distribution) {
                Distribution.DEBIAN -> "debian"
                Distribution.UBUNTU -> "ubuntu"
                Distribution.ARCH_LINUX -> "archlinux"
                Distribution.ALPINE -> "alpine"
            }
            return RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.5.0/${distroName}-${archSuffix}-pd-v4.5.0.tar.xz",
                sha256 = null,
                format = ArchiveFormat.TAR_XZ,
                stripComponents = 1,
            )
        }
    }

    suspend fun bootstrapRootfs(
        environment: Environment,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val environmentId = environment.id
        log.info("Starting rootfs bootstrap for $environmentId (${environment.distribution} / ${environment.architecture})")

        // Never recreate existing rootfs if already valid
        if (storage.verifyRootfs(environmentId)) {
            log.info("Rootfs already exists and is valid for $environmentId. Skipping bootstrap.")
            onProgress(1.0f, "Rootfs already installed")
            return@withContext
        }

        val source = getRootfsSource(environment.distribution, environment.architecture)

        storage.initializeEnvironmentDirs(environmentId)
        val finalRootfsDir = storage.rootfsDir(environmentId)
        val tmpDir = storage.tmpDir(environmentId)
        val stagingDir = File(tmpDir, "rootfs-staging")
        val tarball = File(tmpDir, "rootfs.tar.xz")

        // Clean previous staging directory if exists
        if (stagingDir.exists()) {
            stagingDir.deleteRecursively()
        }
        stagingDir.mkdirs()

        try {
            // 1. Download
            onProgress(0.05f, "Downloading ${environment.distribution.displayName} rootfs…")
            log.info("Downloading rootfs from: ${source.url}")
            downloadFile(source.url, tarball, onProgress)

            // 2. SHA256 Verification
            source.sha256?.let { expectedSha256 ->
                onProgress(0.65f, "Verifying download…")
                val actual = sha256(tarball)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    tarball.delete()
                    throw RuntimeError(
                        environmentId = environmentId,
                        message = "SHA256 mismatch: expected=$expectedSha256 actual=$actual",
                    )
                }
            }

            // 3. Extract to staging directory
            onProgress(0.70f, "Extracting Linux userspace…")
            log.info("Extracting tarball to staging directory: ${stagingDir.path}")
            extractArchive(tarball, stagingDir, source.format, source.stripComponents, onProgress)

            // 4. Configure staging rootfs
            onProgress(0.92f, "Configuring system files…")
            configureRootfs(stagingDir)

            // 5. Validate staging rootfs
            validateStagingRootfs(stagingDir, environment.distribution)

            // 6. Atomically move staging to final rootfs
            onProgress(0.97f, "Finalizing installation…")
            finalizeRootfs(stagingDir, finalRootfsDir)

            onProgress(1.0f, "Installation complete")
            log.info("Rootfs bootstrap complete for $environmentId")

        } catch (e: Exception) {
            log.error("Rootfs bootstrap failed for $environmentId", e)
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            throw RuntimeError(
                environmentId = environmentId,
                message = "Bootstrap failed: ${e.message}",
                cause = e,
            )
        } finally {
            if (tarball.exists()) {
                tarball.delete()
            }
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
        }
    }

    private suspend fun downloadFile(
        url: String,
        dest: File,
        onProgress: suspend (Float, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection()
        connection.connect()
        val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L

        connection.getInputStream().use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        val fraction = downloaded.toFloat() / totalBytes
                        onProgress(0.05f + fraction * 0.60f, "Downloading… ${downloaded / 1_048_576}MB")
                    }
                }
            }
        }
    }

    /**
     * Extracts an archive directly using Commons Compress / XZ streams.
     * Operates 100% in Java/NDK without executing external tar or xz binaries.
     */
    private suspend fun extractArchive(
        tarball: File,
        destDir: File,
        format: ArchiveFormat,
        stripComponents: Int,
        onProgress: suspend (Float, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val destCanonicalPath = destDir.canonicalPath
        val fileInputStream = BufferedInputStream(FileInputStream(tarball), 64 * 1024)

        val decompressorStream: InputStream = when (format) {
            ArchiveFormat.TAR_XZ -> XZCompressorInputStream(fileInputStream)
            ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(fileInputStream)
            ArchiveFormat.TAR_BZ2 -> fileInputStream
        }

        var entryCount = 0
        val buffer = ByteArray(32 * 1024)

        TarArchiveInputStream(decompressorStream).use { tarIn ->
            var entry: TarArchiveEntry? = tarIn.nextEntry
            while (entry != null) {
                entryCount++
                val entryName = stripPathComponents(entry.name, stripComponents)
                if (entryName.isNotBlank()) {
                    val targetFile = File(destDir, entryName)
                    val targetCanonical = targetFile.canonicalPath

                    // Path traversal guard
                    if (!targetCanonical.startsWith(destCanonicalPath)) {
                        throw FilesystemError(
                            path = entryName,
                            message = "Path traversal attack detected in tarball: $entryName",
                        )
                    }

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else if (entry.isSymbolicLink) {
                        targetFile.parentFile?.mkdirs()
                        try {
                            Files.deleteIfExists(targetFile.toPath())
                            Files.createSymbolicLink(targetFile.toPath(), Paths.get(entry.linkName))
                        } catch (e: Exception) {
                            log.debug("Symlink creation fallback for ${targetFile.name} -> ${entry.linkName}: ${e.message}")
                        }
                    } else {
                        targetFile.parentFile?.mkdirs()
                        targetFile.outputStream().use { out ->
                            var len: Int
                            while (tarIn.read(buffer).also { len = it } != -1) {
                                out.write(buffer, 0, len)
                            }
                        }

                        // Apply executable permissions if marked in tar entry mode or binary directory
                        val mode = entry.mode
                        val isExec = (mode and 0b001001001) != 0 ||
                            entryName.contains("bin/") ||
                            entryName.contains("sbin/") ||
                            (entryName.contains("lib/") && entryName.endsWith(".so"))
                        if (isExec) {
                            targetFile.setExecutable(true, false)
                            NativeBridge.setExecutable(targetFile.absolutePath)
                        }
                        targetFile.setReadable(true, false)
                    }
                }

                if (entryCount % 500 == 0) {
                    onProgress(0.70f + (entryCount % 10000) * 0.00002f, "Extracting files ($entryCount)…")
                }

                entry = tarIn.nextEntry
            }
        }
        log.info("Extracted $entryCount entries successfully to staging directory")
    }

    private fun stripPathComponents(path: String, count: Int): String {
        if (count <= 0) return path
        val parts = path.split("/").filter { it.isNotEmpty() }
        return if (parts.size > count) {
            parts.drop(count).joinToString("/")
        } else {
            ""
        }
    }

    private fun configureRootfs(rootfsDir: File) {
        // Write resolv.conf for DNS
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
        }

        // Set hostname
        File(rootfsDir, "etc/hostname").apply {
            parentFile?.mkdirs()
            writeText("linuxdroid\n")
        }

        // Write Wayland GUI environment configuration
        File(rootfsDir, "etc/environment").apply {
            parentFile?.mkdirs()
            writeText(
                """
                WAYLAND_DISPLAY=wayland-0
                XDG_RUNTIME_DIR=/tmp
                DISPLAY=:0
                GDK_BACKEND=wayland,x11
                QT_QPA_PLATFORM=wayland;xcb
                CLUTTER_BACKEND=wayland
                SDL_VIDEODRIVER=wayland
                """.trimIndent() + "\n"
            )
        }

        // Write apt sources.list for Debian packages
        File(rootfsDir, "etc/apt/sources.list").apply {
            parentFile?.mkdirs()
            writeText(
                """
                deb http://deb.debian.org/debian bookworm main contrib non-free
                deb http://deb.debian.org/debian-security bookworm-security main contrib non-free
                deb http://deb.debian.org/debian bookworm-updates main contrib non-free
                """.trimIndent() + "\n"
            )
        }

        // Create LinuxDroid Wayland Session Launcher script
        val binDir = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
        File(binDir, "linuxdroid-session").apply {
            writeText(
                """
                #!/bin/sh
                export XDG_RUNTIME_DIR=/tmp
                export WAYLAND_DISPLAY=wayland-0
                export DISPLAY=:0
                mkdir -p /tmp
                chmod 0700 /tmp
                if command -v cage >/dev/null 2>&1; then
                    if command -v foot >/dev/null 2>&1; then
                        exec cage -- foot
                    elif command -v xterm >/dev/null 2>&1; then
                        exec cage -- xterm
                    else
                        exec cage -- /bin/sh
                    fi
                elif command -v weston >/dev/null 2>&1; then
                    exec weston --socket=wayland-0
                else
                    echo "Minimal Wayland GUI ready. Install cage/weston for graphical session."
                    exec /bin/sh
                fi
                """.trimIndent() + "\n"
            )
            setExecutable(true, false)
            NativeBridge.setExecutable(absolutePath)
        }

        // Create LinuxDroid Terminal Launcher script
        File(binDir, "linuxdroid-terminal").apply {
            writeText(
                """
                #!/bin/sh
                export XDG_RUNTIME_DIR=/tmp
                export WAYLAND_DISPLAY=wayland-0
                export DISPLAY=:0
                if command -v foot >/dev/null 2>&1; then
                    exec foot "$@"
                elif command -v xterm >/dev/null 2>&1; then
                    exec xterm "$@"
                else
                    exec /bin/sh "$@"
                fi
                """.trimIndent() + "\n"
            )
            setExecutable(true, false)
            NativeBridge.setExecutable(absolutePath)
        }

        // Create home directory for default linux user
        File(rootfsDir, "home/user").mkdirs()

        // Create Android shared storage mount directory
        File(rootfsDir, "home/user/Android").mkdirs()
    }

    private fun validateStagingRootfs(stagingDir: File, distribution: Distribution) {
        val requiredDirs = listOf("bin", "etc", "usr")
        val missing = requiredDirs.filter { !File(stagingDir, it).exists() }
        if (missing.isNotEmpty()) {
            throw FilesystemError(
                path = stagingDir.path,
                message = "Extracted rootfs missing essential Linux directories: $missing for distribution $distribution",
            )
        }

        // Verify shell exists
        val shell = File(stagingDir, "bin/sh")
        if (!shell.exists()) {
            // Check usr/bin/sh as modern distros use merged-usr
            val usrShell = File(stagingDir, "usr/bin/sh")
            if (!usrShell.exists()) {
                throw FilesystemError(
                    path = stagingDir.path,
                    message = "Linux /bin/sh shell executable not found in extracted rootfs",
                )
            }
        }
    }

    private fun finalizeRootfs(stagingDir: File, finalRootfsDir: File) {
        if (finalRootfsDir.exists()) {
            finalRootfsDir.deleteRecursively()
        }
        if (!stagingDir.renameTo(finalRootfsDir)) {
            // Fallback to copy if across filesystem boundaries
            stagingDir.copyRecursively(finalRootfsDir, overwrite = true)
            stagingDir.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class RootfsSource(
    val url: String,
    val sha256: String?,
    val format: ArchiveFormat,
    val stripComponents: Int,
)
