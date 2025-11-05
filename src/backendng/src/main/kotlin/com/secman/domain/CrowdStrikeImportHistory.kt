package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Persistence entity capturing statistics for each CrowdStrike import execution.
 *
 * Feature reference: 032-servers-query-import (operational telemetry)
 * Allows UI to surface data freshness by exposing the last successful import.
 */
@Entity
@Table(name = "crowdstrike_import_history")
@Serdeable
data class CrowdStrikeImportHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "imported_at", nullable = false)
    var importedAt: LocalDateTime? = null,

    @Column(name = "imported_by", length = 255)
    var importedBy: String? = null,

    @Column(name = "servers_processed", nullable = false)
    var serversProcessed: Int = 0,

    @Column(name = "servers_created", nullable = false)
    var serversCreated: Int = 0,

    @Column(name = "servers_updated", nullable = false)
    var serversUpdated: Int = 0,

    @Column(name = "vulns_imported", nullable = false)
    var vulnerabilitiesImported: Int = 0,

    @Column(name = "vulns_skipped", nullable = false)
    var vulnerabilitiesSkipped: Int = 0,

    @Column(name = "vulns_with_patch_date", nullable = false)
    var vulnerabilitiesWithPatchDate: Int = 0,

    @Column(name = "error_count", nullable = false)
    var errorCount: Int = 0
) {
    @PrePersist
    fun onCreate() {
        if (importedAt == null) {
            importedAt = LocalDateTime.now()
        }
    }
}
