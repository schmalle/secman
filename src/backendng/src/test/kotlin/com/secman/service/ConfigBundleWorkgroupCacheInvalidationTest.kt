package com.secman.service

import com.secman.domain.Criticality
import com.secman.domain.Workgroup
import com.secman.domain.WorkgroupAccessChangedEvent
import com.secman.dto.ConfigBundleDto
import com.secman.dto.ImportBundleRequest
import com.secman.dto.ImportOptions
import com.secman.dto.WorkgroupExportDto
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.FalconConfigRepository
import com.secman.repository.IdentityProviderRepository
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupAdDomainRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.repository.WorkgroupRepository
import io.micronaut.context.event.ApplicationEventPublisher
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import java.util.Optional

class ConfigBundleWorkgroupCacheInvalidationTest {

    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val workgroupRepository = mockk<WorkgroupRepository>(relaxed = true)
    private val publisher = mockk<ApplicationEventPublisher<WorkgroupAccessChangedEvent>>(relaxed = true)
    private val service = ConfigBundleService(
        userRepository = userRepository,
        workgroupRepository = workgroupRepository,
        userMappingRepository = mockk<UserMappingRepository>(relaxed = true),
        identityProviderRepository = mockk<IdentityProviderRepository>(relaxed = true),
        falconConfigRepository = mockk<FalconConfigRepository>(relaxed = true),
        mcpApiKeyRepository = mockk<McpApiKeyRepository>(relaxed = true),
        awsAccountSharingRepository = mockk<AwsAccountSharingRepository>(relaxed = true),
        workgroupAwsAccountRepository = mockk<WorkgroupAwsAccountRepository>(relaxed = true),
        workgroupAdDomainRepository = mockk<WorkgroupAdDomainRepository>(relaxed = true),
        entityManager = mockk<EntityManager>(relaxed = true),
        auditLogService = mockk<AuditLogService>(relaxed = true),
        workgroupAccessChangedPublisher = publisher
    )

    @Test
    fun `configuration bundle enabled-state change invalidates MCP asset access cache`() {
        val existing = Workgroup(id = 21L, name = "imported", criticality = Criticality.MEDIUM)
        every { workgroupRepository.findByNameIgnoreCase("imported") } returns Optional.of(existing)
        every { workgroupRepository.save(any()) } answers { arg<Workgroup>(0) }
        every { userRepository.findByUsername("admin") } returns Optional.empty()
        every { userRepository.findByEmail("admin") } returns Optional.empty()
        val authentication = mockk<Authentication> {
            every { name } returns "admin"
        }

        service.importBundle(
            ImportBundleRequest(
                bundle = ConfigBundleDto(
                    exportedBy = "test",
                    workgroups = listOf(WorkgroupExportDto(name = "imported", enabled = false))
                ),
                options = ImportOptions(skipExisting = false, updateExisting = true)
            ),
            authentication
        )

        verify(exactly = 1) {
            publisher.publishEvent(match { it.workgroupIds == setOf(21L) })
        }
    }
}
