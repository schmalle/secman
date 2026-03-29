package com.secman.util

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.security.crypto.encrypt.TextEncryptor
import java.security.SecureRandom
import java.util.*

@Converter
class EncryptedStringConverter : AttributeConverter<String?, String?> {

    companion object {
        private val log = LoggerFactory.getLogger(EncryptedStringConverter::class.java)

        private const val ENCRYPTION_PASSWORD_PROPERTY = "secman.encryption.password"
        private const val ENCRYPTION_SALT_PROPERTY = "secman.encryption.salt"

        // Default values for development - should be overridden in production
        private const val DEFAULT_PASSWORD = "SecManDefaultEncryptionPassword2024"
        private const val DEFAULT_SALT = "5c0744940b5c369b"

        private val textEncryptor: TextEncryptor by lazy {
            val password = System.getProperty(ENCRYPTION_PASSWORD_PROPERTY)
                ?: System.getenv("SECMAN_ENCRYPTION_PASSWORD")
            val salt = System.getProperty(ENCRYPTION_SALT_PROPERTY)
                ?: System.getenv("SECMAN_ENCRYPTION_SALT")

            val usingDefaultPassword = password == null
            val usingDefaultSalt = salt == null
            val isDevelopment = System.getenv("MICRONAUT_ENVIRONMENTS")
                ?.split(",")?.any { it.trim().equals("dev", ignoreCase = true) || it.trim().equals("test", ignoreCase = true) } == true

            if (usingDefaultPassword || usingDefaultSalt) {
                if (!isDevelopment) {
                    throw IllegalStateException(
                        "CRITICAL: Encryption secrets not configured! " +
                        "Set SECMAN_ENCRYPTION_PASSWORD and SECMAN_ENCRYPTION_SALT environment variables. " +
                        "Use EncryptedStringConverter.generatePassword() and generateSalt() to create secure values. " +
                        "For local development, set MICRONAUT_ENVIRONMENTS=dev to use insecure defaults."
                    )
                }
                log.warn("##########################################################################")
                log.warn("# SECURITY WARNING: Using default encryption secrets!                    #")
                log.warn("# Set SECMAN_ENCRYPTION_PASSWORD and SECMAN_ENCRYPTION_SALT env vars     #")
                log.warn("# Default secrets are PUBLIC and INSECURE - dev/test only.               #")
                log.warn("##########################################################################")
            }

            Encryptors.text(password ?: DEFAULT_PASSWORD, salt ?: DEFAULT_SALT)
        }

        /**
         * Generate a random salt for production use
         */
        fun generateSalt(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { String.format("%02x", it) }
        }

        /**
         * Generate a random password for production use
         */
        fun generatePassword(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getEncoder().encodeToString(bytes)
        }
    }

    /**
     * Convert the entity attribute to a database column value.
     * Encrypts the plaintext string for storage.
     */
    override fun convertToDatabaseColumn(attribute: String?): String? {
        return attribute?.let { plaintext ->
            try {
                textEncryptor.encrypt(plaintext)
            } catch (e: Exception) {
                throw RuntimeException("Failed to encrypt sensitive data", e)
            }
        }
    }

    /**
     * Convert the database column value to an entity attribute.
     * Decrypts the encrypted string for use in the application.
     */
    override fun convertToEntityAttribute(dbData: String?): String? {
        return dbData?.let { encrypted ->
            try {
                textEncryptor.decrypt(encrypted)
            } catch (e: Exception) {
                throw RuntimeException("Failed to decrypt sensitive data. " +
                    "This may indicate corrupted data or mismatched encryption keys.", e)
            }
        }
    }
}