package com.secman.domain

import com.secman.domain.enums.EmailProvider
import com.secman.domain.enums.TestAccountStatus
import com.secman.util.EncryptedStringConverter
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * Entity representing a test email account for validation and testing purposes
 */
@Entity
@Table(name = "test_email_accounts")
@Serdeable
data class TestEmailAccount(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(nullable = false, length = 255, unique = true)
    val emailAddress: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val provider: EmailProvider,

    @Column(nullable = true, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter::class)
    val credentials: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val status: TestAccountStatus = TestAccountStatus.VERIFICATION_PENDING,

    @Column(nullable = true, columnDefinition = "TEXT")
    val lastTestResult: String? = null,

    @Column(nullable = true)
    val lastTestedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(nullable = false)
    val updatedAt: LocalDateTime? = null
) {
    companion object {
        /**
         * Create a new test email account with encrypted credentials
         */
        fun create(
            name: String,
            emailAddress: String,
            provider: EmailProvider,
            credentialsMap: Map<String, Any>
        ): TestEmailAccount {
            // Convert credentials map to JSON string for encryption
            val credentialsJson = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<Map<String, Any>>(),
                credentialsMap
            )

            return TestEmailAccount(
                name = name,
                emailAddress = emailAddress,
                provider = provider,
                credentials = credentialsJson,
                status = TestAccountStatus.VERIFICATION_PENDING
            )
        }
    }

    /**
     * Get decrypted credentials as a map
     */
    fun getCredentialsMap(): Map<String, Any> {
        return if (credentials.isNullOrBlank()) {
            emptyMap()
        } else {
            try {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(credentials)
            } catch (e: Exception) {
                throw RuntimeException("Failed to parse test account credentials", e)
            }
        }
    }

    /**
     * Update credentials with new values
     */
    fun withUpdatedCredentials(newCredentials: Map<String, Any>): TestEmailAccount {
        val credentialsJson = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<Map<String, Any>>(),
            newCredentials
        )

        return copy(
            credentials = credentialsJson,
            status = TestAccountStatus.VERIFICATION_PENDING, // Reset status when credentials change
            updatedAt = LocalDateTime.now()
        )
    }

    /**
     * Update status and test result
     */
    fun withTestResult(
        newStatus: TestAccountStatus,
        testResult: Map<String, Any>? = null
    ): TestEmailAccount {
        val testResultJson = testResult?.let { result ->
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<Map<String, Any>>(),
                result
            )
        }

        return copy(
            status = newStatus,
            lastTestResult = testResultJson,
            lastTestedAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
    }

    /**
     * Get last test result as a map
     */
    fun getLastTestResultMap(): Map<String, Any>? {
        return if (lastTestResult.isNullOrBlank()) {
            null
        } else {
            try {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(lastTestResult)
            } catch (e: Exception) {
                null // Return null if parsing fails
            }
        }
    }

    /**
     * Check if the account can be used for testing
     */
    fun canTest(): Boolean = status.canTest()

    /**
     * Check if the account can be verified
     */
    fun canVerify(): Boolean = status.canVerify()

    /**
     * Get provider-specific configuration
     */
    fun getProviderConfig(): Map<String, Any> {
        val baseConfig = getCredentialsMap().toMutableMap()

        // Add provider-specific defaults
        when (provider) {
            EmailProvider.GMAIL -> {
                baseConfig.putIfAbsent("host", "smtp.gmail.com")
                baseConfig.putIfAbsent("port", 587)
                baseConfig.putIfAbsent("tls", true)
                baseConfig.putIfAbsent("ssl", false)
            }
            EmailProvider.OUTLOOK -> {
                baseConfig.putIfAbsent("host", "smtp-mail.outlook.com")
                baseConfig.putIfAbsent("port", 587)
                baseConfig.putIfAbsent("tls", true)
                baseConfig.putIfAbsent("ssl", false)
            }
            EmailProvider.YAHOO -> {
                baseConfig.putIfAbsent("host", "smtp.mail.yahoo.com")
                baseConfig.putIfAbsent("port", 587)
                baseConfig.putIfAbsent("tls", true)
                baseConfig.putIfAbsent("ssl", false)
            }
            EmailProvider.SMTP_CUSTOM, EmailProvider.IMAP_CUSTOM -> {
                // Use credentials as-is for custom configurations
            }
        }

        return baseConfig
    }

    /**
     * Create a copy with sensitive data masked for API responses
     */
    fun toSafeResponse(): TestEmailAccount {
        return copy(
            credentials = if (!credentials.isNullOrBlank()) "***MASKED***" else null
        )
    }

    /**
     * Validate account configuration
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (name.isBlank()) {
            errors.add("Account name cannot be empty")
        }

        if (emailAddress.isBlank()) {
            errors.add("Email address cannot be empty")
        } else if (!emailAddress.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))) {
            errors.add("Invalid email address format")
        }

        val creds = getCredentialsMap()
        when (provider) {
            EmailProvider.GMAIL, EmailProvider.OUTLOOK, EmailProvider.YAHOO -> {
                if (!creds.containsKey("username") || creds["username"].toString().isBlank()) {
                    errors.add("Username is required for ${provider.name}")
                }
                if (!creds.containsKey("password") || creds["password"].toString().isBlank()) {
                    errors.add("Password is required for ${provider.name}")
                }
            }
            EmailProvider.SMTP_CUSTOM -> {
                if (!creds.containsKey("host") || creds["host"].toString().isBlank()) {
                    errors.add("SMTP host is required for custom configuration")
                }
                if (!creds.containsKey("port")) {
                    errors.add("SMTP port is required for custom configuration")
                }
            }
            EmailProvider.IMAP_CUSTOM -> {
                if (!creds.containsKey("host") || creds["host"].toString().isBlank()) {
                    errors.add("IMAP host is required for custom configuration")
                }
                if (!creds.containsKey("port")) {
                    errors.add("IMAP port is required for custom configuration")
                }
            }
        }

        return errors
    }
}