package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Persistent record of every severity value ever queried by a CrowdStrike
 * import. Read by `CrowdStrikeVulnerabilityImportService.reconcileStaleCrowdStrikeImports`
 * to compute the sweep's severity union (current run + all historical runs),
 * which prevents stale rows when an operator's `--severity` flag changes
 * between runs.
 *
 * Severity is stored already-uppercased (e.g. `CRITICAL`, `HIGH`) and acts
 * as the natural primary key — the table is upsert-only, with at most one
 * row per distinct severity value.
 *
 * See migration V214.
 */
@Entity
@Table(name = "crowdstrike_severity_history")
@Serdeable
data class CrowdStrikeSeverityHistory(
    @Id
    @Column(name = "severity", length = 20, nullable = false)
    var severity: String = "",

    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: LocalDateTime = LocalDateTime.now()
)
