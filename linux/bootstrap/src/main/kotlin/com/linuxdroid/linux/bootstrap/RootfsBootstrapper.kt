package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * RootfsBootstrapper downloads and installs the Linux rootfs for an environment.
 *
 * This class is responsible for Phase 6 (rootless runtime prototype) and
 * Phase 7 (first Linux shell) of the development roadmap.
 *
 * CRITICAL: bootstrapRootfs() NEVER deletes an existing rootfs.
 * If the rootfs already exists, it returns immediately without changes.
 */
class RootfsBootstrapper(
    private val context: Context,
    private val storage: EnvironmentStorage,
) {
    private val log = LinuxDroidLogger(LogSubsystem.BOOTSTRAP)

    companion object {
        /**
         * Distribution configurations for arm64 minimal rootfs archives.
         * Using proot-distro maintained rootfs images.
         */
        private val DISTRIBUTION_SOURCES = mapOf(
            Distribution.DEBIAN to RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.5.0/debian-aarch64-pd-v4.5.0.tar.xz",
                // SHA256 must be verified before production use
                sha256 = null,
                format = ArchiveFormat.TAR_XZ,
                stripComponents = 1,
            ),
            Distribution.UBUNTU to RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.5.0/ubuntu-aarch64-pd-v4.5.0.tar.xz",
                sha256 = null,
                format = ArchiveFormat.TAR_XZ,
                stripComponents = 1,
            ),
        )
    }

    /**
     * Bootstraps the rootfs for the given environment.
     *
     * If the rootfs already exists (has /bin, /etc, /usr), returns immediately.
     * Never recreates or modifies an existing rootfs.
     *
     * Progress is reported through [onProgress] (0.0 to 1.0).
     */
    suspend fun bootstrapRootfs(
        environment: Environment,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val environmentId = environment.id
        log.info("Starting rootfs bootstrap for $environmentId (${environment.distribution})")

        // Never recreate existing rootfs
        val rootfsOk = storage.verifyRootfs(environmentId)
        if (rootfsOk) {
            log.info("Rootfs already exists and is valid for $environmentId. Skipping bootstrap.")
            onProgress(1.0f, "Rootfs already installed")
            return@withContext
        }

        val source = DISTRIBUTION_SOURCES[environment.distribution]
            ?: throw RuntimeError(
                environmentId = environmentId,
                message = "No rootfs source for distribution: ${environment.distribution}",
            )

        // Create directories
        storage.initializeEnvironmentDirs(environmentId)
        val rootfsDir = storage.rootfsDir(environmentId)
        rootfsDir.mkdirs()

        val tmpDir = storage.tmpDir(environmentId)
        val tarball = File(tmpDir, "rootfs.tar.xz")

        try {
            onProgress(0.05f, "Downloading ${environment.distribution.displayName} rootfs…")
            log.info("Downloading rootfs from: ${source.url}")
            downloadFile(source.url, tarball, onProgress)

            source.sha256?.let { expectedSha256 ->
                onProgress(0.7f, "Verifying download…")
                val actual = sha256(tarball)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    tarball.delete()
                    throw RuntimeError(
                        environmentId = environmentId,
                        message = "SHA256 mismatch: expected=$expectedSha256 actual=$actual",
                    )
                }
            }

            onProgress(0.75f, "Extracting rootfs…")
            log.info("Extracting rootfs to: ${rootfsDir.path}")
            extractTarXz(tarball, rootfsDir, source.stripComponents)

            onProgress(0.9f, "Configuring rootfs…")
            configureRootfs(environmentId, rootfsDir)

            onProgress(1.0f, "Installation complete")
            log.info("Rootfs bootstrap complete for $environmentId")

        } catch (e: Exception) {
            log.error("Rootfs bootstrap failed for $environmentId", e)
            // Do NOT delete the rootfs directory if it has content — partial install is better
            // than a deleted rootfs. The user can retry or diagnose.
            throw RuntimeError(
                environmentId = environmentId,
                message = "Bootstrap failed: ${e.message}",
                cause = e,
            )
        } finally {
            // Clean up the tarball (not the rootfs)
            if (tarball.exists()) {
                tarball.delete()
                log.debug("Cleaned up tarball")
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
                val buffer = ByteArray(8 * 1024)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        val fraction = downloaded.toFloat() / totalBytes
                        // Scale to 0.05-0.70
                        onProgress(0.05f + fraction * 0.65f, "Downloading… ${downloaded / 1_048_576}MB")
                    }
                }
            }
        }
    }

    private fun extractTarXz(tarball: File, destDir: File, stripComponents: Int) {
        // Use Android's built-in process execution to run tar
        // tar is available on Android 7+ (API 24+)
        val cmd = buildList {
            add("tar")
            add("-xJf")
            add(tarball.absolutePath)
            add("-C")
            add(destDir.absolutePath)
            if (stripComponents > 0) {
                add("--strip-components=$stripComponents")
            }
        }

        val result = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        val exitCode = result.waitFor()
        if (exitCode != 0) {
            val output = result.inputStream.bufferedReader().readText()
            throw FilesystemError(
                path = tarball.path,
                message = "tar extraction failed (exit=$exitCode): $output",
            )
        }
    }

    private fun configureRootfs(environmentId: EnvironmentId, rootfsDir: File) {
        // Write resolv.conf for DNS
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
        }

        // Set hostname
        File(rootfsDir, "etc/hostname").writeText("linuxdroid\n")

        // Create home directory
        File(rootfsDir, "home/user").mkdirs()

        log.debug("Rootfs configured for $environmentId")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
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

enum class ArchiveFormat { TAR_XZ, TAR_GZ, TAR_BZ2 }
