package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.BackgroundMonitorRestartPolicy
import com.hermeswebui.android.background.ReconnectNotificationPolicy
import com.hermeswebui.android.background.ReconnectRetryPolicy
import com.hermeswebui.android.background.ReconnectSessionCookiePolicy
import org.junit.Test

class BackgroundMonitorPolicyTest {
    @Test
    fun `restart recovers only a trusted same-origin target while enabled`() {
        val recovered = BackgroundMonitorRestartPolicy.recover(
            enabled = true,
            configuredServerUrl = "https://hermes.example",
            lastLoadedUrl = "https://hermes.example/session-42",
            isTrustedUrl = { it.startsWith("https://hermes.example") }
        )
        assertThat(recovered?.sessionId).isEqualTo("session-42")
        assertThat(
            BackgroundMonitorRestartPolicy.recover(
                true,
                "https://hermes.example",
                "https://evil.example/session-42"
            ) { it.startsWith("https://hermes.example") }?.currentUrl
        ).isEqualTo("https://hermes.example")
        assertThat(
            BackgroundMonitorRestartPolicy.recover(
                false,
                "https://hermes.example",
                "https://hermes.example/session-42"
            ) { true }
        ).isNull()
    }

    @Test
    fun `retry schedule is exponential-like and bounded`() {
        assertThat((0..6).map(ReconnectRetryPolicy::delayMs))
            .containsExactly(2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 60_000L, 60_000L)
            .inOrder()
    }

    @Test
    fun `background notification channel id is stable and separate from user alerts`() {
        assertThat(ReconnectNotificationPolicy.CHANNEL_ID)
            .isEqualTo("hermes_background_activity")
        assertThat(ReconnectNotificationPolicy.CHANNEL_ID)
            .isNotEqualTo("hermes_webui_notifications")
    }

    @Test
    fun `current cookie takes precedence and initial cookie is only a fallback`() {
        assertThat(
            ReconnectSessionCookiePolicy.cookieHeader(
                currentCookie = "session=rotated",
                initialCookie = "session=initial"
            )
        ).isEqualTo("session=rotated")
        assertThat(
            ReconnectSessionCookiePolicy.cookieHeader(
                currentCookie = null,
                initialCookie = "session=initial"
            )
        ).isEqualTo("session=initial")
        assertThat(
            ReconnectSessionCookiePolicy.cookieHeader(
                currentCookie = "",
                initialCookie = "session=initial"
            )
        ).isEqualTo("session=initial")
    }
}
