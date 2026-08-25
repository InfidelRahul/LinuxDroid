package com.linuxdroid.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for the EnvironmentState state machine.
 *
 * Verifies that:
 * - All valid transitions are accepted
 * - All invalid transitions are rejected
 * - State machine invariants hold
 */
class EnvironmentStateMachineTest {

    private fun makeEnvironment(state: EnvironmentState = EnvironmentState.CREATED): Environment {
        val id = EnvironmentId.generate()
        return Environment(
            metadata = EnvironmentMetadata(
                id = id,
                name = "Test Environment",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            state = state,
            rootfsPath = "/data/environments/${id.value}/rootfs",
            metadataPath = "/data/environments/${id.value}/metadata",
        )
    }

    // ─── Valid transitions ────────────────────────────────────────────────────

    @Test fun `CREATED to INSTALLING is valid`() {
        val env = makeEnvironment(EnvironmentState.CREATED)
        val updated = env.withState(EnvironmentState.INSTALLING)
        assertThat(updated.state).isEqualTo(EnvironmentState.INSTALLING)
    }

    @Test fun `INSTALLING to READY is valid`() {
        val env = makeEnvironment(EnvironmentState.INSTALLING)
        val updated = env.withState(EnvironmentState.READY)
        assertThat(updated.state).isEqualTo(EnvironmentState.READY)
    }

    @Test fun `READY to STARTING is valid`() {
        val env = makeEnvironment(EnvironmentState.READY)
        val updated = env.withState(EnvironmentState.STARTING)
        assertThat(updated.state).isEqualTo(EnvironmentState.STARTING)
    }

    @Test fun `STARTING to RUNNING is valid`() {
        val env = makeEnvironment(EnvironmentState.STARTING)
        val updated = env.withState(EnvironmentState.RUNNING)
        assertThat(updated.state).isEqualTo(EnvironmentState.RUNNING)
    }

    @Test fun `RUNNING to STOPPING is valid`() {
        val env = makeEnvironment(EnvironmentState.RUNNING)
        val updated = env.withState(EnvironmentState.STOPPING)
        assertThat(updated.state).isEqualTo(EnvironmentState.STOPPING)
    }

    @Test fun `STOPPING to STOPPED is valid`() {
        val env = makeEnvironment(EnvironmentState.STOPPING)
        val updated = env.withState(EnvironmentState.STOPPED)
        assertThat(updated.state).isEqualTo(EnvironmentState.STOPPED)
    }

    @Test fun `STOPPED to STARTING is valid`() {
        val env = makeEnvironment(EnvironmentState.STOPPED)
        val updated = env.withState(EnvironmentState.STARTING)
        assertThat(updated.state).isEqualTo(EnvironmentState.STARTING)
    }

    @Test fun `RUNNING to FAILED is valid`() {
        val env = makeEnvironment(EnvironmentState.RUNNING)
        val updated = env.withState(EnvironmentState.FAILED, "Compositor crashed")
        assertThat(updated.state).isEqualTo(EnvironmentState.FAILED)
        assertThat(updated.failureMessage).isEqualTo("Compositor crashed")
    }

    @Test fun `FAILED to RECOVERING is valid`() {
        val env = makeEnvironment(EnvironmentState.FAILED)
        val updated = env.withState(EnvironmentState.RECOVERING)
        assertThat(updated.state).isEqualTo(EnvironmentState.RECOVERING)
    }

    @Test fun `RECOVERING to READY is valid`() {
        val env = makeEnvironment(EnvironmentState.RECOVERING)
        val updated = env.withState(EnvironmentState.READY)
        assertThat(updated.state).isEqualTo(EnvironmentState.READY)
    }

    // ─── Invalid transitions ──────────────────────────────────────────────────

    @Test fun `CREATED to RUNNING is invalid`() {
        val env = makeEnvironment(EnvironmentState.CREATED)
        assertFailsWith<IllegalStateTransitionException> {
            env.withState(EnvironmentState.RUNNING)
        }
    }

    @Test fun `RUNNING to INSTALLING is invalid`() {
        val env = makeEnvironment(EnvironmentState.RUNNING)
        assertFailsWith<IllegalStateTransitionException> {
            env.withState(EnvironmentState.INSTALLING)
        }
    }

    @Test fun `STOPPED to READY is invalid`() {
        val env = makeEnvironment(EnvironmentState.STOPPED)
        assertFailsWith<IllegalStateTransitionException> {
            env.withState(EnvironmentState.READY)
        }
    }

    @Test fun `READY to RUNNING is invalid - must go through STARTING`() {
        val env = makeEnvironment(EnvironmentState.READY)
        assertFailsWith<IllegalStateTransitionException> {
            env.withState(EnvironmentState.RUNNING)
        }
    }

    @Test fun `CREATED to STOPPED is invalid`() {
        val env = makeEnvironment(EnvironmentState.CREATED)
        assertFailsWith<IllegalStateTransitionException> {
            env.withState(EnvironmentState.STOPPED)
        }
    }

    // ─── Invariants ───────────────────────────────────────────────────────────

    @Test fun `EnvironmentId is immutable across state transitions`() {
        val env = makeEnvironment(EnvironmentState.READY)
        val original = env.id
        val updated = env.withState(EnvironmentState.STARTING)
        assertThat(updated.id).isEqualTo(original)
    }

    @Test fun `canStart returns true only for READY and STOPPED`() {
        assertThat(EnvironmentState.READY.canStart()).isTrue()
        assertThat(EnvironmentState.STOPPED.canStart()).isTrue()
        assertThat(EnvironmentState.RUNNING.canStart()).isFalse()
        assertThat(EnvironmentState.STARTING.canStart()).isFalse()
        assertThat(EnvironmentState.CREATED.canStart()).isFalse()
        assertThat(EnvironmentState.FAILED.canStart()).isFalse()
    }

    @Test fun `canStop returns true only for RUNNING and STARTING`() {
        assertThat(EnvironmentState.RUNNING.canStop()).isTrue()
        assertThat(EnvironmentState.STARTING.canStop()).isTrue()
        assertThat(EnvironmentState.READY.canStop()).isFalse()
        assertThat(EnvironmentState.STOPPED.canStop()).isFalse()
        assertThat(EnvironmentState.FAILED.canStop()).isFalse()
    }

    @Test fun `failure message is cleared when transitioning out of FAILED`() {
        val failedEnv = makeEnvironment(EnvironmentState.FAILED).copy(
            state = EnvironmentState.FAILED,
            failureMessage = "Old error"
        )
        val recovering = failedEnv.withState(EnvironmentState.RECOVERING)
        val ready = recovering.withState(EnvironmentState.READY)
        assertThat(ready.failureMessage).isNull()
    }

    @Test fun `EnvironmentId validates format`() {
        assertFailsWith<IllegalArgumentException> { EnvironmentId("") }
        assertFailsWith<IllegalArgumentException> { EnvironmentId("has spaces") }
        assertFailsWith<IllegalArgumentException> { EnvironmentId("has/slash") }
        // Valid IDs
        EnvironmentId("abc123")
        EnvironmentId("env-1_test")
    }
}
