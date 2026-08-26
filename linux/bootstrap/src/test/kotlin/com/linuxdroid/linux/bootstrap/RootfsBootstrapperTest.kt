package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class RootfsBootstrapperTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `bootstrap skips when rootfs is already valid`() {
        runBlocking {
            val context = mockk<Context>(relaxed = true)
            val storage = mockk<EnvironmentStorage>()
            val envId = EnvironmentId("test-env")
            val env = Environment(
                metadata = EnvironmentMetadata(
                    id = envId,
                    name = "Test",
                    distribution = Distribution.DEBIAN,
                    architecture = Architecture.ARM64,
                ),
                rootfsPath = "/dummy",
                metadataPath = "/dummy",
            )

            coEvery { storage.verifyRootfs(envId) } returns true

            val bootstrapper = RootfsBootstrapper(context, storage)
            var progressReported = false
            bootstrapper.bootstrapRootfs(env) { p, msg ->
                if (p == 1.0f && msg.contains("already installed")) {
                    progressReported = true
                }
            }

            assertThat(progressReported).isTrue()
        }
    }

    @Test
    fun `tar xz stream compression and decompression works without external binaries`() {
        val testTarXz = tempFolder.newFile("test.tar.xz")
        val extractDir = tempFolder.newFolder("extracted")

        // 1. Create a real .tar.xz archive in-memory/stream
        FileOutputStream(testTarXz).use { fos ->
            XZCompressorOutputStream(fos).use { xzos ->
                TarArchiveOutputStream(xzos).use { tarOut ->
                    val binShData = "#!/bin/sh\necho hello".toByteArray()
                    val entry = TarArchiveEntry("rootfs/bin/sh").apply {
                        size = binShData.size.toLong()
                        mode = 0b111101101 // rwxr-xr-x (0755)
                    }
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(binShData)
                    tarOut.closeArchiveEntry()

                    val etcData = "nameserver 8.8.8.8\n".toByteArray()
                    val etcEntry = TarArchiveEntry("rootfs/etc/resolv.conf").apply {
                        size = etcData.size.toLong()
                        mode = 0b110100100 // rw-r--r-- (0644)
                    }
                    tarOut.putArchiveEntry(etcEntry)
                    tarOut.write(etcData)
                    tarOut.closeArchiveEntry()
                }
            }
        }

        // 2. Decompress and extract using pure streaming decompressor
        BufferedInputStream(FileInputStream(testTarXz)).use { bis ->
            XZCompressorInputStream(bis).use { xzin ->
                TarArchiveInputStream(xzin).use { tarIn ->
                    var entry = tarIn.nextEntry
                    while (entry != null) {
                        val parts = entry.name.split("/").filter { it.isNotEmpty() }
                        val strippedName = parts.drop(1).joinToString("/") // stripComponents = 1
                        if (strippedName.isNotEmpty()) {
                            val targetFile = File(extractDir, strippedName)
                            targetFile.parentFile?.mkdirs()
                            targetFile.outputStream().use { out ->
                                tarIn.copyTo(out)
                            }
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
        }

        // 3. Verify extracted contents
        val extractedSh = File(extractDir, "bin/sh")
        val extractedEtc = File(extractDir, "etc/resolv.conf")
        assertThat(extractedSh.exists()).isTrue()
        assertThat(extractedSh.readText()).isEqualTo("#!/bin/sh\necho hello")
        assertThat(extractedEtc.exists()).isTrue()
        assertThat(extractedEtc.readText()).isEqualTo("nameserver 8.8.8.8\n")
    }
}

