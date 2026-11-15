package com.secman.crowdstrike

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * The SLA rule: overdue once max(first detection, patch date) + threshold has passed.
 * Test names use the user-facing story: fix known on day x, detected on day z.
 */
class SlaAnchorTest {

    private val now = LocalDateTime.of(2026, 11, 13, 12, 0)

    @Test
    fun `fix known before detection - clock starts at detection`() {
        val patchDate = now.minusDays(100)
        val detectedAt = now.minusDays(20)

        assertThat(SlaAnchor.of(detectedAt, patchDate)).isEqualTo(detectedAt)
        assertThat(SlaAnchor.isOverdue(detectedAt, patchDate, 30, now)).isFalse()
        assertThat(SlaAnchor.isOverdue(now.minusDays(31), patchDate, 30, now)).isTrue()
    }

    @Test
    fun `detected before any fix existed - clock starts at the patch date`() {
        val detectedAt = now.minusDays(60)
        val patchDate = now.minusDays(10)

        assertThat(SlaAnchor.of(detectedAt, patchDate)).isEqualTo(patchDate)
        assertThat(SlaAnchor.isOverdue(detectedAt, patchDate, 30, now)).isFalse()
    }

    @Test
    fun `no patch date - clock starts at detection`() {
        val detectedAt = now.minusDays(31)

        assertThat(SlaAnchor.of(detectedAt, null)).isEqualTo(detectedAt)
        assertThat(SlaAnchor.isOverdue(detectedAt, null, 30, now)).isTrue()
    }

    @Test
    fun `boundary is strict, matching the backend's sla_anchor_at below threshold`() {
        val exactlyThirtyDays = now.minusDays(30)

        assertThat(SlaAnchor.isOverdue(exactlyThirtyDays, null, 30, now)).isFalse()
        assertThat(SlaAnchor.isOverdue(exactlyThirtyDays.minusSeconds(1), null, 30, now)).isTrue()
    }

    @Test
    fun `unknown detection date is not counted in the pre-import summary`() {
        assertThat(SlaAnchor.isOverdue(null, now.minusDays(400), 30, now)).isFalse()
    }
}
