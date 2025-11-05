package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

/**
 * PasskeyCredential entity for WebAuthn/FIDO2 authentication
 * Feature: Passkey MFA Support
 *
 * Stores WebAuthn credentials for passwordless/MFA authentication
 */
@Entity
@Table(name = "passkey_credentials")
@Serdeable
data class PasskeyCredential(
    @Id
    @GeneratedValue
    var id: Long? = null,

    /**
     * User who owns this credential
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    @NotNull
    var user: User,

    /**
     * WebAuthn credential ID (base64url encoded)
     * Unique identifier for this credential
     */
    @Column(name = "credential_id", unique = true, nullable = false, length = 1024)
    @NotBlank
    var credentialId: String,

    /**
     * COSE-encoded public key
     * Used for signature verification
     */
    @Column(name = "public_key_cose", nullable = false, columnDefinition = "TEXT")
    @NotBlank
    var publicKeyCose: String,

    /**
     * Signature counter for replay attack prevention
     * Must increment with each authentication
     */
    @Column(name = "sign_count", nullable = false)
    var signCount: Long = 0,

    /**
     * Authenticator Attestation GUID (AAGUID)
     * Identifies the authenticator model
     */
    @Column(name = "aaguid", length = 36)
    var aaguid: String? = null,

    /**
     * User-friendly name for this credential
     * e.g., "iPhone", "YubiKey", "Windows Hello"
     */
    @Column(name = "credential_name", nullable = false, length = 255)
    @NotBlank
    var credentialName: String = "Passkey",

    /**
     * Transports supported by this authenticator
     * e.g., "usb,nfc,ble,internal"
     */
    @Column(name = "transports", length = 255)
    var transports: String? = null,

    /**
     * When this credential was created
     */
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    /**
     * When this credential was last used for authentication
     */
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null
) {
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        lastUsedAt = now
    }

    override fun toString(): String {
        return "PasskeyCredential(id=$id, userId=${user.id}, credentialName='$credentialName', createdAt=$createdAt)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PasskeyCredential) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
