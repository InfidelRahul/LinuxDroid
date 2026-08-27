package com.linuxdroid.core.runtime

import com.linuxdroid.core.model.RuntimeSpec
import java.io.File

/**
 * Dedicated builder that translates a [RuntimeSpec] deterministically into a native command line.
 */
interface RuntimeCommandBuilder {
    /**
     * Builds the deterministic command line argument list for execution.
     *
     * @param spec The full immutable runtime specification.
     * @param executableOverride Optional path to the native executable binary.
     * @return List of command line arguments with the executable as the first element.
     */
    fun build(spec: RuntimeSpec, executableOverride: File? = null): List<String>
}
