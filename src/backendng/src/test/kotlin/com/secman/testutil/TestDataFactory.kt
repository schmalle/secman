package com.secman.testutil

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.User.Role
import com.secman.domain.Vulnerability
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.LocalDateTime

/**
 * Factory for creating test entities with sensible defaults.
 * Feature: 056-test-suite
 *
 * All factory methods return unsaved entities - caller is responsible for persistence.
 */
object TestDataFactory {

    private val passwordEncoder = BCryptPasswordEncoder()

    // Default test password (plaintext) - all test users use this
    const val DEFAULT_PASSWORD = "testpass123"

    // Pre-encoded hash for DEFAULT_PASSWORD to avoid repeated encoding
    private val defaultPasswordHash: String by lazy {
        passwordEncoder.encode(DEFAULT_PASSWORD)!!
    }

    /**
     * Create an admin user with ADMIN and USER roles
     */
    fun createAdminUser(
        username: String = "testadmin",
        email: String = "testadmin@secman.test"
    ): User {
        return User(
            username = username,
            email = email,
            passwordHash = defaultPasswordHash,
            roles = mutableSetOf(Role.USER, Role.ADMIN)
        )
    }

    /**
     * Create a user with VULN role (can add vulnerabilities)
     */
    fun createVulnUser(
        username: String = "testvuln",
        email: String = "testvuln@secman.test"
    ): User {
        return User(
            username = username,
            email = email,
            passwordHash = defaultPasswordHash,
            roles = mutableSetOf(Role.USER, Role.VULN)
        )
    }

    /**
     * Create a regular user with only USER role
     */
    fun createRegularUser(
        username: String = "testuser",
        email: String = "testuser@secman.test"
    ): User {
        return User(
            username = username,
            email = email,
            passwordHash = defaultPasswordHash,
            roles = mutableSetOf(Role.USER)
        )
    }

    /**
     * Create a user with custom roles
     */
    fun createUserWithRoles(
        username: String,
        email: String,
        vararg roles: Role
    ): User {
        return User(
            username = username,
            email = email,
            passwordHash = defaultPasswordHash,
            roles = roles.toMutableSet()
        )
    }

    /**
     * Create an asset with CLI-IMPORT defaults (as created by add-vulnerability command)
     */
    fun createAsset(
        name: String = "test-system",
        type: String = "SERVER",
        owner: String = "CLI-IMPORT",
        ip: String? = null
    ): Asset {
        return Asset(
            name = name,
            type = type,
            owner = owner,
            ip = ip,
            lastSeen = LocalDateTime.now()
        )
    }

    /**
     * Create a vulnerability for the specified asset
     */
    fun createVulnerability(
        asset: Asset,
        cve: String = "CVE-2024-TEST001",
        severity: String = "High",
        daysOpen: Int = 60
    ): Vulnerability {
        val scanTimestamp = LocalDateTime.now().minusDays(daysOpen.toLong())
        val daysOpenText = if (daysOpen == 1) "1 day" else "$daysOpen days"

        return Vulnerability(
            asset = asset,
            vulnerabilityId = cve,
            cvssSeverity = severity,
            daysOpen = daysOpenText,
            scanTimestamp = scanTimestamp,
            importTimestamp = LocalDateTime.now()
        )
    }

    /**
     * Create a vulnerability with custom scan timestamp
     */
    fun createVulnerabilityWithTimestamp(
        asset: Asset,
        cve: String,
        severity: String,
        scanTimestamp: LocalDateTime
    ): Vulnerability {
        return Vulnerability(
            asset = asset,
            vulnerabilityId = cve,
            cvssSeverity = severity,
            scanTimestamp = scanTimestamp,
            importTimestamp = LocalDateTime.now()
        )
    }

    /**
     * Map CLI criticality enum to display severity string
     * Matches VulnerabilityService.mapCriticalityToSeverity()
     */
    fun mapCriticalityToSeverity(criticality: String): String {
        return when (criticality.uppercase()) {
            "CRITICAL" -> "Critical"
            "HIGH" -> "High"
            "MEDIUM" -> "Medium"
            "LOW" -> "Low"
            else -> "Informational"
        }
    }
}
