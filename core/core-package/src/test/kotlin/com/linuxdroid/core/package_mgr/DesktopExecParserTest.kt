package com.linuxdroid.core.package_mgr

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DesktopExecParserTest {

    @Test
    fun `parse handles simple command without arguments`() {
        val argv = DesktopExecParser.parse("foot")
        assertThat(argv).containsExactly("foot")
    }

    @Test
    fun `parse splits arguments by space`() {
        val argv = DesktopExecParser.parse("weston-terminal -e /bin/bash")
        assertThat(argv).containsExactly("weston-terminal", "-e", "/bin/bash").inOrder()
    }

    @Test
    fun `parse handles double quoted arguments with spaces`() {
        val argv = DesktopExecParser.parse("gedit \"my file with spaces.txt\" --title=\"My Document\"")
        assertThat(argv).containsExactly("gedit", "my file with spaces.txt", "--title=My Document").inOrder()
    }

    @Test
    fun `parse handles single quoted arguments`() {
        val argv = DesktopExecParser.parse("sh -c 'echo \"hello world\"'")
        assertThat(argv).containsExactly("sh", "-c", "echo \"hello world\"").inOrder()
    }

    @Test
    fun `parse expands and strips field codes correctly`() {
        val argv = DesktopExecParser.parse(
            exec = "vlc %U --app-name=%c %%",
            name = "VLC Media Player"
        )
        // %U is stripped, %c expands to name, %% expands to %
        assertThat(argv).containsExactly("vlc", "--app-name=VLC Media Player", "%").inOrder()
    }

    @Test
    fun `parse expands icon field code when provided`() {
        val argv = DesktopExecParser.parse(
            exec = "app %i",
            icon = "/usr/share/icons/app.png"
        )
        assertThat(argv).containsExactly("app", "--icon", "/usr/share/icons/app.png").inOrder()
    }

    @Test
    fun `parse handles backslash escaped characters`() {
        val argv = DesktopExecParser.parse("my\\ app --arg=val\\ space")
        assertThat(argv).containsExactly("my app", "--arg=val space").inOrder()
    }

    @Test
    fun `parse returns empty list for empty or blank exec`() {
        assertThat(DesktopExecParser.parse("")).isEmpty()
        assertThat(DesktopExecParser.parse("   ")).isEmpty()
    }
}
