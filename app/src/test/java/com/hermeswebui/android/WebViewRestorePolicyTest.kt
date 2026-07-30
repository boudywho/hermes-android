package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.webview.WebViewRestorePolicy
import org.junit.Test

class WebViewRestorePolicyTest {
    @Test
    fun `accepts same configured origin trusted current route`() {
        assertThat(
            WebViewRestorePolicy.mayAttemptRestore(
                savedServerUrl = "https://hermes.example",
                savedCurrentUrl = "https://hermes.example/session-1",
                configuredServerUrl = "https://hermes.example/",
                hasExplicitNavigationIntent = false,
                stateInvalidated = false,
                isAllowedUrl = { true },
                isDashboardUrl = { false }
            )
        ).isTrue()
    }

    @Test
    fun `rejects explicit intents stale profiles dashboards and untrusted routes`() {
        fun decision(
            savedServer: String = "https://hermes.example",
            current: String = "https://hermes.example/session-1",
            explicit: Boolean = false,
            invalidated: Boolean = false,
            allowed: Boolean = true,
            dashboard: Boolean = false
        ) = WebViewRestorePolicy.mayAttemptRestore(
            savedServer,
            current,
            "https://hermes.example",
            explicit,
            invalidated,
            { allowed },
            { dashboard }
        )
        assertThat(decision(explicit = true)).isFalse()
        assertThat(decision(invalidated = true)).isFalse()
        assertThat(decision(savedServer = "https://other.example")).isFalse()
        assertThat(decision(current = "https://other.example/session")).isFalse()
        assertThat(decision(allowed = false)).isFalse()
        assertThat(decision(dashboard = true)).isFalse()
    }

    @Test
    fun `rejects restored history containing provider dashboard or external entries`() {
        val configured = "https://hermes.example"
        assertThat(
            WebViewRestorePolicy.areHistoryUrlsTrusted(
                listOf("$configured/", "$configured/session-1"),
                configured,
                { true },
                { false }
            )
        ).isTrue()
        assertThat(
            WebViewRestorePolicy.areHistoryUrlsTrusted(
                listOf("$configured/session-1", "https://identity.example/authorize"),
                configured,
                { true },
                { false }
            )
        ).isFalse()
        assertThat(
            WebViewRestorePolicy.areHistoryUrlsTrusted(
                listOf("$configured/session-1", "$configured/dashboard"),
                configured,
                { true },
                { it.endsWith("/dashboard") }
            )
        ).isFalse()
    }
}
