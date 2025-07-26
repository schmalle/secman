package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "oauth_states")
data class OAuthState(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "state_value", nullable = false, unique = true)
    var stateValue: String = "",

    @Column(name = "provider_id", nullable = false)
    var providerId: Long = 0,

    @Column(name = "redirect_uri")
    var redirectUri: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "expires_at", nullable = false)
    var expiresAt: LocalDateTime = LocalDateTime.now().plusMinutes(10) // 10 minute expiry
) {
    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
        if (expiresAt.isEqual(LocalDateTime.now()) || expiresAt.isBefore(LocalDateTime.now())) {
            expiresAt = LocalDateTime.now().plusMinutes(10)
        }
    }
}