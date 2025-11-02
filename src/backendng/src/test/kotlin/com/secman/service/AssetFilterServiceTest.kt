package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.ScanRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Unit tests for AssetFilterService
 * Feature: Unified Access Control (Workgroup + AWS Account Mapping)
 *
 * Tests the integration of workgroup-based and AWS account-based access control
 * to ensure users can access assets via:
 * 1. Workgroup membership
 * 2. Manual creation
 * 3. Scan upload
 * 4. AWS account mapping
 */
@MicronautTest
class AssetFilterServiceTest {

    private lateinit var assetRepository: AssetRepository
    private lateinit var vulnerabilityRepository: VulnerabilityRepository
    private lateinit var scanRepository: ScanRepository
    private lateinit var userRepository: UserRepository
    private lateinit var userMappingRepository: UserMappingRepository
    private lateinit var assetFilterService: AssetFilterService

    @BeforeEach
    fun setup() {
        assetRepository = mockk()
        vulnerabilityRepository = mockk()
        scanRepository = mockk()
        userRepository = mockk()
        userMappingRepository = mockk()
        assetFilterService = AssetFilterService(
            assetRepository,
            vulnerabilityRepository,
            scanRepository,
            userRepository,
            userMappingRepository
        )
    }

    @Test
    fun `getAccessibleAssets returns all assets for ADMIN users`() {
        // Given
        val authentication = createMockAuthentication(
            userId = 1L,
            email = "admin@example.com",
            roles = setOf("ADMIN", "USER")
        )
        val allAssets = listOf(
            createAsset(1L, "Asset1"),
            createAsset(2L, "Asset2"),
            createAsset(3L, "Asset3")
        )
        every { assetRepository.findAll() } returns allAssets

        // When
        val result = assetFilterService.getAccessibleAssets(authentication)

        // Then
        assertEquals(3, result.size)
        verify { assetRepository.findAll() }
        verify(exactly = 0) { assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(any(), any(), any()) }
        verify(exactly = 0) { userMappingRepository.findDistinctAwsAccountIdByEmail(any()) }
    }

    @Test
    fun `getAccessibleAssets combines workgroup and AWS account assets for regular users`() {
        // Given
        val userId = 2L
        val userEmail = "user@example.com"
        val authentication = createMockAuthentication(
            userId = userId,
            email = userEmail,
            roles = setOf("USER")
        )

        // Assets from workgroup
        val workgroupAsset1 = createAsset(1L, "WorkgroupAsset1")
        val workgroupAsset2 = createAsset(2L, "WorkgroupAsset2")

        // Assets from AWS account mapping
        val awsAsset1 = createAsset(3L, "AWSAsset1", cloudAccountId = "123456789012")
        val awsAsset2 = createAsset(4L, "AWSAsset2", cloudAccountId = "987654321098")

        every {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId, userId, userId
            )
        } returns listOf(workgroupAsset1, workgroupAsset2)

