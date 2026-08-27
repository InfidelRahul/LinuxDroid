package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Aarch64PointerNormalizationTest {

    private fun normalizeAarch64Address(address: Long): Long {
        // Equivalent to normalize_tracee_address on AArch64 ((word_t)(addr) & 0x00FFFFFFFFFFFFFFULL)
        return address and 0x00FFFFFFFFFFFFFFL
    }

    @Test
    fun `normal untagged pointer remains unchanged`() {
        val canonicalAddress = 0x00000078de2db6f0L
        val normalized = normalizeAarch64Address(canonicalAddress)
        assertThat(normalized).isEqualTo(canonicalAddress)
    }

    @Test
    fun `tagged AArch64 pointer 0xb4000078de2db6f0 normalizes to canonical address`() {
        // Tagged address from Android 16 Scudo allocator: 0xb4000078de2db6f0
        val taggedAddress = -0x4bffff8721d24910L // 0xb4000078de2db6f0 in signed two's complement Long
        val expectedCanonical = 0x00000078de2db6f0L

        val normalized = normalizeAarch64Address(taggedAddress)
        assertThat(normalized).isEqualTo(expectedCanonical)
    }

    @Test
    fun `tagged AArch64 pointer 0xb400007c4165ec40 normalizes to canonical address`() {
        val taggedAddress = -0x4bffff83be9a13c0L // 0xb400007c4165ec40
        val expectedCanonical = 0x0000007c4165ec40L

        val normalized = normalizeAarch64Address(taggedAddress)
        assertThat(normalized).isEqualTo(expectedCanonical)
    }

    @Test
    fun `various top-byte tags are cleanly stripped`() {
        val canonicalBase = 0x00007fffffffe000L
        val tags = listOf(0x01L, 0x0AL, 0x42L, 0x80L, 0xAAL, 0xB4L, 0xCEL, 0xFFL)

        for (tag in tags) {
            val taggedAddress = canonicalBase or (tag shl 56)
            val normalized = normalizeAarch64Address(taggedAddress)
            assertThat(normalized).isEqualTo(canonicalBase)
        }
    }

    @Test
    fun `null address remains null`() {
        val nullAddress = 0x0L
        val normalized = normalizeAarch64Address(nullAddress)
        assertThat(normalized).isEqualTo(0L)
    }
}
