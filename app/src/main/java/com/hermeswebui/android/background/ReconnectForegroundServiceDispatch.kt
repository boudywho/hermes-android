package com.hermeswebui.android.background

internal class ReconnectForegroundServiceDispatch<Command> {
    internal enum class Action {
        NONE,
        START_OR_UPDATE,
        STOP
    }

    var serviceRequested: Boolean = false
        private set

    var deliveredCommand: Command? = null
        private set

    fun action(
        enabled: Boolean,
        activityVisible: Boolean,
        command: Command
    ): Action {
        if (!enabled) return Action.STOP
        if (!activityVisible) return Action.NONE
        if (!serviceRequested || deliveredCommand != command) {
            return Action.START_OR_UPDATE
        }
        return Action.NONE
    }

    fun onStartOrUpdateSucceeded(command: Command) {
        serviceRequested = true
        deliveredCommand = command
    }

    fun onStartOrUpdateFailed(): Boolean {
        val shouldCancelRetry = !serviceRequested
        if (shouldCancelRetry) {
            deliveredCommand = null
        }
        return shouldCancelRetry
    }

    fun onStopped() {
        serviceRequested = false
        deliveredCommand = null
    }
}
