package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "integration_scanner")
class IntegrationScanner(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false, length = 200) var name: String = "",
    @Column(nullable = false, length = 20) var source: String = "",
    @Column(name = "service_user_id", nullable = false) var serviceUserId: Long = 0,
    @Column(name = "stale_after_hours", nullable = false) var staleAfterHours: Int = 24,
    @Column(nullable = false) var enabled: Boolean = true,
    @Column(name = "last_stale_notified_at") var lastStaleNotifiedAt: Instant? = null
)
