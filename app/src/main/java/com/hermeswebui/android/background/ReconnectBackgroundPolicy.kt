package com.hermeswebui.android.background

internal object ReconnectBackgroundPolicy {
    internal fun shouldRunForegroundService(
        backgroundReconnectEnabled: Boolean,
        activityVisible: Boolean
    ): Boolean {
        return backgroundReconnectEnabled && !activityVisible
    }

    internal fun shouldKeepAlive(
        backgroundReconnectEnabled: Boolean,
        activityVisible: Boolean
    ): Boolean {
        return backgroundReconnectEnabled && !activityVisible
    }

    internal fun shouldCancelAutoRetryOnStop(
        backgroundReconnectEnabled: Boolean,
        activityVisible: Boolean
    ): Boolean {
        return !shouldKeepAlive(
            backgroundReconnectEnabled = backgroundReconnectEnabled,
            activityVisible = activityVisible
        )
    }
}
