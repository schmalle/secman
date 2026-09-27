package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "owner_mail_notification")
@Serdeable
data class OwnerMailNotification(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true, length = 64)
    var eventKey: String = "",
    @Column(length = 12) var awsAccountId: String? = null,
    @Column(length = 255) var ownerEmail: String? = null,
    @Column(nullable = false, length = 16) var status: String = "PENDING",
    @Column(length = 64) var errorCode: String? = null,
    @Column(length = 255) var providerMessageId: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    var claimedAt: LocalDateTime? = null
)