        every { userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail) } returns listOf(
            "123456789012",
            "987654321098"
        )

        every { assetRepository.findByCloudAccountIdIn(listOf("123456789012", "987654321098")) } returns listOf(
            awsAsset1,
            awsAsset2
        )

        // When
        val result = assetFilterService.getAccessibleAssets(authentication)

        // Then
        assertEquals(4, result.size)
        assertTrue(result.any { it.id == 1L })
        assertTrue(result.any { it.id == 2L })
        assertTrue(result.any { it.id == 3L })
        assertTrue(result.any { it.id == 4L })

        verify {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId,
                userId,
                userId
            )
        }
        verify { userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail) }
        verify { assetRepository.findByCloudAccountIdIn(listOf("123456789012", "987654321098")) }
    }

    @Test
    fun `getAccessibleAssets deduplicates assets that appear in both workgroup and AWS account lists`() {
        // Given
        val userId = 2L
        val userEmail = "user@example.com"
        val authentication = createMockAuthentication(
            userId = userId,
            email = userEmail,
            roles = setOf("USER")
        )

        // Same asset appears in both lists (e.g., user is in workgroup AND has AWS account mapping)
        val duplicateAsset = createAsset(1L, "DuplicateAsset", cloudAccountId = "123456789012")
        val workgroupOnlyAsset = createAsset(2L, "WorkgroupOnly")
        val awsOnlyAsset = createAsset(3L, "AWSOnly", cloudAccountId = "123456789012")

        every {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId, userId, userId
            )
        } returns listOf(duplicateAsset, workgroupOnlyAsset)

        every { userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail) } returns listOf("123456789012")

        every { assetRepository.findByCloudAccountIdIn(listOf("123456789012")) } returns listOf(
            duplicateAsset,  // Same asset
            awsOnlyAsset
        )

        // When
        val result = assetFilterService.getAccessibleAssets(authentication)

        // Then
        assertEquals(3, result.size, "Should deduplicate the asset that appears in both lists")
        assertEquals(1, result.count { it.id == 1L }, "Duplicate asset should appear only once")
        assertTrue(result.any { it.id == 2L })
        assertTrue(result.any { it.id == 3L })
    }

    @Test
    fun `getAccessibleAssets returns only workgroup assets when user has no AWS account mappings`() {
        // Given
        val userId = 2L
        val userEmail = "user@example.com"
        val authentication = createMockAuthentication(
            userId = userId,
            email = userEmail,
            roles = setOf("USER")
        )

        val workgroupAsset = createAsset(1L, "WorkgroupAsset")

        every {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId, userId, userId
            )
        } returns listOf(workgroupAsset)

        every { userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail) } returns emptyList()

        // When
        val result = assetFilterService.getAccessibleAssets(authentication)

        // Then
        assertEquals(1, result.size)
        assertEquals(1L, result[0].id)
        verify { userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail) }
        verify(exactly = 0) { assetRepository.findByCloudAccountIdIn(any()) }
    }

    @Test
    fun `getAccessibleAssets returns only AWS account assets when user has no workgroup access`() {
        // Given
        val userId = 2L
        val userEmail = "user@example.com"
        val authentication = createMockAuthentication(
            userId = userId,
            email = userEmail,
            roles = setOf("USER")
        )

        val awsAsset = createAsset(1L, "AWSAsset", cloudAccountId = "123456789012")

        every {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId, userId, userId
            )
        } returns emptyList()

        every { userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail) } returns listOf("123456789012")

        every { assetRepository.findByCloudAccountIdIn(listOf("123456789012")) } returns listOf(awsAsset)

        // When
        val result = assetFilterService.getAccessibleAssets(authentication)

        // Then
        assertEquals(1, result.size)
        assertEquals(1L, result[0].id)
    }

    @Test
    fun `getAccessibleAssets handles missing email in authentication gracefully`() {
        // Given
        val userId = 2L
        val authentication = createMockAuthentication(
            userId = userId,
            email = null,  // No email in authentication
            roles = setOf("USER")
        )

        val workgroupAsset = createAsset(1L, "WorkgroupAsset")

        every {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId, userId, userId
            )
        } returns listOf(workgroupAsset)

        // When
        val result = assetFilterService.getAccessibleAssets(authentication)

        // Then
        assertEquals(1, result.size)
        verify(exactly = 0) { userMappingRepository.findDistinctAwsAccountIdByEmail(any()) }
        verify(exactly = 0) { assetRepository.findByCloudAccountIdIn(any()) }
    }

    @Test
    fun `getAccessibleAssets sorts results by name`() {
        // Given
        val userId = 2L
        val userEmail = "user@example.com"
        val authentication = createMockAuthentication(
            userId = userId,
            email = userEmail,
            roles = setOf("USER")
        )

        val assetZ = createAsset(1L, "ZAsset")
        val assetA = createAsset(2L, "AAsset")
        val assetM = createAsset(3L, "MAsset")

        every {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId, userId, userId
            )
        } returns listOf(assetZ, assetM)

        every { userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail) } returns listOf("123456789012")

        every { assetRepository.findByCloudAccountIdIn(listOf("123456789012")) } returns listOf(assetA)

        // When
        val result = assetFilterService.getAccessibleAssets(authentication)

        // Then
        assertEquals(3, result.size)
        assertEquals("AAsset", result[0].name)
        assertEquals("MAsset", result[1].name)
        assertEquals("ZAsset", result[2].name)
    }

    @Test
    fun `canAccessAsset returns true for ADMIN users`() {
        // Given
        val authentication = createMockAuthentication(
            userId = 1L,
            email = "admin@example.com",
            roles = setOf("ADMIN")
        )

        // When
        val result = assetFilterService.canAccessAsset(123L, authentication)

        // Then
        assertTrue(result)
    }

    @Test
    fun `canAccessAsset returns true when asset is in user's workgroup`() {
        // Given
        val userId = 2L
        val userEmail = "user@example.com"
        val assetId = 10L
        val authentication = createMockAuthentication(
            userId = userId,
            email = userEmail,
            roles = setOf("USER")
        )

        val accessibleAsset = createAsset(assetId, "AccessibleAsset")

        every {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId, userId, userId
            )
        } returns listOf(accessibleAsset)

        every { userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail) } returns emptyList()

        // When
        val result = assetFilterService.canAccessAsset(assetId, authentication)

        // Then
        assertTrue(result)
    }

    @Test
    fun `canAccessAsset returns true when asset is accessible via AWS account mapping`() {
        // Given
        val userId = 2L
        val userEmail = "user@example.com"
        val assetId = 10L
        val authentication = createMockAuthentication(
            userId = userId,
            email = userEmail,
            roles = setOf("USER")
        )

        val awsAsset = createAsset(assetId, "AWSAsset", cloudAccountId = "123456789012")

        every {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId, userId, userId
            )
        } returns emptyList()

        every { userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail) } returns listOf("123456789012")

        every { assetRepository.findByCloudAccountIdIn(listOf("123456789012")) } returns listOf(awsAsset)

        // When
        val result = assetFilterService.canAccessAsset(assetId, authentication)

        // Then
        assertTrue(result)
    }

    @Test
    fun `canAccessAsset returns false when asset is not accessible via any method`() {
        // Given
        val userId = 2L
        val userEmail = "user@example.com"
        val assetId = 999L  // Not in accessible assets
        val authentication = createMockAuthentication(
            userId = userId,
            email = userEmail,
            roles = setOf("USER")
        )

        val otherAsset = createAsset(10L, "OtherAsset")

        every {
            assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId, userId, userId
            )
        } returns listOf(otherAsset)

        every { userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail) } returns emptyList()

        // When
        val result = assetFilterService.canAccessAsset(assetId, authentication)

        // Then
        assertFalse(result)
    }

    // Helper methods

    private fun createMockAuthentication(userId: Long, email: String?, roles: Set<String>): Authentication {
        return mockk<Authentication>().apply {
            every { attributes } returns mapOf(
                "userId" to userId,
                "email" to email
            )
            every { this@apply.roles } returns roles
            every { name } returns "user$userId"
        }
    }

    private fun createAsset(id: Long, name: String, cloudAccountId: String? = null): Asset {
        return Asset(
            name = name,
            type = "Server",
            ip = "192.168.1.$id",
            owner = "owner@example.com",
            description = "Test asset",
            cloudAccountId = cloudAccountId
        ).apply {
            this.id = id
        }
    }
}
