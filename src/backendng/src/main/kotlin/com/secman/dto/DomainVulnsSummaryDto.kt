package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Domain Vulnerabilities Summary DTO
 *
 * Feature: 042-domain-vulnerabilities-view
 *
 * Aggregates vulnerabilities from CrowdStrike Falcon API grouped by Active Directory domains
 * for the authenticated user based on their domain mappings.
 *
 * Similar structure to AccountVulnsSummaryDto but organized by AD domains instead of AWS accounts.
 *
 * @property domainGroups List of domain groups with their devices and vulnerabilities
 * @property totalDevices Total number of devices across all domains
 * @property totalVulnerabilities Total number of vulnerabilities across all domains
 * @property globalCritical Total critical vulnerabilities across all domains
 * @property globalHigh Total high vulnerabilities across all domains
 * @property globalMedium Total medium vulnerabilities across all domains
 * @property globalLow Total low vulnerabilities across all domains
 * @property queriedAt Timestamp when the data was fetched from Falcon API
 * @property lastSyncedAt Timestamp of the most recent CrowdStrike import (null if never synced)
 */
@Serdeable
data class DomainVulnsSummaryDto(
    val domainGroups: List<DomainGroupDto>,
    val totalDevices: Int,
    val totalVulnerabilities: Int,
    val globalCritical: Int? = null,
    val globalHigh: Int? = null,
    val globalMedium: Int? = null,
    val globalLow: Int? = null,
    val queriedAt: String,
    val lastSyncedAt: String? = null
)

/**
 * Domain Group DTO
 *
 * Represents a single Active Directory domain with its devices and vulnerability counts
 *
 * @property domain AD domain name (e.g., "CONTOSO", "EXAMPLE")
 * @property devices List of devices in this domain with their vulnerability counts
 * @property totalDevices Total number of devices in this domain
 * @property totalVulnerabilities Total number of vulnerabilities in this domain
 * @property totalCritical Total critical vulnerabilities in this domain
 * @property totalHigh Total high vulnerabilities in this domain
 * @property totalMedium Total medium vulnerabilities in this domain
 * @property totalLow Total low vulnerabilities in this domain
 */
@Serdeable
data class DomainGroupDto(
    val domain: String,
    val devices: List<DeviceVulnCountDto>,
    val totalDevices: Int,
    val totalVulnerabilities: Int,
    val totalCritical: Int? = null,
    val totalHigh: Int? = null,
    val totalMedium: Int? = null,
    val totalLow: Int? = null
)

/**
 * Device Vulnerability Count DTO
 *
 * Represents a single device from Falcon API with vulnerability counts
 *
 * @property hostname Device hostname
 * @property ip Device IP address
 * @property vulnerabilityCount Total number of vulnerabilities
 * @property criticalCount Number of critical vulnerabilities
 * @property highCount Number of high vulnerabilities
 * @property mediumCount Number of medium vulnerabilities
 * @property lowCount Number of low vulnerabilities
 */
@Serdeable
data class DeviceVulnCountDto(
    val hostname: String,
    val ip: String?,
    val vulnerabilityCount: Int,
    val criticalCount: Int? = null,
    val highCount: Int? = null,
    val mediumCount: Int? = null,
    val lowCount: Int? = null
)

/**
 * Domain Sync Result DTO
 *
 * Feature: Domain Vulnerability Sync
 *
 * Result of syncing a domain's vulnerabilities from CrowdStrike Falcon API
 * to the secman database.
 *
 * @property domain The AD domain that was synced
 * @property syncedAt Timestamp when the sync completed
 * @property devicesProcessed Number of devices processed from CrowdStrike
 * @property devicesCreated Number of new devices created in the database
 * @property devicesUpdated Number of existing devices updated
 * @property vulnerabilitiesImported Number of vulnerabilities imported
 */
@Serdeable
data class DomainSyncResultDto(
    val domain: String,
    val syncedAt: String,
    val devicesProcessed: Int,
    val devicesCreated: Int,
    val devicesUpdated: Int,
    val vulnerabilitiesImported: Int
)
