package com.linuxdroid.core.package_mgr

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApplicationManagerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `parseDesktopFile parses standard desktop entry correctly`() {
        runBlocking {
            val desktopFile = tempFolder.newFile("gedit.desktop").apply {
                writeText(
                    """
                    [Desktop Entry]
                    Name=Text Editor
                    Comment=Edit text files
                    Exec=gedit %U
                    Terminal=false
                    Type=Application
                    Icon=org.gnome.gedit
                    Categories=GNOME;GTK;Utility;TextEditor;
                    """.trimIndent()
                )
            }

            val storage = mockk<EnvironmentStorage>()
            val appManager = DefaultApplicationManager(storage)

            val app = appManager.parseDesktopFile(desktopFile)
            assertThat(app).isNotNull()
            assertThat(app?.name).isEqualTo("Text Editor")
            assertThat(app?.executable).isEqualTo("gedit")
            assertThat(app?.comment).isEqualTo("Edit text files")
            assertThat(app?.iconName).isEqualTo("org.gnome.gedit")
            assertThat(app?.isTerminal).isFalse()
            assertThat(app?.categories).containsExactly("GNOME", "GTK", "Utility", "TextEditor")
        }
    }

    @Test
    fun `parseDesktopFile ignores NoDisplay entries`() {
        runBlocking {
            val desktopFile = tempFolder.newFile("hidden.desktop").apply {
                writeText(
                    """
                    [Desktop Entry]
                    Name=Internal Helper
                    Exec=helper
                    NoDisplay=true
                    """.trimIndent()
                )
            }

            val storage = mockk<EnvironmentStorage>()
            val appManager = DefaultApplicationManager(storage)

            val app = appManager.parseDesktopFile(desktopFile)
            assertThat(app).isNull()
        }
    }
}

