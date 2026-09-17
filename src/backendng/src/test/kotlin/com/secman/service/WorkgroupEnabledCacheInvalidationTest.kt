package com.secman.service

import com.secman.domain.Criticality
import com.secman.domain.Workgroup
import com.secman.domain.WorkgroupAccessChangedEvent
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupAdDomainRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.repository.WorkgroupRepository
import io.micronaut.context.event.ApplicationEventPublisher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional

class WorkgroupEnabledCacheInvalidationTest {

    private val workgroupRepository = mockk<WorkgroupRepository>()
    private val publisher = mockk<ApplicationEventPublisher<WorkgroupAccessChangedEvent>>(relaxed = true)
    private val service = WorkgroupService(
        workgroupRepository = workgroupRepository,
        userRepository = mockk<UserRepository>(),
        assetRepository = mockk<AssetRepository>(),
        workgroupAwsAccountRepository = mockk<WorkgroupAwsAccountRepository>(),
        workgroupAdDomainRepository = mockk<WorkgroupAdDomainRepository>(),
        validationService = mockk<WorkgroupValidationService>(),
        workgroupAccessChangedPublisher = publisher
    )

    @Test
    fun `enabled-state change invalidates MCP asset access cache`() {
        val workgroup = Workgroup(id = 11L, name = "status-change", criticality = Criticality.MEDIUM)
        every { workgroupRepository.findById(11L) } returns Optional.of(workgroup)
        every { workgroupRepository.update(workgroup) } returns workgroup

        service.updateWorkgroup(id = 11L, enabled = false)

        verify(exactly = 1) {
            publisher.publishEvent(match { it.workgroupIds == setOf(11L) })
        }
    }

    @Test
    fun `non-status update does not invalidate MCP asset access cache`() {
        val workgroup = Workgroup(id = 12L, name = "rename-only", criticality = Criticality.MEDIUM)
        every { workgroupRepository.findById(12L) } returns Optional.of(workgroup)
        every { workgroupRepository.existsByNameIgnoreCase("renamed") } returns false
        every { workgroupRepository.update(workgroup) } returns workgroup

        service.updateWorkgroup(id = 12L, name = "renamed")

        verify(exactly = 0) { publisher.publishEvent(any()) }
    }
}
