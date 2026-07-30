package com.hermeswebui.android.webview

import com.hermeswebui.android.core.security.UrlOrigins

object WebViewRestorePolicy {
    fun mayAttemptRestore(
        savedServerUrl: String?,
        savedCurrentUrl: String?,
        configuredServerUrl: String,
        hasExplicitNavigationIntent: Boolean,
        stateInvalidated: Boolean,
        isAllowedUrl: (String) -> Boolean,
        isDashboardUrl: (String) -> Boolean
    ): Boolean {
        if (hasExplicitNavigationIntent || stateInvalidated) return false
        if (savedServerUrl.isNullOrBlank() || savedCurrentUrl.isNullOrBlank()) return false
        if (!UrlOrigins.hasSameOrigin(savedServerUrl, configuredServerUrl)) return false
        if (!UrlOrigins.hasSameOrigin(savedCurrentUrl, configuredServerUrl)) return false
        if (!isAllowedUrl(savedCurrentUrl) || isDashboardUrl(savedCurrentUrl)) return false
        return true
    }

    fun areHistoryUrlsTrusted(
        urls: List<String>,
        configuredServerUrl: String,
        isAllowedUrl: (String) -> Boolean,
        isDashboardUrl: (String) -> Boolean
    ): Boolean {
        if (urls.isEmpty()) return false
        return urls.all { url ->
            UrlOrigins.hasSameOrigin(url, configuredServerUrl) &&
                isAllowedUrl(url) &&
                !isDashboardUrl(url)
        }
    }
}
