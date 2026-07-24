package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.domain.TailscaleEndpointDetector
import org.junit.Test

class TailscaleEndpointDetectorTest {
    @Test
    fun `detects ts net hostname`() {
        assertThat(TailscaleEndpointDetector.isTailscaleUrl("https://node.tailabc.ts.net")).isTrue()
    }

    @Test
    fun `detects tailscale cgnat ipv4`() {
        assertThat(TailscaleEndpointDetector.isTailscaleUrl("http://100.101.102.103:8080")).isTrue()
    }

    @Test
    fun `detects tailscale ula ipv6`() {
        assertThat(TailscaleEndpointDetector.isTailscaleUrl("https://[fd7a:115c:a1e0::12]")).isTrue()
    }

    @Test
    fun `ignores non tailscale hosts`() {
        assertThat(TailscaleEndpointDetector.isTailscaleUrl("https://hermes.example.com")).isFalse()
        assertThat(TailscaleEndpointDetector.isTailscaleUrl("https://192.168.1.12")).isFalse()
    }
}
