package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "web_exposure", uniqueConstraints = [
    UniqueConstraint(name = "uk_web_exposure_subject", columnNames = ["subject_id"])
], indexes = [Index(name = "idx_web_exposure_reachability", columnList = "reachability,observed_at")])
class WebExposure(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "subject_id", nullable = false) var subjectId: Long = 0,
    @Column(name = "last_run_id", nullable = false) var lastRunId: Long = 0,
    @Column(name = "configured_url", nullable = false, length = 2048) var configuredUrl: String = "",
    @Column(name = "effective_url", length = 2048) var effectiveUrl: String? = null,
    @Column(nullable = false, length = 16) var reachability: String = "UNKNOWN",
    @Column(name = "http_status") var httpStatus: Int? = null,
    @Column(name = "redirect_count", nullable = false) var redirectCount: Int = 0,
    @Column(name = "vantage_point", nullable = false, length = 100) var vantagePoint: String = "",
    @Column(name = "observed_at", nullable = false) var observedAt: Instant = Instant.EPOCH
)
