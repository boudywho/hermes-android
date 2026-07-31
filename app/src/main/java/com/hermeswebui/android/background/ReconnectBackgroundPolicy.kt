package com.hermeswebui.android.background

internal object ReconnectBackgroundPolicy {
    internal fun shouldRunForegroundService(
        backgroundReconnectEnabled: Boolean,
        exitedUntilExplicitLaunch: Boolean = false
    ): Boolean {
        return backgroundReconnectEnabled && !exitedUntilExplicitLaunch
    }

    internal fun shouldKeepAlive(
        backgroundReconnectEnabled: Boolean,
        exitedUntilExplicitLaunch: Boolean = false
    ): Boolean {
        return shouldRunForegroundService(
            backgroundReconnectEnabled = backgroundReconnectEnabled,
            exitedUntilExplicitLaunch = exitedUntilExplicitLaunch
        )
    }

    internal fun shouldCancelAutoRetryOnStop(
        backgroundReconnectEnabled: Boolean,
        exitedUntilExplicitLaunch: Boolean = false
    ): Boolean {
        return !shouldKeepAlive(
            backgroundReconnectEnabled = backgroundReconnectEnabled,
            exitedUntilExplicitLaunch = exitedUntilExplicitLaunch
        )
    }
}
