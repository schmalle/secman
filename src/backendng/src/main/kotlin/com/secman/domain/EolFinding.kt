package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * One component (OS, installed product, or repository dependency) whose release
 * cycle is end-of-life or reaches end-of-life inside the configured horizon.
 *
 * Written by [com.secman.service.EolScanService] with the same delete-then-insert
 * per scan run that the CrowdStrike vulnerability import uses: a component that
 * has been upgraded simply stops being reproduced by the next scan. Rows are
 * never cascaded from `Asset` — the scan owns them explicitly (see CLAUDE.md
 * §Transactional replace).
 *
 * Only EOL / approaching-EOL components are stored; see [EolStatus].
 *
 * `assetId` / `githubRepositoryId` are plain columns rather than associations on
 * purpose: this table is scanned in bulk and joined by id, and a `@ManyToOne`
 * here would drag `Asset` graphs into every report query.
 */
@Entity
@Table(
    name = "eol_finding",
    indexes = [
        Index(name = "idx_eol_finding_asset", columnList = "asset_id"),
        Index(name = "idx_eol_finding_repo", columnList = "github_repository_id"),
        Index(name = "idx_eol_finding_status", columnList = "status"),
        Index(name = "idx_eol_finding_eol_date", columnList = "eol_date"),
        Index(name = "idx_eol_finding_run", columnList = "scan_run_id"),
        Index(name = "idx_eol_finding_subject", columnList = "subject_type")
    ]
)
@Serdeable
data class EolFinding(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 32)
    var subjectType: EolSubjectType = EolSubjectType.ASSET_PRODUCT,

    /** Set for [EolSubjectType.ASSET_OS] and [EolSubjectType.ASSET_PRODUCT]. */
    @Column(name = "asset_id")
    var assetId: Long? = null,

    /** Denormalized for report display; the access decision never reads this. */
    @Column(name = "asset_name", length = 512)
    var assetName: String? = null,

    @Column(name = "cloud_account_id", length = 64)
    var cloudAccountId: String? = null,

    @Column(name = "ad_domain", length = 255)
    var adDomain: String? = null,

    /** Owner recorded on the asset at scan time; the notification recipient. */
    @Column(name = "asset_owner", length = 255)
    var assetOwner: String? = null,

    /** Set for [EolSubjectType.ASSET_PRODUCT]. */
    @Column(name = "installed_product_id")
    var installedProductId: Long? = null,

    /** Set for [EolSubjectType.REPOSITORY_COMPONENT]. */
    @Column(name = "github_repository_id")
    var githubRepositoryId: Long? = null,

    @Column(name = "repository_full_name", length = 512)
    var repositoryFullName: String? = null,

    /** Component name as observed (product name, OS string, or package name). */
    @Column(name = "component_name", nullable = false, length = 512)
    var componentName: String = "",

    @Column(name = "component_vendor", length = 255)
    var componentVendor: String? = null,

    @Column(name = "component_version", length = 255)
    var componentVersion: String? = null,

    /**
     * Whether the subject of this finding is deployed software or an installer payload.
     * Denormalized at scan time from the source `InstalledProduct` for ASSET_PRODUCT findings,
     * matching how assetName / cloudAccountId / assetOwner are already carried here.
     * ASSET_OS and REPOSITORY_COMPONENT findings are always INSTALLED — an operating system is
     * not a cached payload and a repository dependency is not a file on disk.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_class", nullable = false, length = 20)
    var productClass: ProductClass = ProductClass.UNKNOWN,

    /** Package ecosystem for repository components, e.g. `npm`, `maven`. */
    @Column(name = "ecosystem", length = 64)
    var ecosystem: String? = null,

    @Column(name = "eol_product_id", nullable = false)
    var eolProductId: Long = 0,

    @Column(name = "eol_product_key", nullable = false, length = 190)
    var eolProductKey: String = "",

    @Column(name = "eol_release_id", nullable = false)
    var eolReleaseId: Long = 0,

    @Column(name = "eol_cycle", nullable = false, length = 100)
    var eolCycle: String = "",

    /** Null when upstream flagged the cycle EOL without publishing a date. */
    @Column(name = "eol_date")
    var eolDate: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: EolStatus = EolStatus.EOL,

    /** Negative once the date is in the past. Null when [eolDate] is null. */
    @Column(name = "days_until_eol")
    var daysUntilEol: Long? = null,

    /** Groups every row written by one scan; drives the stale-row cleanup. */
    @Column(name = "scan_run_id", nullable = false, length = 64)
    var scanRunId: String = "",

    @Column(name = "detected_at", nullable = false)
    var detectedAt: Instant? = null
) {
    @PrePersist
    fun onCreate() {
        detectedAt = detectedAt ?: Instant.now()
    }
}
