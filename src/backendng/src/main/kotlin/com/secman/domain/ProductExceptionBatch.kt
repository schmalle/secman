package com.secman.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "product_exception_batch", uniqueConstraints = [UniqueConstraint(columnNames = ["owner_id", "request_key"])])
class ProductExceptionBatch(
    @Id @Column(length = 36) val id: String = UUID.randomUUID().toString(),
    @Column(name = "owner_id", nullable = false) val ownerId: Long,
    @Column(nullable = false, columnDefinition = "LONGTEXT") val manifest: String,
    @ElementCollection
    @CollectionTable(name = "product_exception_batch_account", joinColumns = [JoinColumn(name = "batch_id")])
    @Column(name = "account_id", length = 12) val accounts: MutableSet<String> = mutableSetOf(),
    @Column(nullable = false) val created: Instant = Instant.now(),
    @Column(name = "auto_approve", nullable = false) val autoApprove: Boolean,
    @Column(nullable = false) var status: String = "PREVIEW",
    @Column(name = "request_key", length = 36) var requestKey: String? = null,
    @Column(name = "decided_by") var decidedBy: String? = null,
    @ElementCollection
    @CollectionTable(name = "product_exception_batch_decision", joinColumns = [JoinColumn(name = "batch_id")])
    @OrderColumn(name = "sequence_no") val history: MutableList<BatchDecision> = mutableListOf(),
    @Column(name = "decided_at") var decidedAt: Instant? = null
)

@Embeddable
@io.micronaut.serde.annotation.Serdeable
data class BatchDecision(val actor: String, val outcome: String,
    @Column(name = "occurred_at") val occurredAt: Instant, @Column(length = 2048) val note: String? = null)
