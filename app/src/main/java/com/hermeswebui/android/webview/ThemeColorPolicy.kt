package com.hermeswebui.android.webview

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.pow

object ThemeColorPolicy {
    const val BRIDGE_NAME = "HermesAndroidThemeColor"
    const val MAX_MESSAGE_CHARS = 64
    const val DEFAULT_COLOR = 0xFF0D0D1A.toInt()
    private val colorPattern = Regex("^#[0-9A-Fa-f]{6}$")

    fun parseMessage(raw: String?): Int? {
        if (raw.isNullOrEmpty() || raw.length > MAX_MESSAGE_CHARS) return null
        val json = try {
            val tokener = JSONTokener(raw)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != 0.toChar()) return null
            value
        } catch (_: JSONException) {
            return null
        }
        if (json.length() != 2 || json.opt("type") != "theme_color") return null
        val color = json.opt("color") as? String ?: return null
        if (!colorPattern.matches(color)) return null
        return 0xFF000000.toInt() or color.substring(1).toInt(16)
    }

    fun useDarkIcons(color: Int): Boolean {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92
            else ((value + 0.055) / 1.055).pow(2.4)
        }
        val luminance =
            0.2126 * linear(color shr 16 and 0xFF) +
                0.7152 * linear(color shr 8 and 0xFF) +
                0.0722 * linear(color and 0xFF)
        return luminance >= 0.179
    }
}
