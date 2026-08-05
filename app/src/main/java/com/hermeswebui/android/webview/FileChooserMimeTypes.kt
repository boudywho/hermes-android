package com.hermeswebui.android.webview

import java.util.Locale

internal object FileChooserMimeTypes {
    private val mimeComponent = "[a-z0-9!#$&^_.+-]+"
    private val mimePattern = Regex("^(?:\\*/\\*|$mimeComponent/(?:\\*|$mimeComponent))$")
    private val imageMimePattern = Regex("^image/(?:\\*|$mimeComponent)$")

    fun normalize(acceptTypes: Array<out String>?): Array<String> {
        val tokens = splitTokens(acceptTypes)
        if (tokens.isEmpty() || tokens.any { !mimePattern.matches(it) }) {
            return arrayOf("*/*")
        }
        return tokens.toTypedArray()
    }

    fun requestsImage(acceptTypes: Array<out String>?): Boolean {
        val tokens = splitTokens(acceptTypes)
        return tokens.isEmpty() || tokens.any { it == "*/*" || imageMimePattern.matches(it) }
    }

    private fun splitTokens(acceptTypes: Array<out String>?): List<String> {
        return acceptTypes.orEmpty()
            .flatMap { it.split(',', ';') }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
