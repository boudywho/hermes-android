package com.hermeswebui.android.background

internal object ReconnectSessionCookiePolicy {
    fun cookieHeader(currentCookie: String?, initialCookie: String?): String? =
        currentCookie?.takeIf { it.isNotBlank() }
            ?: initialCookie?.takeIf { it.isNotBlank() }
}
