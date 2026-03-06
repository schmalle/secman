package com.secman.config

import io.micronaut.context.annotation.Context
import io.micronaut.context.annotation.Requires
import io.micronaut.security.token.jwt.generator.JwtTokenGenerator
import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.Base64

@Context
@Requires(notEnv = ["test"])
@Singleton
class JwtSigningValidator(
    private val jwtTokenGenerator: JwtTokenGenerator
) {
    private val logger = LoggerFactory.getLogger(JwtSigningValidator::class.java)

    @PostConstruct
    fun validate() {
        val testClaims = mapOf("sub" to "startup-check", "iss" to "secman-backend-ng")
        val token = jwtTokenGenerator.generateToken(testClaims)

        if (token.isEmpty) {
            logger.error("=".repeat(70))
            logger.error("  JWT SIGNING FAILURE — token generation returned empty")
            logger.error("  Check application.yml for duplicate 'micronaut:' top-level keys")
            logger.error("=".repeat(70))
            throw IllegalStateException(
                "JWT token generation failed — no token produced. " +
                "Likely cause: duplicate 'micronaut:' key in application.yml overwrites security config."
            )
        }

        val tokenStr = token.get()
        val parts = tokenStr.split(".")
        if (parts.size != 3 || parts[2].isBlank()) {
            logger.error("=".repeat(70))
            logger.error("  CRITICAL: JWT tokens are UNSIGNED (alg:none)")
            logger.error("  Token has ${parts.size} parts, signature is ${if (parts.size == 3) "empty" else "missing"}")
            logger.error("  This means anyone can forge valid authentication tokens!")
            logger.error("  Check application.yml for duplicate 'micronaut:' top-level keys")
            logger.error("=".repeat(70))
            throw IllegalStateException(
                "JWT tokens are unsigned (alg:none) — critical security vulnerability. " +
                "Likely cause: duplicate 'micronaut:' key in application.yml overwrites security config."
            )
        }

        val headerJson = String(Base64.getUrlDecoder().decode(parts[0]))
        if (!headerJson.contains("HS256")) {
            logger.error("=".repeat(70))
            logger.error("  CRITICAL: JWT tokens are NOT signed with HS256")
            logger.error("  Header: $headerJson")
            logger.error("  Expected algorithm: HS256")
            logger.error("=".repeat(70))
            throw IllegalStateException(
                "JWT tokens are not signed with HS256. Header: $headerJson"
            )
        }

        logger.info("JWT signing validation passed: tokens are signed with HS256")
    }
}
