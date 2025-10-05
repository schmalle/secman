package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "identity_providers")
@Serdeable
data class IdentityProvider(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: ProviderType = ProviderType.OIDC,

    @Column(name = "client_id", nullable = false)
    var clientId: String = "",

    @Column(name = "client_secret")
    var clientSecret: String? = null,

    @Column(name = "tenant_id")
    var tenantId: String? = null,

    @Column(name = "discovery_url")
    var discoveryUrl: String? = null,

    @Column(name = "authorization_url")
    var authorizationUrl: String? = null,

    @Column(name = "token_url")
    var tokenUrl: String? = null,

    @Column(name = "user_info_url")
    var userInfoUrl: String? = null,

    var issuer: String? = null,

    @Column(name = "jwks_uri")
    var jwksUri: String? = null,

    var scopes: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = false,

    @Column(name = "auto_provision", nullable = false)
    var autoProvision: Boolean = false,

    @Column(name = "button_text", nullable = false)
    var buttonText: String = "",

    @Column(name = "button_color", nullable = false)
    var buttonColor: String = "#007bff",

    @Column(name = "role_mapping", columnDefinition = "TEXT")
    var roleMapping: String? = null, // JSON string for role mappings

    @Column(name = "claim_mappings", columnDefinition = "TEXT")
    var claimMappings: String? = null, // JSON string for claim mappings

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
        updatedAt = LocalDateTime.now()
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    enum class ProviderType {
        OIDC, SAML
    }
}