package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Tracks compliance status changes per asset over time.
 * Only status transitions are stored (not every import) to minimize DB storage.
 *
 * Status: COMPLIANT = no vulnerabilities older than threshold (default 30 days)
 *         NON_COMPLIANT = has vulnerabilities older than threshold
 *
 * Feature: ec2-vulnerability-tracking
 */
@Entity
@Table(
    name = "asset_compliance_history",
    indexes = [
        Index(name = "idx_ach_asset_id", columnList = "asset_id"),
        Index(name = "idx_ach_changed_at", columnList = "changed_at"),
        Index(name = "idx_ach_status", columnList = "status"),
        Index(name = "idx_ach_asset_changed", columnList = "asset_id, changed_at DESC")
    ]
)
@Serdeable
data class AssetComplianceHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "asset_id", nullable = false)
    var assetId: Long,

    @Column(nullable = false, length = 15)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    var status: ComplianceStatus,

    @Column(name = "changed_at", nullable = false)
    var changedAt: LocalDateTime,

    @Column(name = "overdue_count", nullable = false)
    var overdueCount: Int = 0,

    @Column(name = "oldest_vuln_days")
    var oldestVulnDays: Int? = null,

    @Column(nullable = false, length = 30)
    var source: String
)

@Serdeable
enum class ComplianceStatus {
    COMPLIANT,
    NON_COMPLIANT
}
