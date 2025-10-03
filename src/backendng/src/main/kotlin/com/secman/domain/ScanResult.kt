package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.*
import java.time.LocalDateTime

/**
 * ScanResult entity - Host-level data discovered in a scan
 *
 * Represents a single host discovered during a network scan.
 * Links a Scan to an Asset and contains host-specific metadata.
 * Multiple ScanResults can exist for the same Asset (scan history over time).
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - Data Model: specs/002-implement-a-parsing/data-model.md
 * - Decision 1: hostname can be null, use IP as fallback for asset name
 * - Decision 2: Skip duplicate IPs within same scan
 * - Decision 4: Point-in-time snapshots via separate ScanResult records
 */
@Entity
@Table(
    name = "scan_result",
    indexes = [
        Index(name = "idx_scan_result_asset_date", columnList = "asset_id,discovered_at"),
        Index(name = "idx_scan_result_scan", columnList = "scan_id")
    ]
)
@Serdeable
data class ScanResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /**
     * Many-to-one relationship to Scan
     * Foreign key with cascade delete from parent
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    var scan: Scan,

    /**
     * Many-to-one relationship to Asset
     * Foreign key with cascade delete from parent
     * Same asset can have multiple scan results over time
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    var asset: Asset,

    /**
     * IP address discovered
     * NOT NULL - required for asset lookup/creation
     * Max 45 chars to support IPv6
     */
    @Column(nullable = false, length = 45, name = "ip_address")
    @field:NotBlank(message = "ipAddress is required")
    var ipAddress: String,

    /**
     * DNS hostname if available (nullable)
     * From nmap PTR lookup: <hostname name="..." type="PTR"/>
     * Can be null when hostname not resolvable (per Decision 1)
     */
    @Column(length = 255)
    var hostname: String? = null,

    /**
     * Timestamp of this host's discovery
     * Typically matches parent Scan.scanDate
     */
    @Column(nullable = false, name = "discovered_at")
    var discoveredAt: LocalDateTime,

    /**
     * One-to-many relationship to ScanPort
     * Cascade ALL: Deleting result removes all ports
     * Orphan removal: Removing port from list deletes it
     */
    @OneToMany(mappedBy = "scanResult", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var ports: List<ScanPort> = emptyList()
) {
    /**
     * Add ScanPort to this result
     * Maintains bidirectional relationship
     */
    fun addPort(port: ScanPort) {
        ports = ports + port
        port.scanResult = this
    }

    /**
     * Get effective hostname or IP
     * Implements Decision 1: IP as fallback when hostname missing
     */
    fun getEffectiveName(): String {
        return hostname ?: ipAddress
    }

    override fun toString(): String {
        return "ScanResult(id=$id, ipAddress='$ipAddress', hostname='$hostname', ports=${ports.size})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanResult) return false
        if (id != null && other.id != null) {
            return id == other.id
        }
        return false
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
