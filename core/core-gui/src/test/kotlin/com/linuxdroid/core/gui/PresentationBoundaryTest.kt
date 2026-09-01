package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guards the architectural rule that Android-specific types stop at the
 * Android implementation boundary.
 *
 * This is a source-level check rather than a reflective one: it is the import
 * list that decides whether the module is coupled to Android, and a reflective
 * check would need the classes to be loadable against an Android classpath.
 */
class PresentationBoundaryTest {

    private fun guiSources(): List<File> {
        // Resolved relative to the module directory the tests run from, with a
        // fallback for runners rooted at the repository.
        val candidates = listOf(
            File("src/main/kotlin/com/linuxdroid/core/gui"),
            File("core/core-gui/src/main/kotlin/com/linuxdroid/core/gui"),
        )
        val dir = candidates.firstOrNull { it.isDirectory }
        assertThat(dir).isNotNull()
        return dir!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `no gui source imports an android type`() {
        val offenders = mutableListOf<String>()
        for (file in guiSources()) {
            file.readLines()
                .filter { it.trimStart().startsWith("import ") }
                .filter { line ->
                    val imported = line.trim().removePrefix("import ").substringBefore(" as ")
                    imported.startsWith("android.") ||
                        imported.startsWith("androidx.") ||
                        imported.startsWith("dalvik.")
                }
                .forEach { offenders += "${file.name}: ${it.trim()}" }
        }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the frame contracts never name a platform surface type`() {
        // ANativeWindow and Surface must not appear even in signatures, which
        // would mean the abstraction had been shaped around Android.
        val banned = listOf("ANativeWindow", "android.view.Surface", "SurfaceHolder", "SurfaceView")
        val offenders = mutableListOf<String>()
        for (file in guiSources()) {
            val text = file.readText()
            // Strip comments: the docs legitimately mention these names when
            // describing where the boundary is.
            val code = text.lines()
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .filterNot { it.trimStart().startsWith("/*") }
                .joinToString("\n")
            banned.filter { code.contains(it) }.forEach { offenders += "${file.name}: $it" }
        }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the frame sink contract is implementable without android`() {
        // A pure-Kotlin implementation must satisfy the interface; if the
        // contract had drifted toward Android this would not compile.
        val sink = object : FrameSink {
            override val surfaceState = SurfaceLifecycleState.ACTIVE
            override val configuredGeometry: DisplayGeometry? = null
            override suspend fun configure(
                geometry: DisplayGeometry,
                descriptor: FrameDescriptor,
            ): PresentationFailure? = null
            override suspend fun present(frame: CompositorFrame) = PresentResult.Presented
            override suspend fun release() = Unit
        }
        assertThat(sink.surfaceState.canPresent).isTrue()
    }
}
