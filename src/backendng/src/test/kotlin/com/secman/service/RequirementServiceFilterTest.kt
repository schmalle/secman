package com.secman.service

import com.secman.domain.Requirement
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequirementServiceFilterTest {
    @Test
    fun `MCP filtering pages at the repository before applying offset`() {
        val repository = mockk<RequirementRepository>()
        val pageable = slot<Pageable>()
        val rows = (1L..30L).map { Requirement(id = it, internalId = "REQ-$it", shortreq = "Requirement $it") }
        every {
            repository.findCurrentFiltered("cloud", "aws", "", "", capture(pageable))
        } returns Page.of(rows, Pageable.from(0, 30), 100)
        val service = RequirementService(
            repository,
            mockk<RequirementSnapshotRepository>(relaxed = true),
            mockk<RequirementIdService>(relaxed = true)
        )

        val (result, total) = service.filterRequirements(
            search = " cloud ",
            usecase = " aws ",
            limit = 10,
            offset = 20
        )

        assertThat(pageable.captured.size).isEqualTo(30)
        assertThat(result.map { it.id }).containsExactlyElementsOf((21L..30L).toList())
        assertThat(total).isEqualTo(100)
        verify(exactly = 1) { repository.findCurrentFiltered(any(), any(), any(), any(), any()) }
    }
}
