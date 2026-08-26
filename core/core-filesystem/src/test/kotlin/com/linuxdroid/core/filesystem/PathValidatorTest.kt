package com.linuxdroid.core.filesystem

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.PathTraversalError
import org.junit.Test
import org.junit.Assert.assertThrows

class PathValidatorTest {

    private val basePath = "/data/user/0/com.linuxdroid.app/files/environments/env-1/rootfs"

    @Test
    fun `valid relative path resolves inside base`() {
        val safePath = "home/user/document.txt"
        assertThat(PathValidator.isSafelyInside(safePath, basePath)).isTrue()
        val canonical = PathValidator.canonicalize(safePath, basePath)
        assertThat(canonical).startsWith(basePath)
    }

    @Test
    fun `path traversal attempt throws PathTraversalError`() {
        val escapePath = "../../../../../etc/shadow"
        assertThat(PathValidator.isSafelyInside(escapePath, basePath)).isFalse()
        assertThrows(PathTraversalError::class.java) {
            PathValidator.requireInsideBase(escapePath, basePath)
        }
    }

    @Test
    fun `validateLinuxPath rejects null bytes and parent directory traversal`() {
        assertThat(PathValidator.validateLinuxPath("/home/user/file.txt")).isTrue()
        assertThat(PathValidator.validateLinuxPath("/home/user/\u0000file.txt")).isFalse()
        assertThat(PathValidator.validateLinuxPath("/home/user/../../../etc/passwd")).isFalse()
    }
}

