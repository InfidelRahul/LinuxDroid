package com.linuxdroid.linux.bootstrap

import java.io.File

/**
 * Validates the structural integrity of an extracted Linux root filesystem.
 */
class DistributionValidator {

    /**
     * Validates that [rootfsDir] contains standard POSIX Linux root directories and a usable shell.
     */
    fun validateRootfs(rootfsDir: File): Boolean {
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) return false

        // Check required root directories
        val requiredDirs = listOf("bin", "etc", "dev", "proc", "sys", "tmp")
        val hasDirs = requiredDirs.all { File(rootfsDir, it).exists() || File(rootfsDir, "usr/$it").exists() }
        if (!hasDirs) return false

        // Check executable shell
        val shLocations = listOf(
            File(rootfsDir, "bin/sh"),
            File(rootfsDir, "usr/bin/sh"),
            File(rootfsDir, "bin/bash"),
            File(rootfsDir, "usr/bin/bash"),
        )
        return shLocations.any { it.exists() && it.length() > 0 }
    }
}
