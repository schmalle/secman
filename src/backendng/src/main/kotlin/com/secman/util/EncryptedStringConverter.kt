package com.secman.util

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.security.crypto.encrypt.TextEncryptor
import java.security.SecureRandom
import java.util.*

@Converter
class EncryptedStringConverter : AttributeConverter<String?, String?> {

    companion object {
        private const val ENCRYPTION_PASSWORD_PROPERTY = "secman.encryption.password"
        private const val ENCRYPTION_SALT_PROPERTY = "secman.encryption.salt"

        // Default values for development - should be overridden in production
        private const val DEFAULT_PASSWORD = "SecManDefaultEncryptionPassword2024"
        private const val DEFAULT_SALT = "5c0744940b5c369b"

        private val textEncryptor: TextEncryptor by lazy {
            val password = System.getProperty(ENCRYPTION_PASSWORD_PROPERTY)
                ?: System.getenv("SECMAN_ENCRYPTION_PASSWORD")
                ?: DEFAULT_PASSWORD

            val salt = System.getProperty(ENCRYPTION_SALT_PROPERTY)
                ?: System.getenv("SECMAN_ENCRYPTION_SALT")
                ?: DEFAULT_SALT

            Encryptors.text(password, salt)
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