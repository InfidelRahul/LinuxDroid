package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.DesktopConfig
import com.linuxdroid.core.model.SessionId
import org.junit.Test

class DesktopSettingsTest {

    @Test
    fun `defaults resolve to weston with the linuxdroid shell and no xwayland`() {
        val settings = DesktopSettings.from(DesktopConfig())

        assertThat(settings.compositorId).isEqualTo(CompositorId.WESTON)
        assertThat(settings.shellMode).isEqualTo(ShellMode.LINUXDROID_SHELL)
        assertThat(settings.xwaylandEnabled).isFalse()
    }

    @Test
    fun `blank compositor name falls back to weston instead of failing`() {
        val settings = DesktopSettings.from(DesktopConfig(waylandCompositor = "   "))
        assertThat(settings.compositorId).isEqualTo(CompositorId.WESTON)
    }

    @Test
    fun `alternative compositor is honoured so weston stays replaceable`() {
        val settings = DesktopSettings.from(DesktopConfig(waylandCompositor = "cage"))
        assertThat(settings.compositorId).isEqualTo(CompositorId("cage"))
    }

    @Test
    fun `unknown or none shell resolves to compositor-only mode`() {
        assertThat(ShellMode.fromConfigValue("none")).isEqualTo(ShellMode.NONE)
        assertThat(ShellMode.fromConfigValue("")).isEqualTo(ShellMode.NONE)
        assertThat(ShellMode.fromConfigValue("gnome")).isEqualTo(ShellMode.NONE)
        assertThat(ShellMode.fromConfigValue("LinuxDroid-Shell")).isEqualTo(ShellMode.LINUXDROID_SHELL)
    }

    @Test
    fun `xwayland is opt-in`() {
        assertThat(DesktopSettings.from(DesktopConfig(xwaylandEnabled = true)).xwaylandEnabled).isTrue()
        assertThat(DesktopSettings.from(DesktopConfig()).xwaylandEnabled).isFalse()
    }

    @Test
    fun `settings provider is overridable without a configuration framework`() {
        val custom = DesktopSettings(dock = DockSettings(pinnedAppIds = listOf("foot.desktop")))
        val provider = DesktopSettingsProvider { custom }
        assertThat(provider.settings().dock.pinnedAppIds).containsExactly("foot.desktop")
    }
}

class GuiSessionStageTest {

    @Test
    fun `startup stages are ordered environment then services then compositor then shell`() {
        val startup = listOf(
            GuiSessionStage.PREPARING_ENVIRONMENT,
            GuiSessionStage.STARTING_SERVICES,
            GuiSessionStage.STARTING_COMPOSITOR,
            GuiSessionStage.STARTING_SHELL,
            GuiSessionStage.READY,
        )
        assertThat(startup.map { it.ordinal }).isInOrder()
    }

    @Test
    fun `shutdown stages are ordered applications then shell then compositor then services`() {
        val shutdown = listOf(
            GuiSessionStage.STOPPING_APPLICATIONS,
            GuiSessionStage.STOPPING_SHELL,
            GuiSessionStage.STOPPING_COMPOSITOR,
            GuiSessionStage.STOPPING_SERVICES,
            GuiSessionStage.STOPPED,
        )
        assertThat(shutdown.map { it.ordinal }).isInOrder()
    }

    @Test
    fun `only stopped and failed are terminal`() {
        assertThat(GuiSessionStage.entries.filter { it.isTerminal })
            .containsExactly(GuiSessionStage.STOPPED, GuiSessionStage.FAILED)
    }

    @Test
    fun `ready stage alone is not enough - the gui runtime must also be usable`() {
        val sessionId = SessionId("s1")
        val notUsable = GuiSessionStatus(
            sessionId = sessionId,
            stage = GuiSessionStage.READY,
            gui = GuiRuntimeStatus(state = GuiState.STARTING),
        )
        assertThat(notUsable.isReady).isFalse()

        val usable = GuiSessionStatus(
            sessionId = sessionId,
            stage = GuiSessionStage.READY,
            gui = GuiRuntimeStatus(
                state = GuiState.RUNNING,
                compositor = CompositorStatus(CompositorId.WESTON, GuiState.RUNNING),
            ),
        )
        assertThat(usable.isReady).isTrue()
    }
}
