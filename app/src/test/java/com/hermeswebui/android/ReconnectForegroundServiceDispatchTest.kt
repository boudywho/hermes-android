package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.ReconnectForegroundServiceDispatch
import org.junit.Test

class ReconnectForegroundServiceDispatchTest {
    private val initialCommand = Command("https://hermes.example.com", 30)
    private val updatedCommand = initialCommand.copy(pollIntervalSeconds = 60)

    @Test
    fun `initial visible enabled sync starts service`() {
        val dispatch = ReconnectForegroundServiceDispatch<Command>()

        assertThat(
            dispatch.action(
                enabled = true,
                activityVisible = true,
                command = initialCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.START_OR_UPDATE)

        dispatch.onStartOrUpdateSucceeded(initialCommand)

        assertThat(dispatch.serviceRequested).isTrue()
        assertThat(dispatch.deliveredCommand).isEqualTo(initialCommand)
        assertThat(
            dispatch.action(
                enabled = true,
                activityVisible = true,
                command = initialCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.NONE)
    }

    @Test
    fun `background transition retains service without dispatch`() {
        val dispatch = runningDispatch()

        assertThat(
            dispatch.action(
                enabled = true,
                activityVisible = false,
                command = initialCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.NONE)
        assertThat(
            dispatch.action(
                enabled = true,
                activityVisible = true,
                command = initialCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.NONE)
        assertThat(dispatch.serviceRequested).isTrue()
        assertThat(dispatch.deliveredCommand).isEqualTo(initialCommand)
    }

    @Test
    fun `background command change is deferred until resume then latest command is delivered`() {
        val dispatch = runningDispatch()

        assertThat(
            dispatch.action(
                enabled = true,
                activityVisible = false,
                command = updatedCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.NONE)
        assertThat(dispatch.deliveredCommand).isEqualTo(initialCommand)

        assertThat(
            dispatch.action(
                enabled = true,
                activityVisible = true,
                command = updatedCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.START_OR_UPDATE)
        dispatch.onStartOrUpdateSucceeded(updatedCommand)

        assertThat(dispatch.deliveredCommand).isEqualTo(updatedCommand)
    }

    @Test
    fun `disabled sync always stops even without local requested bookkeeping`() {
        val dispatch = ReconnectForegroundServiceDispatch<Command>()

        assertThat(
            dispatch.action(
                enabled = false,
                activityVisible = false,
                command = initialCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.STOP)
        dispatch.onStopped()
        assertThat(
            dispatch.action(
                enabled = false,
                activityVisible = true,
                command = initialCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.STOP)
    }

    @Test
    fun `rejected initial visible start clears requested bookkeeping`() {
        val dispatch = ReconnectForegroundServiceDispatch<Command>()

        assertThat(
            dispatch.action(
                enabled = true,
                activityVisible = true,
                command = initialCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.START_OR_UPDATE)
        val shouldCancelRetry = dispatch.onStartOrUpdateFailed()

        assertThat(dispatch.serviceRequested).isFalse()
        assertThat(dispatch.deliveredCommand).isNull()
        assertThat(shouldCancelRetry).isTrue()
    }

    @Test
    fun `rejected update preserves running bookkeeping and retries when visible`() {
        val dispatch = runningDispatch()

        assertThat(
            dispatch.action(
                enabled = true,
                activityVisible = true,
                command = updatedCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.START_OR_UPDATE)
        val shouldCancelRetry = dispatch.onStartOrUpdateFailed()

        assertThat(dispatch.serviceRequested).isTrue()
        assertThat(dispatch.deliveredCommand).isEqualTo(initialCommand)
        assertThat(shouldCancelRetry).isFalse()
        assertThat(
            dispatch.action(
                enabled = true,
                activityVisible = false,
                command = updatedCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.NONE)
        assertThat(
            dispatch.action(
                enabled = true,
                activityVisible = true,
                command = updatedCommand
            )
        )
            .isEqualTo(ReconnectForegroundServiceDispatch.Action.START_OR_UPDATE)
    }

    private fun runningDispatch(): ReconnectForegroundServiceDispatch<Command> {
        return ReconnectForegroundServiceDispatch<Command>().also {
            it.onStartOrUpdateSucceeded(initialCommand)
        }
    }

    private data class Command(
        val serverUrl: String,
        val pollIntervalSeconds: Int
    )
}
