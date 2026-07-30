package com.hermeswebui.android.background

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo

internal object ReconnectForegroundServiceTypePolicy {
    internal const val SPECIAL_USE_MIN_SDK = 34

    @SuppressLint("InlinedApi")
    internal fun foregroundServiceType(sdkInt: Int): Int {
        return if (sdkInt >= SPECIAL_USE_MIN_SDK) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
    }
}
