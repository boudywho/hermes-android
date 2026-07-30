package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.webview.ThemeColorPolicy
import org.junit.Test

class ThemeColorPolicyTest {
    @Test
    fun `accepts only canonical opaque rgb messages`() {
        assertThat(
            ThemeColorPolicy.parseMessage("""{"type":"theme_color","color":"#12AbEF"}""")
        ).isEqualTo(0xFF12ABEF.toInt())
        assertThat(ThemeColorPolicy.parseMessage("""{"type":"theme_color","color":"red"}"""))
            .isNull()
        assertThat(
            ThemeColorPolicy.parseMessage(
                """{"type":"theme_color","color":"#123456","extra":true}"""
            )
        ).isNull()
    }

    @Test
    fun `icon contrast follows validated chrome luminance`() {
        assertThat(ThemeColorPolicy.useDarkIcons(0xFFFFFFFF.toInt())).isTrue()
        assertThat(ThemeColorPolicy.useDarkIcons(0xFF000000.toInt())).isFalse()
    }
}
