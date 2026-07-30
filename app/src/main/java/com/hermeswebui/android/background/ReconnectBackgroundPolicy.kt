package com.hermeswebui.android.background

internal object ReconnectBackgroundPolicy {
    internal fun shouldRunForegroundService(
        backgroundReconnectEnabled: Boolean
    ): Boolean {
        return backgroundReconnectEnabled
    }

    internal fun shouldKeepAlive(
        backgroundReconnectEnabled: Boolean
    ): Boolean {
        return backgroundReconnectEnabled
    }

    internal fun shouldCancelAutoRetryOnStop(
        backgroundReconnectEnabled: Boolean
    ): Boolean {
        return !shouldKeepAlive(
            backgroundReconnectEnabled = backgroundReconnectEnabled
        )
    }
}
