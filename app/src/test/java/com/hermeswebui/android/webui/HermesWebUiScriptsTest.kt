package com.hermeswebui.android.webui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HermesWebUiScriptsTest {
    @Test
    fun `blob bridge handles trusted and programmatic anchors with ordered bounded chunks`() {
        val script = HermesWebUiScripts.blobDownloadScript

        assertThat(script).contains("window.top !== window")
        assertThat(script).contains("event.isTrusted")
        assertThat(script).contains("HTMLAnchorElement.prototype.click")
        assertThat(script).contains("url.protocol === \"blob:\"")
        assertThat(script).contains("offset += 65536")
        assertThat(script).contains("sequence")
        assertThat(script).doesNotContain("addJavascriptInterface")
    }

    @Test
    fun `theme bridge observes theme meta and emits canonical opaque colors`() {
        val script = HermesWebUiScripts.themeColorScript

        assertThat(script).contains("window.top !== window")
        assertThat(script).contains("meta[name=\"theme-color\"]")
        assertThat(script).contains("MutationObserver")
        assertThat(script).contains("discovery?.disconnect()")
        assertThat(script).contains("metaObserver.observe(meta, { attributes: true, attributeFilter: [\"content\"] })")
        assertThat(script).contains("pixel[3] !== 255")
        assertThat(script).contains("theme_color")
    }

    @Test
    fun `app settings script preserves folded navigation selectors`() {
        val script = HermesWebUiScripts.appSettingsEntryScript

        assertThat(script).contains("width < 799")
        assertThat(script).contains("button.nav-tab.has-tooltip--bottom[data-tooltip=\"Settings\"]")
        assertThat(script).contains(".mobile-nav button[data-tooltip=\"Settings\"]")
        assertThat(script).contains(".bottom-nav button[data-tooltip=\"Settings\"]")
        assertThat(script).contains("findCompactSettingsAnchor() || findAnchorByKind('settings') || findAnchorByKind('help')")
    }

    @Test
    fun `app settings script routes to native application settings deep link`() {
        val script = HermesWebUiScripts.appSettingsEntryScript

        assertThat(script).contains("var appSettingsHref = 'hermes://app/settings';")
        assertThat(script).contains("window.location.href = appSettingsHref;")
        assertThat(script).contains("Application Settings")
    }

    @Test
    fun `notification bridge builder injects bridge name and permission`() {
        val script = HermesWebUiScripts.buildNotificationBridgeScript(
            bridgeName = "HermesAndroidNotifications",
            initialPermission = "granted"
        )

        assertThat(script).contains("var bridgeName = \"HermesAndroidNotifications\";")
        assertThat(script).contains("var initialPermission = \"granted\";")
        assertThat(script).contains("window.__hermesAndroidSetNotificationPermission")
    }

    @Test
    fun `viewport fix script injects CSS custom properties for viewport dimensions`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("root.style.setProperty('--vh',")
        assertThat(script).contains("root.style.setProperty('--dvh',")
        assertThat(script).contains("root.style.setProperty('--viewport-height',")
        assertThat(script).contains("root.style.setProperty('--viewport-width',")
    }

    @Test
    fun `viewport fix script uses generic collapse detection instead of explicit selectors`() {
        val script = HermesWebUiScripts.viewportFixScript

        // Generic detection heuristics
        assertThat(script).contains("isCollapsedElement")
        assertThat(script).contains("scrollHeight")
        assertThat(script).contains("rect.height")
        assertThat(script).contains("hasOverflowMismatch")
        
        // Performance guards
        assertThat(script).contains("MAX_REPAIRS_PER_SCAN")
        assertThat(script).contains("MIN_SCAN_INTERVAL_MS")
        
        // Repair tracking attribute
        assertThat(script).contains("data-hermes-android-vh-repaired")
    }

    @Test
    fun `viewport fix script excludes primary chat surface from generic repairs`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("shouldSkipRepairForElement")
        assertThat(script).contains("el.closest('.messages, #messages, [data-testid=\"messages\"]')")
        assertThat(script).contains("avoid chat-window flicker")
    }

    @Test
    fun `viewport fix script keeps visible repaired panels from oscillating`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("function updateRepair(el, viewport)")
        assertThat(script).contains("function clearRepairIfHidden(el)")
        assertThat(script).contains("make the panel oscillate")
        assertThat(script).doesNotContain("clearRepairIfHealthy")
    }

    @Test
    fun `viewport fix script includes baseline CSS for layout containers`() {
        val script = HermesWebUiScripts.viewportFixScript

        // Root sizing
        assertThat(script).contains("html, body { min-height:")
        assertThat(script).contains("body { overflow-x: hidden")
        
        // Flex container helpers
        assertThat(script).contains(".layout, .rail, .sidebar, #sessionList, .messages { min-height: 0")
        
        // Settings page fix
        assertThat(script).contains(".main.showing-settings .main-view { max-height: none")
    }
}
