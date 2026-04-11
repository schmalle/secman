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
     *
     * Legacy fallback: Spring's Encryptors.text() emits lowercase hex strings whose
     * length is a multiple of 2 (and >= 32 chars to cover salt + IV + at least one
     * AES block). If the stored value clearly does NOT match that shape we treat it
     * as legacy plaintext (pre-encryption schema) and return it as-is so it can be
     * re-encrypted on next save.
     *
     * SECURITY: We must NOT silently swallow decryption errors on values that DO
     * look like ciphertext — that would let an attacker with DB write access bypass
     * encryption by storing plaintext-looking blobs. In that case we fail loudly so
     * the operator notices a key mismatch instead of silently leaking the raw value.
     */
    override fun convertToEntityAttribute(dbData: String?): String? {
        return dbData?.let { stored ->
            if (!looksLikeCiphertext(stored)) {
                // Pre-encryption legacy plaintext — return as-is so the entity can
                // round-trip and the next save will encrypt it.
                return@let stored
            }
            try {
                textEncryptor.decrypt(stored)
            } catch (e: Exception) {
                // Looks like ciphertext but decryption failed — almost certainly a
                // key/salt mismatch. Failing loud is safer than returning the raw
                // ciphertext and pretending nothing happened.
                log.error("Failed to decrypt value that appears to be ciphertext. " +
                    "Check SECMAN_ENCRYPTION_PASSWORD / SECMAN_ENCRYPTION_SALT — they must match " +
                    "the values used when the data was originally encrypted.")
                throw RuntimeException("Failed to decrypt sensitive data", e)
            }
        }
    }

    private fun looksLikeCiphertext(value: String): Boolean {
        // Spring's Encryptors.text() produces lowercase hex output. The minimum useful
        // size is salt(16) + iv(16) + 1 AES block(16) = 48 bytes -> 96 hex chars, but
        // we use 32 as a generous lower bound to err on the side of treating short
        // values as plaintext rather than failing on them.
        if (value.length < 32) return false
        if (value.length % 2 != 0) return false
        return value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}