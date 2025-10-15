package com.secman.fixtures

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.domain.Vulnerability
import java.time.LocalDateTime

/**
 * Test fixtures for Account Vulns feature testing
 *
 * Provides factory methods to create test entities with realistic data
 * for contract, integration, and unit tests.
 */
object AccountVulnsTestFixtures {

    /**
     * Create a test User with specified email and roles
     */
    fun createTestUser(
        email: String = "test@example.com",
        roles: Set<User.Role> = setOf(User.Role.USER)
    ): User {
        return User(
            id = null,
            username = email.substringBefore("@"),
            email = email,
            passwordHash = "hashed-password",
            roles = roles.toMutableSet()
        )
    }

    /**
     * Create an admin test User
     */
    fun createAdminUser(email: String = "admin@example.com"): User {
        return createTestUser(email = email, roles = setOf(User.Role.ADMIN))
    }

    /**
     * Create a test UserMapping linking user to AWS account
     */
    fun createUserMapping(
        email: String = "test@example.com",
        awsAccountId: String = "123456789012",
        domain: String = "-NONE-"
    ): UserMapping {
        return UserMapping(
            id = null,
            email = email,
            awsAccountId = awsAccountId,
            domain = domain
        )
    }

    /**
     * Create a test Asset with specified cloudAccountId
     */
    fun createAsset(
        name: String = "test-asset",
        type: String = "SERVER",
        cloudAccountId: String = "123456789012",
        ip: String? = "10.0.0.1",
        owner: String = "test-owner"
    ): Asset {
        return Asset(
            id = null,
            name = name,
            type = type,
            ip = ip,
            owner = owner,
            cloudAccountId = cloudAccountId,
            description = null,
            lastSeen = LocalDateTime.now()
        )
    }

    /**
     * Create a test Vulnerability linked to an Asset
     */
    fun createVulnerability(
        asset: Asset,
        vulnerabilityId: String = "CVE-2024-0001",
        cvssSeverity: String = "High",
        daysOpen: String = "30"
    ): Vulnerability {
        return Vulnerability(
            id = null,
            asset = asset,
            vulnerabilityId = vulnerabilityId,
            cvssSeverity = cvssSeverity,
            vulnerableProductVersions = "Apache 2.4.0",
            daysOpen = daysOpen,
            scanTimestamp = LocalDateTime.now()
        )
    }

    /**
     * Create multiple vulnerabilities with mixed severities for testing Feature 019.
     * Returns 8 vulnerabilities: 2 CRITICAL, 3 HIGH, 1 MEDIUM, 1 LOW, 1 with empty string severity (UNKNOWN)
     *
     * @param asset Asset to attach vulnerabilities to
     * @return List of vulnerabilities with various severity levels
     */
    fun createVulnerabilitiesWithSeverity(asset: Asset): List<Vulnerability> {
        return listOf(
            createVulnerability(asset, "CVE-2024-0001", "CRITICAL", "10"),
            createVulnerability(asset, "CVE-2024-0002", "CRITICAL", "15"),
            createVulnerability(asset, "CVE-2024-0003", "HIGH", "20"),
            createVulnerability(asset, "CVE-2024-0004", "HIGH", "25"),
            createVulnerability(asset, "CVE-2024-0005", "HIGH", "30"),
            createVulnerability(asset, "CVE-2024-0006", "MEDIUM", "35"),
            createVulnerability(asset, "CVE-2024-0007", "LOW", "40"),
            createVulnerability(asset, "CVE-2024-0008", "", "45") // Empty severity (UNKNOWN)
        )
    }
    
    /**
     * Create a vulnerability with NULL severity for testing (Feature 019).
     * Useful for testing UNKNOWN severity handling.
     */
    fun createVulnerabilityWithNullSeverity(asset: Asset): Vulnerability {
        val vuln = createVulnerability(asset, "CVE-2024-9999", "placeholder", "50")
        vuln.cvssSeverity = null // Set to null after creation
        return vuln
    }

    /**
     * Create multiple assets with vulnerabilities for a single AWS account
     * Returns list of assets (vulnerabilities are set via relationship)
     */
    fun createSingleAccountTestData(
        awsAccountId: String = "123456789012",
        assetCount: Int = 5
    ): List<Asset> {
        val assets = mutableListOf<Asset>()
        for (i in 1..assetCount) {
            val asset = createAsset(
                name = "asset-$i",
                type = "SERVER",
                cloudAccountId = awsAccountId,
                ip = "10.0.0.$i"
            )
            assets.add(asset)
        }
        return assets
    }

    /**
     * Create assets across multiple AWS accounts
     * Returns map of awsAccountId → List<Asset>
     */
    fun createMultiAccountTestData(
        accountIds: List<String> = listOf("123456789012", "987654321098"),
        assetsPerAccount: Int = 3
    ): Map<String, List<Asset>> {
        val result = mutableMapOf<String, List<Asset>>()
        accountIds.forEach { accountId ->
            result[accountId] = createSingleAccountTestData(accountId, assetsPerAccount)
        }
        return result
    }

    /**
     * Get a valid JWT token for non-admin user testing
     * TODO: Replace with actual JWT generation once auth system is set up
     */
    fun getValidToken(): String {
        return "test-token-user"
    }

    /**
     * Get a valid JWT token for admin user testing
     * TODO: Replace with actual JWT generation once auth system is set up
     */
    fun getAdminToken(): String {
        return "test-token-admin"
    }

    /**
     * Expected AWS account IDs for test scenarios
     */
    object TestAccountIds {
        const val PRIMARY = "123456789012"
        const val SECONDARY = "987654321098"
        const val TERTIARY = "555555555555"
    }

    /**
     * Expected user emails for test scenarios
     */
    object TestEmails {
        const val REGULAR_USER = "test@example.com"
        const val ADMIN_USER = "admin@example.com"
        const val NO_MAPPING_USER = "nomapping@example.com"
    }
}
