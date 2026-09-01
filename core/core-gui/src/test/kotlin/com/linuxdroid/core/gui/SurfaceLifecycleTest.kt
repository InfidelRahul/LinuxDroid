package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SurfaceLifecycleTest {

    private val log = RecordingGuiLog()
    private fun lifecycle() = SurfaceLifecycle { log }
    private fun geometry(w: Int = 1080, h: Int = 2400) = DisplayGeometry(w, h, 420, 60f)

    @Test
    fun `a fresh lifecycle has no surface and cannot present`() {
        val l = lifecycle()
        assertThat(l.state.value).isEqualTo(SurfaceLifecycleState.NONE)
        assertThat(l.canPresent).isFalse()
        assertThat(l.geometry).isNull()
    }

    @Test
    fun `full create attach activate sequence reaches presentable`() {
        val l = lifecycle()
        assertThat(l.onSurfaceCreated(geometry())).isTrue()
        assertThat(l.state.value).isEqualTo(SurfaceLifecycleState.CREATED)
        // A surface that merely exists is not yet presentable.
        assertThat(l.canPresent).isFalse()

        assertThat(l.onAttached()).isTrue()
        assertThat(l.canPresent).isFalse()

        assertThat(l.onActivated()).isTrue()
        assertThat(l.state.value).isEqualTo(SurfaceLifecycleState.ACTIVE)
        assertThat(l.canPresent).isTrue()
    }

    @Test
    fun `a non-null surface is not automatically presentable`() {
        val l = lifecycle()
        l.onSurfaceCreated(geometry())
        // CREATED and ATTACHED both mean "we have a surface" but neither allows
        // presenting; only the sink confirming configuration does.
        assertThat(SurfaceLifecycleState.CREATED.canPresent).isFalse()
        assertThat(SurfaceLifecycleState.ATTACHED.canPresent).isFalse()
        assertThat(SurfaceLifecycleState.DETACHING.canPresent).isFalse()
        assertThat(SurfaceLifecycleState.DESTROYED.canPresent).isFalse()
        assertThat(SurfaceLifecycleState.ACTIVE.canPresent).isTrue()
    }

    @Test
    fun `destroy from active passes through detaching and clears geometry`() {
        val l = lifecycle()
        l.onSurfaceCreated(geometry()); l.onAttached(); l.onActivated()
        l.onSurfaceDestroyed()
        assertThat(l.state.value).isEqualTo(SurfaceLifecycleState.DESTROYED)
        assertThat(l.canPresent).isFalse()
        assertThat(l.geometry).isNull()
        assertThat(log.messages(GuiLogCategory.GRAPHICS).any { it.contains("DETACHING") }).isTrue()
    }

    @Test
    fun `destroy is idempotent`() {
        val l = lifecycle()
        l.onSurfaceCreated(geometry()); l.onAttached(); l.onActivated()
        l.onSurfaceDestroyed()
        l.onSurfaceDestroyed()
        assertThat(l.state.value).isEqualTo(SurfaceLifecycleState.DESTROYED)
    }

    @Test
    fun `destroying with no surface at all is a no-op`() {
        val l = lifecycle()
        l.onSurfaceDestroyed()
        assertThat(l.state.value).isEqualTo(SurfaceLifecycleState.NONE)
    }

    @Test
    fun `surface can be recreated after destruction and generation advances`() {
        val l = lifecycle()
        l.onSurfaceCreated(geometry()); l.onAttached(); l.onActivated()
        val first = l.generation
        l.onSurfaceDestroyed()

        assertThat(l.onSurfaceCreated(geometry(720, 1280))).isTrue()
        l.onAttached(); l.onActivated()
        assertThat(l.canPresent).isTrue()
        assertThat(l.geometry?.widthPx).isEqualTo(720)
        // The generation bump is what lets in-flight frames be discarded.
        assertThat(l.generation).isEqualTo(first + 1)
    }

    @Test
    fun `a replacement surface without a destroy callback still settles cleanly`() {
        val l = lifecycle()
        l.onSurfaceCreated(geometry()); l.onAttached(); l.onActivated()
        // Android can hand over a new surface without a destroy in between.
        assertThat(l.onSurfaceCreated(geometry(800, 600))).isTrue()
        assertThat(l.state.value).isEqualTo(SurfaceLifecycleState.CREATED)
        assertThat(l.generation).isEqualTo(2)
    }

    @Test
    fun `resize on an active surface suspends presentation until reconfigured`() {
        val l = lifecycle()
        l.onSurfaceCreated(geometry()); l.onAttached(); l.onActivated()
        assertThat(l.canPresent).isTrue()

        assertThat(l.onGeometryChanged(geometry(2400, 1080))).isTrue()
        // Dropping to ATTACHED prevents a frame being drawn at the old size.
        assertThat(l.state.value).isEqualTo(SurfaceLifecycleState.ATTACHED)
        assertThat(l.canPresent).isFalse()
        assertThat(l.geometry?.widthPx).isEqualTo(2400)
    }

    @Test
    fun `resize without a live surface is refused`() {
        val l = lifecycle()
        assertThat(l.onGeometryChanged(geometry())).isFalse()
        l.onSurfaceCreated(geometry()); l.onAttached(); l.onActivated(); l.onSurfaceDestroyed()
        assertThat(l.onGeometryChanged(geometry())).isFalse()
    }

    @Test
    fun `illegal transitions are refused and logged rather than forced`() {
        val l = lifecycle()
        // NONE -> ACTIVE would mean presenting without ever attaching.
        assertThat(l.transitionTo(SurfaceLifecycleState.ACTIVE)).isFalse()
        assertThat(l.state.value).isEqualTo(SurfaceLifecycleState.NONE)
        assertThat(log.hasMessageContaining("rejected illegal surface transition")).isTrue()
    }

    @Test
    fun `destroyed surface cannot jump straight back to active`() {
        val l = lifecycle()
        l.onSurfaceCreated(geometry()); l.onAttached(); l.onActivated(); l.onSurfaceDestroyed()
        assertThat(l.transitionTo(SurfaceLifecycleState.ACTIVE)).isFalse()
        assertThat(l.canPresent).isFalse()
    }

    @Test
    fun `transition to the current state is a no-op success`() {
        val l = lifecycle()
        l.onSurfaceCreated(geometry())
        assertThat(l.transitionTo(SurfaceLifecycleState.CREATED)).isTrue()
        assertThat(l.state.value).isEqualTo(SurfaceLifecycleState.CREATED)
    }
}
