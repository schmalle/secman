package com.secman.domain

import com.secman.util.EncryptedStringConverter
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * GitHub App credentials used to import repositories and their open
 * Dependabot alert counts. Only the private key is a secret: it is encrypted
 * at rest and masked in every API response. App ID, installation ID and
 * organization are public identifiers.
 */
@Entity
@Table(name = "github_app_config")
@Serdeable
data class GithubAppConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "app_id", nullable = false, length = 64)
    @NotBlank
    val appId: String,

    @Column(name = "private_key_pem", nullable = false, columnDefinition = "TEXT")
    @NotBlank
    @Convert(converter = EncryptedStringConverter::class)
    val privateKeyPem: String,

    /** Installation ID; when null the installation is resolved via the organization. */
    @Column(name = "installation_id", length = 64)
    val installationId: String? = null,

    /** Organization login used to pick the installation when installationId is unset. */
    @Column(name = "organization", length = 255)
    val organization: String? = null,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null
) {
    companion object {
        const val PRIVATE_KEY_MASK = "***HIDDEN***"
    }

    /** Copy with the private key masked for API responses. */
    fun toSafeResponse(): GithubAppConfig {
        return copy(privateKeyPem = if (privateKeyPem.isNotBlank()) PRIVATE_KEY_MASK else "")
    }

    /** True when the caller sent a real new key (not the mask). */
    fun shouldUpdateCredentials(newPrivateKeyPem: String?): Boolean {
        return newPrivateKeyPem != null && newPrivateKeyPem != PRIVATE_KEY_MASK
    }

    fun withUpdatedCredentials(newPrivateKeyPem: String?): GithubAppConfig {
        return copy(
            privateKeyPem = if (shouldUpdateCredentials(newPrivateKeyPem)) newPrivateKeyPem!! else privateKeyPem
        )
    }

    fun activate(): GithubAppConfig = copy(isActive = true)

    fun deactivate(): GithubAppConfig = copy(isActive = false)

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (appId.isBlank()) {
            errors.add("App ID cannot be empty")
        } else if (!appId.all { it.isDigit() }) {
            errors.add("App ID must be numeric")
        }
        if (privateKeyPem.isBlank()) {
            errors.add("Private key cannot be empty")
        } else if (!privateKeyPem.contains("PRIVATE KEY")) {
            errors.add("Private key must be a PEM-encoded RSA key (BEGIN [RSA] PRIVATE KEY)")
        }
        val installationIdValue = installationId
        if (installationIdValue != null && installationIdValue.isNotBlank() && !installationIdValue.all { it.isDigit() }) {
            errors.add("Installation ID must be numeric")
        }
        return errors
    }

    override fun toString(): String {
        return "GithubAppConfig(id=$id, appId='$appId', installationId=$installationId, organization=$organization, isActive=$isActive)"
    }
}
