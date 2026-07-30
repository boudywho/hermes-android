package com.hermeswebui.android.webview

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hermeswebui.android.core.security.UrlOrigins
import com.hermeswebui.android.webui.HermesWebUiScripts

class HermesThemeColorCoordinator(
    private val applyColor: (Int) -> Unit
) {
    private var webView: WebView? = null
    private var configuredServerUrl: String = ""
    private var scriptHandler: ScriptHandler? = null

    @SuppressLint("RequiresFeature")
    fun install(view: WebView, serverUrl: String) {
        uninstallBridge()
        configuredServerUrl = serverUrl
        val origin = UrlOrigins.documentStartOriginRule(serverUrl) ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return
        }
        var listenerInstalled = false
        try {
            WebViewCompat.addWebMessageListener(
                view,
                ThemeColorPolicy.BRIDGE_NAME,
                setOf(origin)
            ) { _, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame ||
                    !UrlOrigins.hasSameOrigin(sourceOrigin.toString(), configuredServerUrl)
                ) {
                    return@addWebMessageListener
                }
                ThemeColorPolicy.parseMessage(message.data)?.let(applyColor)
            }
            listenerInstalled = true
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                view,
                HermesWebUiScripts.themeColorScript,
                setOf(origin)
            )
            webView = view
        } catch (_: RuntimeException) {
            if (listenerInstalled) {
                runCatching {
                    WebViewCompat.removeWebMessageListener(view, ThemeColorPolicy.BRIDGE_NAME)
                }
            }
            scriptHandler = null
            webView = null
        }
    }

    fun reset() {
        applyColor(ThemeColorPolicy.DEFAULT_COLOR)
    }

    fun close() {
        uninstallBridge()
        reset()
    }

    private fun uninstallBridge() {
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        webView?.let { view ->
            runCatching {
                WebViewCompat.removeWebMessageListener(view, ThemeColorPolicy.BRIDGE_NAME)
            }
        }
        webView = null
    }
}
