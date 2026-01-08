package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Service for validating JWT signatures using JWKS (JSON Web Key Set).
 *
 * Provides secure ID token validation for OIDC providers by:
 * 1. Fetching JWKS from the provider's jwksUri
 * 2. Caching JWKS with automatic refresh (1 hour cache, refreshed on key-not-found)
 * 3. Validating JWT signatures using RS256 algorithm
 *
 * Security: This service prevents ID token forgery attacks by verifying the
 * cryptographic signature against the provider's published public keys.
 */
@Singleton
class JwksValidationService(
    @Client private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(JwksValidationService::class.java)

    // Cache JWKS by provider URI (1 hour TTL, max 20 providers)
    private val jwksCache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .maximumSize(20)
        .build<String, Map<String, JwkKey>>()

    /**
     * Validate JWT signature and return parsed claims if valid.
     *
     * @param jwt The JWT token to validate
     * @param jwksUri The JWKS endpoint URI
     * @param issuer Expected issuer (optional, for additional validation)
     * @return Parsed claims map if signature is valid, null otherwise
     */
    fun validateAndParseJwt(jwt: String, jwksUri: String?, issuer: String? = null): Map<String, Any>? {
        if (jwksUri.isNullOrBlank()) {
            logger.warn("No JWKS URI configured - falling back to unverified parsing (NOT RECOMMENDED)")
            return parseJwtWithoutVerification(jwt)
        }

        try {
            val parts = jwt.split(".")
            if (parts.size != 3) {
                logger.error("Invalid JWT format: expected 3 parts, got {}", parts.size)
                return null
            }

            val (headerB64, payloadB64, signatureB64) = parts

            // Parse header to get key ID (kid) and algorithm
            val header = parseBase64Json(headerB64)
            if (header == null) {
                logger.error("Failed to parse JWT header")
                return null
            }

            val kid = header["kid"] as? String
            val alg = header["alg"] as? String ?: "RS256"

            if (alg != "RS256") {
                logger.error("Unsupported JWT algorithm: {} (only RS256 is supported)", alg)
                return null
            }

            // Get public key from JWKS
            val publicKey = getPublicKey(jwksUri, kid)
            if (publicKey == null) {
                logger.error("Could not find matching public key for kid: {}", kid)
                return null
            }

            // Verify signature
            val signedContent = "$headerB64.$payloadB64"
            val signature = Base64.getUrlDecoder().decode(signatureB64)

            if (!verifySignature(signedContent, signature, publicKey)) {
                logger.error("JWT signature verification failed")
                return null
            }

            // Parse and return claims
            val claims = parseBase64Json(payloadB64)
            if (claims == null) {
                logger.error("Failed to parse JWT payload")
                return null
            }

            // Validate issuer if provided
            if (issuer != null) {
                val tokenIssuer = claims["iss"] as? String
                if (tokenIssuer != issuer) {
                    logger.error("Issuer mismatch: expected {}, got {}", issuer, tokenIssuer)
                    return null
                }
            }

            // Validate expiration
            val exp = (claims["exp"] as? Number)?.toLong()
            if (exp != null && exp < System.currentTimeMillis() / 1000) {
                logger.error("JWT has expired (exp: {})", exp)
                return null
            }

            logger.debug("JWT signature validated successfully")
            @Suppress("UNCHECKED_CAST")
            return claims as Map<String, Any>

        } catch (e: Exception) {
            logger.error("JWT validation failed: {}", e.message, e)
            return null
        }
    }

    /**
     * Get public key from JWKS, with caching and key-not-found refresh.
     */
    private fun getPublicKey(jwksUri: String, kid: String?): PublicKey? {
        // Try to get from cache first
        var keys = jwksCache.getIfPresent(jwksUri)

        if (keys == null) {
            keys = fetchJwks(jwksUri)
            if (keys != null) {
                jwksCache.put(jwksUri, keys)
            }
        }

        if (keys == null) {
            logger.error("Failed to fetch JWKS from {}", jwksUri)
            return null
        }

        // Find matching key by kid, or use first key if kid is null
        val jwk = if (kid != null) {
            keys[kid] ?: run {
                // Key not found - refresh JWKS in case of key rotation
                logger.info("Key ID {} not found in cache, refreshing JWKS", kid)
                val refreshedKeys = fetchJwks(jwksUri)
                if (refreshedKeys != null) {
                    jwksCache.put(jwksUri, refreshedKeys)
                    refreshedKeys[kid]
                } else {
                    null
                }
            }
        } else {
            // No kid - use first RSA key (common for single-key providers)
            keys.values.firstOrNull { it.kty == "RSA" }
        }

        if (jwk == null) {
            logger.error("No matching JWK found for kid: {}", kid)
            return null
        }

        return jwkToPublicKey(jwk)
    }

    /**
     * Fetch JWKS from the provider's endpoint.
     */
    private fun fetchJwks(jwksUri: String): Map<String, JwkKey>? {
        return try {
            logger.info("Fetching JWKS from {}", jwksUri)

            val request = HttpRequest.GET<String>(jwksUri)
            val response = httpClient.toBlocking().retrieve(request, String::class.java)

            val jwksResponse = objectMapper.readValue(response, JwksResponse::class.java)

            // Index keys by kid for fast lookup
            jwksResponse.keys
                .filter { it.kty == "RSA" && it.use != "enc" } // Only signing keys
                .associateBy { it.kid ?: "default" }
                .also { logger.info("Loaded {} RSA signing keys from JWKS", it.size) }

        } catch (e: Exception) {
            logger.error("Failed to fetch JWKS from {}: {}", jwksUri, e.message)
            null
        }
    }

    /**
     * Convert JWK to Java PublicKey.
     */
    private fun jwkToPublicKey(jwk: JwkKey): PublicKey? {
        return try {
            val n = Base64.getUrlDecoder().decode(jwk.n)
            val e = Base64.getUrlDecoder().decode(jwk.e)

            val modulus = BigInteger(1, n)
            val exponent = BigInteger(1, e)

            val spec = RSAPublicKeySpec(modulus, exponent)
            val factory = KeyFactory.getInstance("RSA")
            factory.generatePublic(spec)
        } catch (e: Exception) {
            logger.error("Failed to convert JWK to public key: {}", e.message)
            null
        }
    }

    /**
     * Verify RS256 signature.
     */
    private fun verifySignature(data: String, signature: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update(data.toByteArray(StandardCharsets.UTF_8))
            sig.verify(signature)
        } catch (e: Exception) {
            logger.error("Signature verification error: {}", e.message)
            false
        }
    }

    /**
     * Parse base64url-encoded JSON.
     */
    private fun parseBase64Json(base64: String): Map<String, Any?>? {
        return try {
            val decoded = Base64.getUrlDecoder().decode(base64)
            val json = String(decoded, StandardCharsets.UTF_8)
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            logger.error("Failed to parse base64 JSON: {}", e.message)
            null
        }
    }

    /**
     * Fallback: Parse JWT without signature verification.
     * Used only when JWKS URI is not configured (logs warning).
     */
    private fun parseJwtWithoutVerification(jwt: String): Map<String, Any>? {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return null

            val payload = parts[1]
            val decodedBytes = Base64.getUrlDecoder().decode(payload)
            val payloadJson = String(decodedBytes, StandardCharsets.UTF_8)

            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(payloadJson, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            logger.error("Failed to parse JWT: {}", e.message)
            null
        }
    }

    /**
     * Clear JWKS cache (for testing or forced refresh).
     */
    fun clearCache() {
        jwksCache.invalidateAll()
        logger.info("JWKS cache cleared")
    }
}

/**
 * JWKS response structure.
 */
data class JwksResponse(
    val keys: List<JwkKey> = emptyList()
)

/**
 * JWK (JSON Web Key) structure for RSA keys.
 */
data class JwkKey(
    val kty: String = "",    // Key Type (e.g., "RSA")
    val use: String? = null, // Key Use ("sig" for signature, "enc" for encryption)
    val kid: String? = null, // Key ID
    val alg: String? = null, // Algorithm (e.g., "RS256")
    val n: String = "",      // RSA modulus (base64url)
    val e: String = ""       // RSA exponent (base64url)
)
