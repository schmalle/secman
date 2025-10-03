package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.*
import java.time.LocalDateTime

/**
 * Scan entity - Metadata about a network scan import event
 *
 * Represents a single scan file upload (nmap, masscan) with summary metadata.
 * Each scan can have multiple ScanResult records (one per discovered host).
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - Data Model: specs/002-implement-a-parsing/data-model.md
 * - Decision 5: scanType discriminator for future masscan support
 * - Decision 6: 60s timeout enforced at controller level
 */
@Entity
@Table(
    name = "scan",
    indexes = [
        Index(name = "idx_scan_uploaded_by", columnList = "uploaded_by"),
        Index(name = "idx_scan_date", columnList = "scan_date")
    ]
)
@Serdeable
data class Scan(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /**
     * Scan tool type: "nmap" or "masscan"
     * Enables future support for additional scanners
     */
    @Column(nullable = false, length = 20)
    @field:NotBlank(message = "scanType is required")
    @field:Pattern(regexp = "^(nmap|masscan)$", message = "scanType must be 'nmap' or 'masscan'")
    var scanType: String,

    /**
     * Original uploaded file name
     * Example: "network-scan-2025-10-03.xml"
     */
    @Column(nullable = false, length = 255)
    @field:NotBlank(message = "filename is required")
    @field:Size(max = 255, message = "filename cannot exceed 255 characters")
    var filename: String,

    /**
     * Timestamp when the scan was performed
     * Extracted from nmap XML <nmaprun start="...">
     */
    @Column(nullable = false)
    var scanDate: LocalDateTime,

    /**
     * Username who uploaded the scan
     * From JWT/authentication context
     */
    @Column(nullable = false, length = 255)
    @field:NotBlank(message = "uploadedBy is required")
    var uploadedBy: String,

    /**
     * Number of hosts discovered in this scan
     * Must be non-negative (0 for empty scans)
     */
    @Column(nullable = false)
    @field:Min(value = 0, message = "hostCount must be non-negative")
    var hostCount: Int,

    /**
     * Scan duration in seconds (optional)
     * Nmap provides this in XML, masscan may not
     */
    @Column
    var duration: Int? = null,

    /**
     * Upload timestamp (auto-populated on creation)
     */
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    /**
     * One-to-many relationship to ScanResult
     * Cascade ALL: Deleting scan removes all results and ports
     * Orphan removal: Removing result from list deletes it
     */
    @OneToMany(mappedBy = "scan", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var results: List<ScanResult> = emptyList()
) {
    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
    }

    /**
     * Add ScanResult to this scan
     * Maintains bidirectional relationship
     */
    fun addResult(result: ScanResult) {
        results = results + result
        result.scan = this
    }

    override fun toString(): String {
        return "Scan(id=$id, scanType='$scanType', filename='$filename', hostCount=$hostCount, scanDate=$scanDate)"
    }
}
