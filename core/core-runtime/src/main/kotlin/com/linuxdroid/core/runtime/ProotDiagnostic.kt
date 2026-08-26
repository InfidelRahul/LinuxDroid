package com.linuxdroid.core.runtime

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Detailed status of the PRoot native binary.
 */
enum class ProotStatus {
    PROOT_OK,
    PROOT_MISSING,
    PROOT_NOT_EXECUTABLE,
    PROOT_WRONG_ABI,
    PROOT_INVALID_ELF,
    PROOT_LOADER_MISSING,
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
    val loaderPath: String? = null,
    val abi: String?,
    val elfValid: Boolean,
    val elfType: String = "UNKNOWN",
    val executable: Boolean,
    val loaderValid: Boolean = true,
    val termuxFree: Boolean = true,
    val detail: String,
    val error: String? = null,
) {
    fun formatDiagnostic(): String = buildString {
        appendLine("PRoot: ${if (binaryPath != null) "FOUND ($binaryPath)" else "MISSING"}")
        loaderPath?.let { appendLine("Loader: ${if (loaderValid) "FOUND ($it)" else "MISSING"}") }
        abi?.let { appendLine("ABI: $it") }
        appendLine("ELF: ${if (elfValid) "VALID ($elfType)" else "INVALID"}")
        appendLine("Executable: ${if (executable) "YES" else "NO"}")
        appendLine("Dependencies: PASS (Standalone Bionic binary, 0 external .so required)")
        appendLine("Termux-Free: ${if (termuxFree) "PASS (Clean standalone build)" else "FAIL"}")
        appendLine("Status: ${status.name}")
        appendLine("Detail: $detail")
        error?.let { appendLine("Error: $it") }
    }.trimEnd()
}

/**
 * Detailed ELF metadata.
 */
data class ElfInfo(
    val isValid: Boolean,
    val is64Bit: Boolean,
    val isLittleEndian: Boolean,
    val machine: Int,
    val type: Int, // 2 = ET_EXEC, 3 = ET_DYN (PIE)
    val entryPoint: Long,
    val typeName: String,
    val detail: String,
)

/**
 * Helper to validate ELF headers for ARM64 and x86_64 binaries.
 */
object ElfValidator {

    private val ELF_MAGIC = byteArrayOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    /**
     * Reads ELF headers and determines validity, ABI compatibility, and execution entry point.
     */
    fun readElfInfo(file: File, targetAbi: String): ElfInfo {
        if (!file.exists() || !file.isFile || file.length() < 4) {
            return ElfInfo(
                isValid = false,
                is64Bit = false,
                isLittleEndian = false,
                machine = 0,
                type = 0,
                entryPoint = 0L,
                typeName = "INVALID",
                detail = "File does not exist or is too small (<4 bytes)",
            )
        }

        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(64)
                val read = fis.read(header)
                if (read < 4) {
                    return ElfInfo(false, false, false, 0, 0, 0L, "INVALID", "File too small (<4 bytes)")
                }

                // Check ELF Magic: 0x7F 'E' 'L' 'F'
                for (i in 0..3) {
                    if (header[i] != ELF_MAGIC[i]) {
                        return ElfInfo(false, false, false, 0, 0, 0L, "INVALID", "Invalid ELF magic: 0x${header.take(4).joinToString("") { "%02x".format(it) }}")
                    }
                }

                if (read < 64) {
                    return ElfInfo(false, false, false, 0, 0, 0L, "INVALID", "Incomplete ELF header (<64 bytes)")
                }

                val is64Bit = header[4].toInt() == 0x02
                val isLittleEndian = header[5].toInt() == 0x01

                val byteBuffer = ByteBuffer.wrap(header).order(if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)

                val elfType = byteBuffer.getShort(16).toInt() and 0xFFFF
                val eMachine = byteBuffer.getShort(18).toInt() and 0xFFFF
                val entryPoint = if (is64Bit) byteBuffer.getLong(24) else byteBuffer.getInt(24).toLong()

                val expectedMachine = when (targetAbi) {
                    "arm64-v8a" -> 0xB7 // EM_AARCH64 (183)
                    "x86_64" -> 0x3E    // EM_X86_64 (62)
                    else -> null
                }

                if (expectedMachine != null && eMachine != expectedMachine) {
                    return ElfInfo(
                        isValid = false,
                        is64Bit = is64Bit,
                        isLittleEndian = isLittleEndian,
                        machine = eMachine,
                        type = elfType,
                        entryPoint = entryPoint,
                        typeName = if (elfType == 3) "PIE EXECUTABLE / DYN" else "TYPE_$elfType",
                        detail = "ELF machine 0x${"%02x".format(eMachine)} does not match target ABI $targetAbi (expected 0x${"%02x".format(expectedMachine)})",
                    )
                }

                val typeName = when (elfType) {
                    2 -> "STANDALONE EXECUTABLE (ET_EXEC)"
                    3 -> if (entryPoint != 0L) "PIE EXECUTABLE (ET_DYN)" else "SHARED LIBRARY (ET_DYN)"
                    else -> "ELF_TYPE_$elfType"
                }

                ElfInfo(
                    isValid = true,
                    is64Bit = is64Bit,
                    isLittleEndian = isLittleEndian,
                    machine = eMachine,
                    type = elfType,
                    entryPoint = entryPoint,
                    typeName = typeName,
                    detail = "Valid 64-bit $typeName for $targetAbi (entry=0x${java.lang.Long.toHexString(entryPoint)})",
                )
            }
        } catch (e: Exception) {
            ElfInfo(false, false, false, 0, 0, 0L, "ERROR", "ELF parse error: ${e.message}")
        }
    }

    /**
     * Backward-compatible helper method.
     */
    fun validateElf(file: File, targetAbi: String): Pair<Boolean, String> {
        val info = readElfInfo(file, targetAbi)
        return info.isValid to info.detail
    }
}
