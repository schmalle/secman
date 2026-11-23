package com.secman.crowdstrike

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * The SLA rule: overdue once first host detection + threshold has passed.
 * Test names use the user-facing story: fix known on day x, detected on day z.
 */
class SlaAnchorTest {

    private val now = LocalDateTime.of(2026, 11, 13, 12, 0)

    @Test
    fun `only host detection starts the overdue clock`() {
        assertThat(SlaAnchor.isOverdue(now.minusDays(60), 30, now)).isTrue()
        assertThat(SlaAnchor.isOverdue(now.minusDays(30), 30, now)).isFalse()
        assertThat(SlaAnchor.isOverdue(now.minusDays(30).minusSeconds(1), 30, now)).isTrue()
        assertThat(SlaAnchor.isOverdue(null, 30, now)).isFalse()
    }
}
