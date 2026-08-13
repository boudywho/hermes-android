package com.hermeswebui.android.webui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HermesWebUiScriptsTest {
    @Test
    fun `download bridge handles trusted and programmatic anchors with ordered bounded chunks`() {
        val script = HermesWebUiScripts.blobDownloadScript

        assertThat(script).contains("window.top !== window")
        assertThat(script).contains("event.isTrusted")
        assertThat(script).contains("HTMLAnchorElement.prototype.click")
        assertThat(script).contains("url.protocol === \"blob:\"")
        assertThat(script).contains("type: \"download\"")
        assertThat(script).contains("url.origin === window.location.origin")
        assertThat(script).contains("filename.trim() !== \"\"")
        assertThat(script).contains("event.preventDefault()")
        assertThat(script).contains("offset += 65536")
        assertThat(script).contains("sequence")
        assertThat(script).doesNotContain("addJavascriptInterface")
    }

    @Test
    fun `theme bridge follows authenticated theme with low overhead observers`() {
        val script = HermesWebUiScripts.themeColorScript

        assertThat(script).contains("window.top !== window")
        assertThat(script).contains("meta#hermes-theme-color[name=\"theme-color\"]")
        assertThat(script).contains("getPropertyValue(\"--sidebar\")")
        assertThat(script).contains("rootObserver.observe(observedRoot")
        assertThat(script).contains("attributeFilter: [\"class\", \"data-skin\", \"style\"]")
        assertThat(script).contains("headObserver.observe(observedHead")
        assertThat(script).contains("requestAnimationFrame(sample)")
        assertThat(script).contains("window.addEventListener(\"pageshow\", scheduleSample)")
        assertThat(script).contains("[50, 250, 1000, 2500]")
        assertThat(script).doesNotContain("discovery?.disconnect()")
        assertThat(script).doesNotContain("observe(document, { childList: true, subtree: true })")
        assertThat(script).doesNotContain("document.body, { childList: true, subtree: true")
        assertThat(script).doesNotContain("setInterval")
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
    fun `viewport fix script skips chat descendants but keeps chat container repairable`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("shouldSkipRepairForElement")
        assertThat(script).contains("el.closest('.messages, #messages, [data-testid=\"messages\"]')")
        assertThat(script).contains("if (el === chatSurface) return false;")
        assertThat(script).contains("avoid chat-window flicker")
    }

    @Test
    fun `viewport fix script repairs only expanded clarification card and inner scroller`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("compactClarification ? 360 : 420")
        assertThat(script).contains(".clarify-card.visible:not(.collapsed), .clarify-card.visible:not(.collapsed) .clarify-inner")
        assertThat(script).contains(".clarify-card.visible:not(.collapsed) .clarify-inner { overflow-y: auto !important; }")
        assertThat(script).contains("without touching its dock")
        assertThat(script).doesNotContain(".clarify-card.collapsed {")
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

    @Test
    fun `viewport observer filters all ordinary chat and composer descendant mutations without layout work`() {
        val script = HermesWebUiScripts.viewportFixScript
        val hotPath = script
            .substringAfter("function isTransientChatOrComposerTarget(target)")
            .substringBefore("function shouldScheduleForMutation(mutation)")

        assertThat(hotPath).contains("current = current.parentElement")
        assertThat(hotPath).contains("id === 'composerWrap'")
        assertThat(hotPath).contains("indexOf(' composer-wrap ')")
        assertThat(hotPath).contains("textarea sizing and send-button state")
        val normalizedHotPath = hotPath.replace(Regex("\\s+"), " ")
        assertThat(normalizedHotPath).contains("if (isComposer) {")
        val composerBranch = normalizedHotPath
            .substringAfter("if (isComposer) {")
            .substringBefore("current = current.parentElement")
        assertThat(composerBranch).contains("return origin !== current && !protectedViewportUi;")
        assertThat(composerBranch).contains("Keep the wrapper structural")
        assertThat(hotPath).doesNotContain("origin.id === 'msg'")
        assertThat(hotPath).doesNotContain("isEditor")
        assertThat(hotPath).doesNotContain("tagName")
        assertThat(hotPath).doesNotContain("contenteditable")
        assertThat(hotPath).doesNotContain("closest(")
        assertThat(hotPath).doesNotContain("querySelector")
        assertThat(hotPath).doesNotContain("getComputedStyle")
        assertThat(hotPath).doesNotContain("getBoundingClientRect")
    }

    @Test
    fun `viewport observer keeps composer and chat wrappers structural`() {
        val script = HermesWebUiScripts.viewportFixScript
        val hotPath = script
            .substringAfter("function isTransientChatOrComposerTarget(target)")
            .substringBefore("function shouldScheduleForMutation(mutation)")

        assertThat(hotPath).contains("return origin !== current && !protectedViewportUi;")
        assertThat(hotPath).contains("The surface itself is structural")
        assertThat(hotPath).contains("Keep the wrapper structural")
    }

    @Test
    fun `viewport observer retains structural and viewport scan triggers`() {
        val script = HermesWebUiScripts.viewportFixScript
        val mutationPolicy = script
            .substringAfter("function shouldScheduleForMutation(mutation)")
            .substringBefore("// Expose for debugging")

        assertThat(mutationPolicy).contains("mutation.type === 'childList'")
        assertThat(mutationPolicy).contains("hasElementNode(mutation.addedNodes)")
        assertThat(mutationPolicy).contains("hasElementNode(mutation.removedNodes)")
        assertThat(mutationPolicy).contains("return !isTransientChatOrComposerTarget(target)")
        assertThat(script).contains("mutations.some(shouldScheduleForMutation)")
        assertThat(script).contains("attributeFilter: ['style', 'class', REPAIRED_ATTR]")
        assertThat(script).contains("window.addEventListener('resize', schedulePolyfill")
        assertThat(script).contains("window.addEventListener('orientationchange'")
        assertThat(script).contains("window.visualViewport.addEventListener('resize', schedulePolyfill")
        assertThat(script).contains("role === 'dialog' || role === 'menu' || role === 'listbox'")
        assertThat(script).contains("clarify|dialog|modal|menu|panel|popover|popup|overlay|sheet|drawer|card|dropdown|autocomplete")
        assertThat(script).contains("if (hasViewportSensitiveMarker(current)) protectedViewportUi = true")
        assertThat(script).contains("origin !== current && !protectedViewportUi")
    }
}
