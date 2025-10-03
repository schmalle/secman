package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

@Entity
@Table(name = "asset")
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
    @PrePersist
    fun onCreate() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
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