package com.hermeswebui.android.data

internal object BoundedCommit {
    fun run(maxAttempts: Int = 3, commit: () -> Boolean): Boolean {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        repeat(maxAttempts) {
            if (commit()) return true
        }
        return false
    }
}
