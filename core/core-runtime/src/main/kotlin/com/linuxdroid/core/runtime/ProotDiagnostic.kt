package com.linuxdroid.core.runtime

import java.io.File
import java.io.FileInputStream

/**
 * Detailed status of the PRoot native binary.
 */
enum class ProotStatus {
    PROOT_OK,
    PROOT_MISSING,
    PROOT_NOT_EXECUTABLE,
    PROOT_WRONG_ABI,
    PROOT_INVALID_ELF,
    PROOT_DEPENDENCY_FAILURE,
    PROOT_EXECUTION_DENIED;

    val isReady: Boolean get() = this == PROOT_OK
}

/**
 * Diagnostic result describing PRoot binary validation.
 */
data class ProotDiagnosticResult(
    val status: ProotStatus,
    val binaryPath: String?,
    val abi: String?,
    val elfValid: Boolean,
    val executable: Boolean,
    val detail: String,
    val error: String? = null,
) {
    fun formatDiagnostic(): String = buildString {
        appendLine("PRoot: ${if (binaryPath != null) "FOUND ($binaryPath)" else "MISSING"}")
        abi?.let { appendLine("ABI: $it") }
        appendLine("ELF: ${if (elfValid) "VALID" else "INVALID"}")
        appendLine("EXECUTABLE: ${if (executable) "YES" else "NO"}")
        appendLine("STATUS: ${status.name}")
        appendLine("DETAIL: $detail")
        error?.let { appendLine("ERROR: $it") }
    }.trimEnd()
}

/**
 * Helper to validate ELF headers for ARM64 and x86_64 binaries.
 */
object ElfValidator {

    private val ELF_MAGIC = byteArrayOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    /**
     * Validates ELF magic and matches machine architecture with target ABI.
     */
    fun validateElf(file: File, targetAbi: String): Pair<Boolean, String> {
        if (!file.exists() || !file.isFile || file.length() < 20) {
            return false to "File too small or does not exist"
        }

        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(20)
                val read = fis.read(header)
                if (read < 20) return false to "Could not read ELF header"

                // Check ELF Magic: 0x7F 'E' 'L' 'F'
                for (i in 0..3) {
                    if (header[i] != ELF_MAGIC[i]) {
                        return false to "Invalid ELF magic: 0x${header.take(4).joinToString("") { "%02x".format(it) }}"
                    }
                }

                // Check 64-bit: byte 4 == 0x02
                if (header[4].toInt() != 0x02) {
                    return false to "Not a 64-bit ELF binary (ei_class=${header[4]})"
                }

                // Check Little Endian: byte 5 == 0x01
                if (header[5].toInt() != 0x01) {
                    return false to "Not a little-endian ELF binary"
                }

                // Check Machine Architecture: e_machine at offset 18 (2 bytes little endian)
                val eMachine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                val expectedMachine = when (targetAbi) {
                    "arm64-v8a" -> 0xB7 // EM_AARCH64 (183)
                    "x86_64" -> 0x3E    // EM_X86_64 (62)
                    else -> null
                }

                if (expectedMachine != null && eMachine != expectedMachine) {
                    return false to "ELF machine 0x${"%02x".format(eMachine)} does not match ABI $targetAbi (expected 0x${"%02x".format(expectedMachine)})"
                }

                return true to "Valid 64-bit ELF for $targetAbi (e_machine=0x${"%02x".format(eMachine)})"
            }
        } catch (e: Exception) {
            return false to "Error reading ELF: ${e.message}"
        }
    }
}

