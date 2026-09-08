package com.secman.relay

import com.secman.dto.IntegrationSummaryDto
import com.secman.service.IntegrationReadService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IntegrationRelaySnapshotTest {
    @Test fun `integration section exposes aggregate counts only and requires admin`() {
        val reads = mockk<IntegrationReadService>()
        every { reads.globalSummary() } returns IntegrationSummaryDto(2, 8, 3, 1, 2, 2, 14)
        val builder = RelaySnapshotBuilder(mockk(), mockk(), mockk(), mockk(), mockk(), reads)
        val snapshot = builder.build("test", listOf("integrations"))
        assertEquals(mapOf("scanners" to 2L, "totalSubjects" to 8L, "healthySubjects" to 3L,
            "failedSubjects" to 1L, "unscannedSubjects" to 2L, "staleSubjects" to 2L,
            "openFindings" to 14L), snapshot.sections["integrations"])
        assertEquals(listOf("ADMIN"), snapshot.policy["integrations"]?.requiredRoles)
        assertTrue("integrations" in RelaySnapshotBuilder.ALL_SECTIONS)
    }
}
