package com.secman.service

import com.secman.config.MemoryOptimizationConfig
import com.secman.domain.Asset
import com.secman.repository.AssetRepository
import com.secman.repository.ScanRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.WorkgroupAdDomainRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AssetFilterServiceWorkgroupAdDomainTest {

    private val assetRepository: AssetRepository = mockk()
    private val vulnerabilityRepository: VulnerabilityRepository = mockk()
    private val scanRepository: ScanRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val userMappingRepository: UserMappingRepository = mockk()
    private val memoryConfig: MemoryOptimizationConfig = mockk()
    private val awsAccountSharingService: AwsAccountSharingService = mockk()
    private val workgroupAwsAccountRepository: WorkgroupAwsAccountRepository = mockk()
    private val workgroupAdDomainRepository: WorkgroupAdDomainRepository = mockk()

    private val service = AssetFilterService(
        assetRepository,
        vulnerabilityRepository,
        scanRepository,
        userRepository,
        userMappingRepository,
        memoryConfig,
        awsAccountSharingService,
        workgroupAwsAccountRepository,
        workgroupAdDomainRepository
    )

    @Test
    fun `regular user can access assets matching AD domains assigned to their direct workgroups`() {
        val domainAsset = Asset(id = 501L, name = "host1", type = "SERVER", owner = "CrowdStrike Import", adDomain = "corp.example.com")
        every { memoryConfig.lazyLoadingEnabled } returns false
        every {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(10L, 10L, 10L)
        } returns emptyList()
        every { userMappingRepository.findDistinctAwsAccountIdByEmail("user@example.com") } returns emptyList()
        every { userMappingRepository.findDistinctDomainByEmail("user@example.com") } returns emptyList()
        every { awsAccountSharingService.getSharedAwsAccountIdsByEmail("user@example.com") } returns emptyList()
        every { assetRepository.findByOwner("regular") } returns emptyList()
        every { workgroupAwsAccountRepository.findDistinctAwsAccountIdsByUserId(10L) } returns emptyList()
        every { workgroupAdDomainRepository.findDistinctAdDomainsByUserId(10L) } returns listOf("CORP.EXAMPLE.COM")
        every { assetRepository.findByAdDomainInIgnoreCase(listOf("corp.example.com")) } returns listOf(domainAsset)

        val assets = service.getAccessibleAssets(auth())

        assertEquals(listOf(domainAsset), assets)
    }

    private fun auth(): Authentication = mockk {
        every { name } returns "regular"
        every { roles } returns setOf("USER")
        every { attributes } returns mapOf("userId" to "10", "email" to "user@example.com")
    }
}
