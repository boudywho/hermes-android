package com.hermeswebui.android.domain

import com.hermeswebui.android.core.security.UrlOrigins

object TailscaleEndpointDetector {
    fun isTailscaleUrl(url: String): Boolean {
        val host = UrlOrigins.hostFrom(url)?.lowercase() ?: return false
        return isTailscaleHost(host)
    }

    internal fun isTailscaleHost(host: String): Boolean {
        val normalized = host
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .lowercase()
        if (normalized.isBlank()) return false
        if (normalized.endsWith(".ts.net")) return true
        if (isTailscaleCgnatIpv4(normalized)) return true
        return normalized.startsWith("fd7a:115c:a1e0:")
    }

    private fun isTailscaleCgnatIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && octets[1] in 64..127
    }
}
