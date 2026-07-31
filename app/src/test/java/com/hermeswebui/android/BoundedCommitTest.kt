package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.BoundedCommit
import org.junit.Test

class BoundedCommitTest {
    @Test
    fun `commit retries until success then stops`() {
        var attempts = 0

        val committed = BoundedCommit.run {
            attempts += 1
            attempts == 2
        }

        assertThat(committed).isTrue()
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `commit returns false after bounded failures`() {
        var attempts = 0

        val committed = BoundedCommit.run(maxAttempts = 3) {
            attempts += 1
            false
        }

        assertThat(committed).isFalse()
        assertThat(attempts).isEqualTo(3)
    }
}
