package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Repair evidence survives inventory deletion; findings are refetched, never reassigned by hostname. */
@Entity
@Table(name = "crowdstrike_identity_repair")
data class CrowdStrikeIdentityRepair(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "operation_id", nullable = false, length = 36) var operationId: String,
    @Column(name = "source_asset_id", nullable = false) var sourceAssetId: Long,
    @Column(name = "target_asset_id", nullable = false) var targetAssetId: Long,
    @Column(name = "crowdstrike_aid", nullable = false, length = 64) var crowdstrikeAid: String,
    @Column(name = "instance_id", nullable = false, length = 255) var instanceId: String,
    @Column(name = "account_id", nullable = false, length = 255) var accountId: String,
    @Column(name = "falcon_first_seen_at", nullable = false) var falconFirstSeenAt: LocalDateTime,
    @Column(name = "falcon_last_seen_at", nullable = false) var falconLastSeenAt: LocalDateTime,
    @Column(nullable = false, length = 255) var actor: String,
    @Column(nullable = false, length = 255) var reason: String,
    @Column(name = "committed_at", nullable = false) var committedAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
)
