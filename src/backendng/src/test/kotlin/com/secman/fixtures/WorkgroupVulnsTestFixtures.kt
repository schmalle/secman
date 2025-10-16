package com.secman.fixtures

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.Vulnerability
import com.secman.domain.Workgroup
import java.time.LocalDateTime

/**
 * Test fixtures for WG Vulns feature testing
 *
 * Feature: 022-wg-vulns-handling - Workgroup-Based Vulnerability View
 *
 * Provides factory methods to create test entities with realistic data
 * for contract, integration, and unit tests.
 */
object WorkgroupVulnsTestFixtures {

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
     * Create a test Workgroup
     */
    fun createWorkgroup(
        name: String = "Test Workgroup",
        description: String? = "Test workgroup for unit tests"
    ): Workgroup {
        return Workgroup(
            id = null,
            name = name,
            description = description,
            users = mutableSetOf(),
            assets = mutableSetOf()
        )
    }

    /**
     * Create a test Asset
     */
    fun createAsset(
        name: String = "test-asset",
        type: String = "SERVER",
        ip: String? = "10.0.0.1",
        owner: String = "test-owner"
    ): Asset {
        return Asset(
            id = null,
            name = name,
            type = type,
            ip = ip,
            owner = owner,
            cloudAccountId = null,
            description = null,
            lastSeen = LocalDateTime.now(),
            workgroups = mutableSetOf()
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
     * Create multiple vulnerabilities with mixed severities for testing severity breakdown.
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
     * Create a vulnerability with NULL severity for testing UNKNOWN severity handling.
     */
    fun createVulnerabilityWithNullSeverity(asset: Asset): Vulnerability {
        val vuln = createVulnerability(asset, "CVE-2024-9999", "placeholder", "50")
        vuln.cvssSeverity = null // Set to null after creation
        return vuln
    }

    /**
     * Create a workgroup with users and assets
     *
     * @param workgroupName Name of the workgroup
     * @param users List of users to add to workgroup
     * @param assetCount Number of assets to create for this workgroup
     * @return Workgroup with users and assets
     */
    fun createWorkgroupWithAssetsAndUsers(
        workgroupName: String,
        users: List<User>,
        assetCount: Int = 3
    ): Workgroup {
        val workgroup = createWorkgroup(name = workgroupName)
        
        // Add users to workgroup
        users.forEach { user ->
            workgroup.users.add(user)
        }
        
        // Create assets for workgroup
        for (i in 1..assetCount) {
            val asset = createAsset(
                name = "$workgroupName-asset-$i",
                type = "SERVER",
                ip = "10.0.0.$i"
            )
            asset.workgroups.add(workgroup)
            workgroup.assets.add(asset)
        }
        
        return workgroup
    }

    /**
     * Create multiple workgroups for a single user
     *
     * @param user User to be member of all workgroups
     * @param workgroupCount Number of workgroups to create
     * @param assetsPerWorkgroup Number of assets per workgroup
     * @return List of workgroups
     */
    fun createMultipleWorkgroupsForUser(
        user: User,
        workgroupCount: Int = 3,
        assetsPerWorkgroup: Int = 2
    ): List<Workgroup> {
        val workgroups = mutableListOf<Workgroup>()
        for (i in 1..workgroupCount) {
            val workgroup = createWorkgroupWithAssetsAndUsers(
                workgroupName = "Workgroup-$i",
                users = listOf(user),
                assetCount = assetsPerWorkgroup
            )
            workgroups.add(workgroup)
        }
        return workgroups
    }

    /**
     * Create scenario where asset belongs to multiple workgroups
     *
     * @param user User who is member of all workgroups
     * @param workgroupNames Names of workgroups to create
     * @return Pair of (workgroups, shared asset)
     */
    fun createSharedAssetScenario(
        user: User,
        workgroupNames: List<String> = listOf("WG-A", "WG-B")
    ): Pair<List<Workgroup>, Asset> {
        val workgroups = mutableListOf<Workgroup>()
        val sharedAsset = createAsset(name = "shared-asset", type = "SERVER")
        
        workgroupNames.forEach { name ->
            val workgroup = createWorkgroup(name = name)
            workgroup.users.add(user)
            workgroup.assets.add(sharedAsset)
            sharedAsset.workgroups.add(workgroup)
            workgroups.add(workgroup)
        }
        
        return Pair(workgroups, sharedAsset)
    }

    /**
     * Get a valid JWT token for non-admin user testing
     */
    fun getValidToken(): String {
        return "test-token-user"
    }

    /**
     * Get a valid JWT token for admin user testing
     */
    fun getAdminToken(): String {
        return "test-token-admin"
    }

    /**
     * Expected workgroup names for test scenarios
     */
    object TestWorkgroups {
        const val SECURITY_TEAM = "Security Team"
        const val INFRASTRUCTURE = "Infrastructure"
        const val DEVELOPMENT = "Development"
    }

    /**
     * Expected user emails for test scenarios
     */
    object TestEmails {
        const val REGULAR_USER = "test@example.com"
        const val ADMIN_USER = "admin@example.com"
        const val NO_WORKGROUP_USER = "noworkgroup@example.com"
        const val MULTI_WORKGROUP_USER = "multiworkgroup@example.com"
    }
}
