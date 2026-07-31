package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.ReconnectBackgroundPolicy
import org.junit.Test

class ReconnectBackgroundPolicyTest {
    @Test
    fun `enabled foreground service runs continuously across activity visibility changes`() {
        assertThat(
            ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = true
            )
        ).isTrue()
    }

    @Test
    fun `disabled foreground service tears down`() {
        assertThat(
            ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = false
            )
        ).isFalse()

        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = false
            )
        ).isFalse()
    }

    @Test
    fun `activity stop preserves enabled monitoring and only disabled mode cancels retry`() {
        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = true
            )
        ).isTrue()
        assertThat(
            ReconnectBackgroundPolicy.shouldCancelAutoRetryOnStop(
                backgroundReconnectEnabled = true
            )
        ).isFalse()
        assertThat(
            ReconnectBackgroundPolicy.shouldCancelAutoRetryOnStop(
                backgroundReconnectEnabled = false
            )
        ).isTrue()
    }

    @Test
    fun `with exit latch clear foreground service policy follows the user toggle`() {
        val cases = listOf(
            true to true,
            false to false
        )

        cases.forEach { (enabled, expectedRun) ->
            assertThat(
                ReconnectBackgroundPolicy.shouldRunForegroundService(
                    backgroundReconnectEnabled = enabled
                )
            ).isEqualTo(expectedRun)
        }
    }

    @Test
    fun `enabled monitoring is not runnable while exit latch is set`() {
        assertThat(
            ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = true,
                exitedUntilExplicitLaunch = true
            )
        ).isFalse()
        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = true,
                exitedUntilExplicitLaunch = true
            )
        ).isFalse()
        assertThat(
            ReconnectBackgroundPolicy.shouldCancelAutoRetryOnStop(
                backgroundReconnectEnabled = true,
                exitedUntilExplicitLaunch = true
            )
        ).isTrue()
    }
}
