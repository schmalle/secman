package com.secman.service

import com.secman.dto.AssetInterventionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Covers the shared per-asset aggregate and the intervention-status truth table.
 *
 * The SQL-shape assertions moved here from AccountVulnsServiceTest when the query was extracted
 * out of AccountVulnsService and WorkgroupVulnsService, so both callers are covered by one test.
 */
class AssetVulnCountsQueryTest {

    private val entityManager = mockk<EntityManager>()
    private val configService = mockk<VulnerabilityConfigService>()
    private val query = mockk<Query>()

    private val subject = AssetVulnCountsQuery(entityManager, configService)

    private fun counts(
        total: Int = 0, critical: Int = 0, high: Int = 0, medium: Int = 0,
        low: Int = 0, unknown: Int = 0, excepted: Int = 0, nonExcepted: Int = 0,
        nonExceptedOverdue: Int = 0
    ) = AssetVulnCountsQuery.AssetVulnCounts(
        total, critical, high, medium, low, unknown, excepted, nonExcepted, nonExceptedOverdue
    )

    // ---------------------------------------------------------------- status truth table

    @Test
    fun `no vulnerabilities at all is GREEN`() {
        assertThat(counts().status).isEqualTo(AssetInterventionStatus.GREEN)
    }

    @Test
    fun `every vulnerability excepted is GREEN even when critical`() {
        // The whole point of the suggestion: excepted findings need no manual intervention.
        assertThat(counts(total = 5, critical = 5, excepted = 5, nonExcepted = 0).status)
            .isEqualTo(AssetInterventionStatus.GREEN)
    }

    @Test
    fun `non-excepted findings inside the window are YELLOW`() {
        assertThat(counts(total = 3, high = 3, nonExcepted = 3, nonExceptedOverdue = 0).status)
            .isEqualTo(AssetInterventionStatus.YELLOW)
    }

    @Test
    fun `a single overdue non-excepted finding is RED`() {
        assertThat(counts(total = 1, high = 1, nonExcepted = 1, nonExceptedOverdue = 1).status)
            .isEqualTo(AssetInterventionStatus.RED)
    }

    @Test
    fun `overdue wins over recent when both are present`() {
        assertThat(counts(total = 9, nonExcepted = 9, nonExceptedOverdue = 1).status)
            .isEqualTo(AssetInterventionStatus.RED)
    }

    @Test
    fun `excepted overdue findings do not make an asset RED`() {
        // An old finding that somebody has formally accepted is not an action item.
        assertThat(counts(total = 4, excepted = 4, nonExcepted = 0, nonExceptedOverdue = 0).status)
            .isEqualTo(AssetInterventionStatus.GREEN)
    }

    // ---------------------------------------------------------------- roll-up

    @Test
    fun `parent status is the worst child status`() {
        assertThat(
            AssetInterventionStatus.worstOf(
                listOf(AssetInterventionStatus.GREEN, AssetInterventionStatus.RED, AssetInterventionStatus.YELLOW)
            )
        ).isEqualTo(AssetInterventionStatus.RED)

        assertThat(
            AssetInterventionStatus.worstOf(
                listOf(AssetInterventionStatus.GREEN, AssetInterventionStatus.YELLOW)
            )
        ).isEqualTo(AssetInterventionStatus.YELLOW)
    }

    @Test
    fun `an empty group rolls up to GREEN`() {
        assertThat(AssetInterventionStatus.worstOf(emptyList())).isEqualTo(AssetInterventionStatus.GREEN)
    }

    // ---------------------------------------------------------------- validation

    @Test
    fun `counts that reconcile are valid`() {
        assertThat(counts(total = 2, high = 2, excepted = 1, nonExcepted = 1, nonExceptedOverdue = 1).isValid())
            .isTrue()
    }

    @Test
    fun `overdue count exceeding non-excepted count is invalid`() {
        // Guards the SQL: overdue is a strict subset of non-excepted.
        assertThat(counts(total = 1, high = 1, nonExcepted = 1, nonExceptedOverdue = 2).isValid())
            .isFalse()
    }

    // ---------------------------------------------------------------- query shape

    @Test
    fun `query selects exception and overdue columns and joins asset`() {
        val sql = slot<String>()
        every { entityManager.createNativeQuery(capture(sql)) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.resultList } returns emptyList<Any>()

        subject.countByAsset(listOf(42L), LocalDateTime.now().minusDays(30))

        assertThat(sql.captured).contains("excepted_count", "non_excepted_count", "non_excepted_overdue_count")
        // ExceptionMatchSql resolves a.ip / a.cloud_account_id / a.os_version, so the JOIN is
        // mandatory — its absence is what left the workgroup view without exception counts.
        assertThat(sql.captured).contains("JOIN asset a ON v.asset_id = a.id")
        // SLA anchor, not scan_timestamp alone: scan_timestamp is refreshed on every re-import.
        assertThat(sql.captured).contains("COALESCE(v.first_seen_at, v.scan_timestamp) < :thresholdDate")
        // NO_EDR exceptions must never suppress a vulnerability.
        assertThat(sql.captured).contains("e.kind = 'VULNERABILITY'")
    }

    @Test
    fun `threshold date is bound as a parameter`() {
        val threshold = LocalDateTime.now().minusDays(30)
        every { entityManager.createNativeQuery(any<String>()) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.resultList } returns emptyList<Any>()

        subject.countByAsset(listOf(1L), threshold)

        io.mockk.verify { query.setParameter("thresholdDate", threshold) }
        io.mockk.verify { query.setParameter("assetIds", listOf(1L)) }
    }

    @Test
    fun `empty asset list short-circuits without touching the database`() {
        val result = subject.countByAsset(emptyList(), LocalDateTime.now())

        assertThat(result).isEmpty()
        io.mockk.verify(exactly = 0) { entityManager.createNativeQuery(any<String>()) }
    }

    @Test
    fun `rows are mapped into counts including the overdue column`() {
        every { entityManager.createNativeQuery(any<String>()) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.resultList } returns listOf(
            // assetId, total, critical, high, medium, low, unknown, excepted, nonExcepted, overdue
            arrayOf(42L, 3L, 1L, 2L, 0L, 0L, 0L, 1L, 2L, 1L)
        )

        val result = subject.countByAsset(listOf(42L), LocalDateTime.now().minusDays(30))

        val counts = result.getValue(42L)
        assertThat(counts.total).isEqualTo(3)
        assertThat(counts.excepted).isEqualTo(1)
        assertThat(counts.nonExcepted).isEqualTo(2)
        assertThat(counts.nonExceptedOverdue).isEqualTo(1)
        assertThat(counts.status).isEqualTo(AssetInterventionStatus.RED)
    }

    @Test
    fun `threshold days comes from the shared reminder configuration`() {
        every { configService.getReminderOneDays() } returns 45

        assertThat(subject.thresholdDays()).isEqualTo(45)
    }
}
