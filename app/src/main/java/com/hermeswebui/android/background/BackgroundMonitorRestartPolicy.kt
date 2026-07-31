package com.hermeswebui.android.background

import com.hermeswebui.android.core.security.UrlOrigins

internal object BackgroundMonitorRestartPolicy {
    data class RecoveredTarget(
        val serverUrl: String,
        val currentUrl: String,
        val sessionId: String?
    )

    fun recover(
        enabled: Boolean,
        exitedUntilExplicitLaunch: Boolean,
        configuredServerUrl: String,
        lastLoadedUrl: String?,
        isTrustedUrl: (String) -> Boolean
    ): RecoveredTarget? {
        if (!enabled || exitedUntilExplicitLaunch || configuredServerUrl.isBlank()) return null
        val current = lastLoadedUrl
            ?.takeIf { UrlOrigins.hasSameOrigin(it, configuredServerUrl) }
            ?.takeIf(isTrustedUrl)
            ?: configuredServerUrl.takeIf(isTrustedUrl)
            ?: return null
        return RecoveredTarget(
            serverUrl = configuredServerUrl,
            currentUrl = current,
            sessionId = ReconnectSessionStreamSupport.sessionIdFromUrl(current)
        )
    }
}
