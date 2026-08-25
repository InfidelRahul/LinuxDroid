package com.linuxdroid.core.filesystem

import com.linuxdroid.core.model.PathTraversalError
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Validates filesystem paths to prevent path traversal and other attacks.
 *
 * All user-supplied or environment-supplied paths MUST be validated
 * through this class before any filesystem operation.
 */
object PathValidator {

    /**
     * Ensures that [path] resolves to a location inside [basePath].
     *
     * @throws PathTraversalError if path escapes basePath
     */
    @Throws(PathTraversalError::class)
    fun requireInsideBase(path: String, basePath: String) {
        val base = Paths.get(basePath).toAbsolutePath().normalize()
        val resolved = base.resolve(path).toAbsolutePath().normalize()
        if (!resolved.startsWith(base)) {
            throw PathTraversalError(attemptedPath = path, basePath = basePath)
        }
    }

    /**
     * Returns the canonical (normalized, absolute) form of [path]
     * verified to be inside [basePath].
     *
     * @throws PathTraversalError if path escapes basePath
     */
    @Throws(PathTraversalError::class)
    fun canonicalize(path: String, basePath: String): String {
        val base = Paths.get(basePath).toAbsolutePath().normalize()
        val resolved = base.resolve(path).toAbsolutePath().normalize()
        if (!resolved.startsWith(base)) {
            throw PathTraversalError(attemptedPath = path, basePath = basePath)
        }
        return resolved.toString()
    }

    /**
     * Returns true if [path] is safely inside [basePath].
     */
    fun isSafelyInside(path: String, basePath: String): Boolean {
        return try {
            requireInsideBase(path, basePath)
            true
        } catch (_: PathTraversalError) {
            false
        }
    }

    /**
     * Validates that a Linux path (inside the chroot) does not start with
     * disallowed patterns.
     */
    fun validateLinuxPath(path: String): Boolean {
        if (path.contains("\u0000")) return false // null bytes
        if (path.contains("../")) return false // directory traversal attempt
        return true
    }
}
