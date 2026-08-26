package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class ElfValidatorTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `validateElf validates valid ARM64 ELF header`() {
        val elfFile = tempFolder.newFile("test_arm64.so")
        FileOutputStream(elfFile).use { fos ->
            val header = ByteArray(64)
            // Magic: 0x7F 'E' 'L' 'F'
            header[0] = 0x7F.toByte()
            header[1] = 'E'.code.toByte()
            header[2] = 'L'.code.toByte()
            header[3] = 'F'.code.toByte()
            // 64-bit
            header[4] = 0x02
            // Little Endian
            header[5] = 0x01
            // Version
            header[6] = 0x01
            // e_machine: EM_AARCH64 (0xB7 = 183) at offset 18
            header[18] = 0xB7.toByte()
            header[19] = 0x00
            fos.write(header)
        }

        val (valid, detail) = ElfValidator.validateElf(elfFile, "arm64-v8a")
        assertThat(valid).isTrue()
        assertThat(detail).contains("Valid 64-bit ELF")
    }

    @Test
    fun `validateElf detects ABI mismatch`() {
        val elfFile = tempFolder.newFile("test_x86_64.so")
        FileOutputStream(elfFile).use { fos ->
            val header = ByteArray(64)
            header[0] = 0x7F.toByte()
            header[1] = 'E'.code.toByte()
            header[2] = 'L'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = 0x02
            header[5] = 0x01
            header[6] = 0x01
            // e_machine: EM_X86_64 (0x3E = 62)
            header[18] = 0x3E.toByte()
            header[19] = 0x00
            fos.write(header)
        }

        // Test with arm64-v8a target
        val (valid, detail) = ElfValidator.validateElf(elfFile, "arm64-v8a")
        assertThat(valid).isFalse()
        assertThat(detail).contains("does not match ABI arm64-v8a")
    }

    @Test
    fun `validateElf rejects invalid magic`() {
        val nonElfFile = tempFolder.newFile("text.txt").apply {
            writeText("This is not an ELF binary")
        }

        val (valid, detail) = ElfValidator.validateElf(nonElfFile, "arm64-v8a")
        assertThat(valid).isFalse()
        assertThat(detail).contains("Invalid ELF magic")
    }
}

