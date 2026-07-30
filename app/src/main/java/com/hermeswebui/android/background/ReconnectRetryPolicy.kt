package com.hermeswebui.android.background

internal object ReconnectRetryPolicy {
    private val delaysMs = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)

    fun delayMs(failureCount: Int): Long =
        delaysMs[failureCount.coerceAtLeast(0).coerceAtMost(delaysMs.lastIndex)]
}
