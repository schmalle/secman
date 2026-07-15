package com.secman.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.SQLException

class DeadlockRetryTest {

    @Test
    fun `isDeadlockException detects MariaDB error code 1213 anywhere in cause chain`() {
        val deadlock = SQLException("Deadlock found", "40001", 1213)
        val wrapped = RuntimeException("wrapped once", IllegalStateException("wrapped twice", deadlock))

        assertThat(DeadlockRetry.isDeadlockException(deadlock)).isTrue()
        assertThat(DeadlockRetry.isDeadlockException(wrapped)).isTrue()
        assertThat(DeadlockRetry.isDeadlockException(RuntimeException("unrelated"))).isFalse()
    }

    @Test
    fun `withRetry retries on deadlock then returns the successful result`() {
        val deadlock = SQLException("Deadlock found", "40001", 1213)
        var attempts = 0

        val result = DeadlockRetry.withRetry("test op") {
            attempts++
            if (attempts < 3) throw deadlock
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `withRetry throws after exhausting attempts on a persistent deadlock`() {
        val deadlock = SQLException("Deadlock found", "40001", 1213)
        var attempts = 0

        assertThatThrownBy {
            DeadlockRetry.withRetry("doomed op") {
                attempts++
                throw deadlock
            }
        }.isSameAs(deadlock)

        // 5 attempts = 1 initial + 4 retries
        assertThat(attempts).isEqualTo(5)
    }

    @Test
    fun `withRetry does not retry non-deadlock exceptions`() {
        val other = IllegalStateException("unrelated failure")
        var attempts = 0

        assertThatThrownBy {
            DeadlockRetry.withRetry("non-deadlock op") {
                attempts++
                throw other
            }
        }.isSameAs(other)

        assertThat(attempts).isEqualTo(1)
    }
}
