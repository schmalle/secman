package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * A GitHub Dependabot alert ingested into secman.
 *
 * Alerts are pushed in by the CLI (`query dependabot-alerts`, which calls the
 * GitHub REST API) via `POST /api/dependabot-alerts/import`, and surfaced
 * read-only in the Vulnerability Management UI.
 *
 * The natural key is `(repository, alertNumber)` — GitHub numbers alerts
 * per-repository — so re-imports upsert in place rather than duplicating.
 * Hibernate `hbm2ddl.auto=update` creates the `dependabot_alert` table; no
 * Flyway migration is required.
 */
@Entity
@Table(
    name = "dependabot_alert",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_dependabot_repo_number", columnNames = ["repository", "alert_number"])
    ],
    indexes = [
        Index(name = "idx_dependabot_repository", columnList = "repository"),
        Index(name = "idx_dependabot_state", columnList = "state"),
        Index(name = "idx_dependabot_severity", columnList = "severity")
    ]
)
@Serdeable
data class DependabotAlert(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** GitHub repository in `owner/name` form. */
    @Column(name = "repository", nullable = false, length = 255)
    var repository: String = "",

    /** Per-repository alert number assigned by GitHub. */
    @Column(name = "alert_number", nullable = false)
    var alertNumber: Int = 0,

    /** open | fixed | dismissed | auto_dismissed */
    @Column(name = "state", nullable = false, length = 20)
    var state: String = "open",

    /** Affected package name, e.g. `lodash`. */
    @Column(name = "package_name", nullable = false, length = 255)
    var packageName: String = "",

    /** Package ecosystem, e.g. `npm`, `pip`, `maven`. */
    @Column(name = "ecosystem", nullable = false, length = 50)
    var ecosystem: String = "",

    /** Manifest file path that declares the dependency. */
    @Column(name = "manifest_path", length = 1024)
    var manifestPath: String? = null,

    /** low | medium | high | critical */
    @Column(name = "severity", nullable = false, length = 20)
    var severity: String = "medium",

    @Column(name = "ghsa_id", length = 64)
    var ghsaId: String? = null,

    @Column(name = "cve_id", length = 32)
    var cveId: String? = null,

    @Column(name = "summary", length = 1024)
    var summary: String? = null,

    @Column(name = "vulnerable_version_range", length = 255)
    var vulnerableVersionRange: String? = null,

    @Column(name = "first_patched_version", length = 255)
    var firstPatchedVersion: String? = null,

    @Column(name = "html_url", length = 1024)
    var htmlUrl: String? = null,

    /** GitHub alert creation time. */
    @Column(name = "alert_created_at")
    var alertCreatedAt: Instant? = null,

    /** GitHub alert last-update time. */
    @Column(name = "alert_updated_at")
    var alertUpdatedAt: Instant? = null,

    @Column(name = "dismissed_at")
    var dismissedAt: Instant? = null,

    @Column(name = "fixed_at")
    var fixedAt: Instant? = null,

    /** When secman last ingested/refreshed this alert. */
    @Column(name = "imported_at", nullable = false)
    var importedAt: Instant = Instant.now()
)
