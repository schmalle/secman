package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

@Entity
@Table(
    name = "asset",
    indexes = [
        Index(name = "idx_asset_ip_numeric", columnList = "ip_numeric")
    ]
)
@Serdeable
data class Asset(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(nullable = false)
    @NotBlank
    @Size(max = 255)
    var name: String,

    @Column(nullable = false)
    @NotBlank
    var type: String,

    @Column
    var ip: String? = null,

    /**
     * Numeric representation of IP address for efficient range queries
     * Feature: 020-i-want-to (IP Address Mapping)
     * Computed from ip field in @PrePersist and @PreUpdate
     * Example: "192.168.1.100" -> 3232235876
     */
    @Column(name = "ip_numeric", nullable = true)
    var ipNumeric: Long? = null,

    @Column(nullable = false)
    @NotBlank
    @Size(max = 255)
    var owner: String,

    @Column(length = 1024)
    var description: String? = null,

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null,

    /**
     * Timestamp when asset was last seen in a scan
     * Updated when new ScanResult is created for this asset
     * Related to: Feature 002-implement-a-parsing (Nmap Scan Import)
     */
    @Column(name = "last_seen")
    var lastSeen: LocalDateTime? = null,

    /**
     * Comma-separated group names this asset belongs to
     * Example: "SVR-MS-DMZ,Production"
     * Related to: Feature 003-i-want-to (Vulnerability Management System)
     */
    @Column(name = "groups", length = 512)
    var groups: String? = null,

    /**
     * Cloud service account ID
     * Related to: Feature 003-i-want-to (Vulnerability Management System)
     */
    @Column(name = "cloud_account_id", length = 255)
    var cloudAccountId: String? = null,

    /**
     * Cloud service instance ID
     * Related to: Feature 003-i-want-to (Vulnerability Management System)
     */
    @Column(name = "cloud_instance_id", length = 255)
    var cloudInstanceId: String? = null,

    /**
     * Active Directory domain this asset belongs to
     * Example: "MS.HOME"
     * Related to: Feature 003-i-want-to (Vulnerability Management System)
     */
    @Column(name = "ad_domain", length = 255)
    var adDomain: String? = null,

    /**
     * Operating system version
     * Example: "Windows Server 2030", "Ubuntu 22.04"
     * Related to: Feature 003-i-want-to (Vulnerability Management System)
     */
    @Column(name = "os_version", length = 255)
    var osVersion: String? = null,

    /**
     * Explicit criticality override for this asset
     * Feature 039: Asset and Workgroup Criticality Classification
     * - nullable: null means inherit from workgroups
     * - non-null: explicit override takes precedence over workgroup criticality
     * - See effectiveCriticality for computed value
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "criticality", nullable = true, length = 20)
    var criticality: Criticality? = null,

    /**
     * Many-to-many relationship with Workgroup
     * Feature: 008-create-an-additional (Workgroup-Based Access Control)
     * Assets can belong to 0..n workgroups
     * EAGER fetch: workgroup membership checked for access control filtering
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "asset_workgroups",
        joinColumns = [JoinColumn(name = "asset_id")],
        inverseJoinColumns = [JoinColumn(name = "workgroup_id")]
    )
    var workgroups: MutableSet<Workgroup> = mutableSetOf(),

    /**
     * Dual ownership tracking - manual creator
     * Feature: 008-create-an-additional (Workgroup-Based Access Control)
     * User who manually created this asset via UI
     * Nullable: allows user deletion (FR-027)
     * LAZY fetch: not needed for list operations
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manual_creator_id", nullable = true)
    var manualCreator: User? = null,

    /**
     * Dual ownership tracking - scan uploader
     * Feature: 008-create-an-additional (Workgroup-Based Access Control)
     * User who uploaded scan that discovered this asset
     * Nullable: allows user deletion (FR-027)
     * LAZY fetch: not needed for list operations
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_uploader_id", nullable = true)
    var scanUploader: User? = null,

    /**
     * Bidirectional relationship to ScanResult
     * One asset can have multiple scan results over time (scan history)
     * Foreign key is in scan_result table (asset_id)
     * Related to: Feature 002-implement-a-parsing, Decision 4 (point-in-time snapshots)
     *
     * Note: @JsonIgnore prevents lazy loading errors during JSON serialization.
     * Scan results should be loaded explicitly via port history endpoint.
     */
    @JsonIgnore
    @OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var scanResults: MutableList<ScanResult> = mutableListOf(),

    /**
     * Bidirectional relationship to Vulnerability
     * One asset can have multiple vulnerabilities discovered across different scans
     * Foreign key is in vulnerability table (asset_id)
     * Related to: Feature 003-i-want-to (Vulnerability Management System)
     *
     * Note: @JsonIgnore prevents lazy loading errors during JSON serialization.
     * Vulnerabilities should be loaded explicitly via asset vulnerabilities endpoint.
     */
    @JsonIgnore
    @OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var vulnerabilities: MutableList<Vulnerability> = mutableListOf()
) {
    /**
     * Computed effective criticality for this asset
     * Feature 039: Asset and Workgroup Criticality Classification
     *
     * Calculation logic:
     * 1. If asset has explicit criticality override -> return it
     * 2. If asset belongs to 1+ workgroups -> return highest workgroup criticality (excluding N/A)
     * 3. If all workgroups are N/A or asset has no workgroups -> default to MEDIUM
     *
     * Not persisted to database, computed on-demand
     */
    fun getEffectiveCriticality(): Criticality {
        return criticality ?: workgroups
            .filter { it.criticality != Criticality.NA }  // Filter out N/A workgroups
            .maxByOrNull { it.criticality }?.criticality ?: Criticality.MEDIUM
    }

    @PrePersist
    fun onCreate() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
        computeIpNumeric()
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
        computeIpNumeric()
    }

    /**
     * Compute numeric representation of IP address
     * Feature: 020-i-want-to (IP Address Mapping)
     * Converts IPv4 address string to Long for efficient range queries
     */
    private fun computeIpNumeric() {
        ipNumeric = if (ip != null) {
            try {
                ipToNumeric(ip!!)
            } catch (e: Exception) {
                null // Invalid IP format, skip numeric conversion
            }
        } else {
            null
        }
    }

    /**
     * Convert IPv4 address string to numeric representation
     * Example: "192.168.1.100" -> 3232235876
     */
    private fun ipToNumeric(ipAddress: String): Long {
        val parts = ipAddress.split('.')
        if (parts.size != 4) return 0L

        var result = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return 0L
            if (octet < 0 || octet > 255) return 0L
            result = (result shl 8) or octet.toLong()
        }
        return result
    }

    /**
     * Add a scan result to this asset
     * Maintains bidirectional relationship and updates lastSeen
     */
    fun addScanResult(result: ScanResult) {
        scanResults.add(result)
        result.asset = this
        lastSeen = result.discoveredAt
        updatedAt = LocalDateTime.now()
    }

    override fun toString(): String {
        return "Asset(id=$id, name='$name', type='$type', owner='$owner', ip='$ip')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Asset) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}