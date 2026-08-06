package com.secman.domain

import com.secman.util.EncryptedStringConverter
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "falcon_configs")
@Serdeable
data class FalconConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "client_id", nullable = false, columnDefinition = "TEXT")
    @NotBlank
    @Convert(converter = EncryptedStringConverter::class)
    val clientId: String,

    @Column(name = "client_secret", nullable = false, columnDefinition = "TEXT")
    @NotBlank
    @Convert(converter = EncryptedStringConverter::class)
    val clientSecret: String,

    @Column(name = "cloud_region", nullable = false, length = 50)
    @NotBlank
    val cloudRegion: String = "us-1",

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime? = null
) {

    companion object {
        const val CLIENT_ID_MASK = "***HIDDEN***"
        const val CLIENT_SECRET_MASK = "***HIDDEN***"

        // Valid CrowdStrike Falcon cloud regions
        val VALID_REGIONS = setOf(
            "us-1", "us-2", "eu-1", "us-gov-1", "us-gov-2"
        )

        /**
         * Create new Falcon configuration with encrypted credentials
         */
        fun create(
            clientId: String,
            clientSecret: String,
            cloudRegion: String
        ): FalconConfig {
            require(VALID_REGIONS.contains(cloudRegion)) {
                "Invalid cloud region. Must be one of: ${VALID_REGIONS.joinToString(", ")}"
            }

            return FalconConfig(
                clientId = clientId,
                clientSecret = clientSecret,
                cloudRegion = cloudRegion,
                isActive = true
            )
        }
    }

    /**
     * Create a copy with sensitive data masked for API responses
     */
    fun toSafeResponse(): FalconConfig {
        return copy(
            clientId = if (clientId.isNotBlank()) CLIENT_ID_MASK else "",
            clientSecret = if (clientSecret.isNotBlank()) CLIENT_SECRET_MASK else ""
        )
    }

    /**
     * Check if credential update is needed (not the masked values)
     */
    fun shouldUpdateCredentials(newClientId: String?, newClientSecret: String?): Boolean {
        val shouldUpdateClientId = newClientId != null && newClientId != CLIENT_ID_MASK
        val shouldUpdateSecret = newClientSecret != null && newClientSecret != CLIENT_SECRET_MASK
        return shouldUpdateClientId || shouldUpdateSecret
    }

    /**
     * Update with new credentials
     */
    fun withUpdatedCredentials(newClientId: String?, newClientSecret: String?): FalconConfig {
        return copy(
            clientId = if (newClientId != null && newClientId != CLIENT_ID_MASK) newClientId else clientId,
            clientSecret = if (newClientSecret != null && newClientSecret != CLIENT_SECRET_MASK) newClientSecret else clientSecret
        )
    }

    /**
     * Activate this configuration
     */
    fun activate(): FalconConfig {
        return copy(isActive = true)
    }

    /**
     * Deactivate this configuration
     */
    fun deactivate(): FalconConfig {
        return copy(isActive = false)
    }

    /**
     * Validate configuration
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (clientId.isBlank()) {
            errors.add("Client ID cannot be empty")
        }

        if (clientSecret.isBlank()) {
            errors.add("Client Secret cannot be empty")
        }

        if (!VALID_REGIONS.contains(cloudRegion)) {
            errors.add("Invalid cloud region. Must be one of: ${VALID_REGIONS.joinToString(", ")}")
        }

        return errors
    }

    override fun toString(): String {
        return "FalconConfig(id=$id, cloudRegion='$cloudRegion', isActive=$isActive)"
    }
}
