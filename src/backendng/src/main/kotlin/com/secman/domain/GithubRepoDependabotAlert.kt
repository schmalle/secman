package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * A currently-open GitHub Dependabot alert for an imported repository.
 *
 * Written by [com.secman.service.GithubRepoImportService.persistRepo]: on
 * each import, a repository's rows are deleted and reinserted from the
 * freshly fetched `state=open` alert list (current-state only, no history —
 * mirrors the CrowdStrike vulnerability import's delete-by-asset +
 * reinsert pattern). A patched or dismissed alert simply disappears on the
 * next import.
 */
@Entity
@Table(
    name = "github_repo_dependabot_alert",
    uniqueConstraints = [
        // Backstop against concurrent-import interleavings duplicating alert rows (V243).
        UniqueConstraint(name = "uk_ghalert_repo_alert", columnNames = ["github_repository_id", "alert_number"])
    ],
    indexes = [
        Index(name = "idx_ghalert_repo", columnList = "github_repository_id"),
        Index(name = "idx_ghalert_severity", columnList = "severity")
    ]
)
@Serdeable
data class GithubRepoDependabotAlert(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "github_repository_id", nullable = false)
    var githubRepositoryId: Long = 0,

    /** Per-repository alert number assigned by GitHub. */
    @Column(name = "alert_number", nullable = false)
    var alertNumber: Int = 0,

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
    var alertUpdatedAt: Instant? = null
)
