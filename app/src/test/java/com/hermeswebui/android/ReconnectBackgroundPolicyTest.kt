package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.ReconnectBackgroundPolicy
import org.junit.Test

class ReconnectBackgroundPolicyTest {
    @Test
    fun `foreground service can run for trusted session activity without reconnecting`() {
        assertThat(
            ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = true,
                activityVisible = false
            )
        ).isTrue()

        assertThat(
            ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = true,
                activityVisible = false
            )
        ).isTrue()

        assertThat(
            ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = true,
                activityVisible = true
            )
        ).isFalse()
    }

    @Test
    fun `keepAlive follows toggle and visibility independently of reconnect state`() {
        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = true,
                activityVisible = false
            )
        ).isTrue()

        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = false,
                activityVisible = false
            )
        ).isFalse()

        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = true,
                activityVisible = true
            )
        ).isFalse()

        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = true,
                activityVisible = false
            )
        ).isTrue()
    }

    @Test
    fun `shouldCancelAutoRetryOnStop mirrors inverse keepAlive policy`() {
        val cases = listOf(
            Triple(true, false, true),
            Triple(false, false, true),
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false)
        )

        cases.forEach { (enabled, visible, _) ->
            val keepAlive = ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = enabled,
                activityVisible = visible
            )
            val cancelAutoRetry = ReconnectBackgroundPolicy.shouldCancelAutoRetryOnStop(
                backgroundReconnectEnabled = enabled,
                activityVisible = visible
            )
            assertThat(cancelAutoRetry).isEqualTo(!keepAlive)
        }
    }

    @Test
    fun `foreground service parity stays derived from visibility reconnect toggle and session signals`() {
        data class Case(
            val enabled: Boolean,
            val visible: Boolean,
            val reconnecting: Boolean,
            val expectedRun: Boolean
        )

        val cases = listOf(
            Case(enabled = true, visible = false, reconnecting = true, expectedRun = true),
            Case(enabled = true, visible = false, reconnecting = false, expectedRun = true),
            Case(enabled = true, visible = true, reconnecting = true, expectedRun = false),
            Case(enabled = false, visible = false, reconnecting = true, expectedRun = false)
        )

        cases.forEach { c ->
            assertThat(
                ReconnectBackgroundPolicy.shouldRunForegroundService(
                    backgroundReconnectEnabled = c.enabled,
                    activityVisible = c.visible
                )
            ).isEqualTo(c.expectedRun)
        }
    }
}
