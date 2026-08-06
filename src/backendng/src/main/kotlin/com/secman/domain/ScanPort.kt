package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.*

/**
 * ScanPort entity - Port-level data for a specific host in a scan
 *
 * Represents a single port discovered on a host during a network scan.
 * Contains port number, protocol, state, and optional service information.
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - Data Model: specs/002-implement-a-parsing/data-model.md
 * - FR-004: Persist open port information with scan timestamp
 * - FR-011: Display port history with states and services
 */
@Entity
@Table(
    name = "scan_port",
    indexes = [
        Index(name = "idx_scan_port_result", columnList = "scan_result_id"),
        Index(name = "idx_scan_port_unique", columnList = "scan_result_id,port_number,protocol"),
        Index(name = "idx_scan_port_service", columnList = "service")
    ]
)
@Serdeable
data class ScanPort(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /**
     * Many-to-one relationship to ScanResult
     * Foreign key with cascade delete from parent
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_result_id", nullable = false)
    var scanResult: ScanResult,

    /**
     * Port number (1-65535)
     * From nmap: <port protocol="tcp" portid="80">
     */
    @Column(nullable = false, name = "port_number")
    @field:Min(value = 1, message = "portNumber must be at least 1")
    @field:Max(value = 65535, message = "portNumber must not exceed 65535")
    var portNumber: Int,

    /**
     * Protocol type: "tcp" or "udp"
     * From nmap: <port protocol="tcp">
     */
    @Column(nullable = false, length = 10)
    @field:NotBlank(message = "protocol is required")
    @field:Pattern(regexp = "^(tcp|udp)$", message = "protocol must be 'tcp' or 'udp'")
    var protocol: String,

    /**
     * Port state: "open", "filtered", or "closed"
     * From nmap: <state state="open"/>
     * Edge case: All states stored, not just "open"
     */
    @Column(nullable = false, length = 20)
    @field:NotBlank(message = "state is required")
    @field:Pattern(regexp = "^(open|filtered|closed)$", message = "state must be 'open', 'filtered', or 'closed'")
    var state: String,

    /**
     * Service name if detected (nullable)
     * From nmap: <service name="http"/>
     * Examples: "http", "ssh", "mysql"
     */
    @Column(length = 100)
    var service: String? = null,

    /**
     * Service version if detected (nullable)
     * From nmap: <service name="http" product="nginx" version="1.18.0"/>
     * Examples: "nginx 1.18.0", "OpenSSH 8.0"
     */
    @Column(length = 255)
    var version: String? = null
) {
    /**
     * Get port display string
     * Example: "80/tcp (open) - http"
     */
    fun toDisplayString(): String {
        val serviceInfo = service?.let { " - $it" } ?: ""
        return "$portNumber/$protocol ($state)$serviceInfo"
    }

    /**
     * Check if port is open
     * Convenience method for filtering
     */
    fun isOpen(): Boolean = state == "open"

    override fun toString(): String {
        return "ScanPort(portNumber=$portNumber, protocol='$protocol', state='$state', service='$service')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanPort) return false
        if (id != null && other.id != null) {
            return id == other.id
        }
        return false
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
