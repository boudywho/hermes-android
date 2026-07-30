package com.hermeswebui.android

import android.content.pm.ServiceInfo
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.ReconnectForegroundServiceTypePolicy
import org.junit.Test

class ReconnectForegroundServiceTypePolicyTest {
    @Test
    fun `Android 14 and newer use special use foreground service type`() {
        assertThat(
            ReconnectForegroundServiceTypePolicy.foregroundServiceType(34)
        ).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        assertThat(
            ReconnectForegroundServiceTypePolicy.foregroundServiceType(37)
        ).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    @Test
    fun `older supported Android versions retain data sync fallback`() {
        assertThat(
            ReconnectForegroundServiceTypePolicy.foregroundServiceType(33)
        ).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
