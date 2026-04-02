package com.secman.config

import io.micronaut.context.annotation.Context
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.context.env.Environment
import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Context
@Requires(notEnv = ["test", "cli"])
@Singleton
class DatabaseCredentialValidator(
    @Value("\${datasources.default.password}") private val dbPassword: String,
    private val environment: Environment
) {
    private val logger = LoggerFactory.getLogger(DatabaseCredentialValidator::class.java)

    companion object {
        private val INSECURE_PASSWORDS = setOf("CHANGEME", "changeme", "password", "root", "admin", "")
    }

    @PostConstruct
    fun validate() {
        if (dbPassword in INSECURE_PASSWORDS) {
            if ("dev" in environment.activeNames) {
                logger.warn("=".repeat(70))
                logger.warn("  WARNING: Using insecure default database password in dev!")
                logger.warn("  Set DB_PASSWORD to a strong value for production.")
                logger.warn("=".repeat(70))
            } else {
                logger.error("=".repeat(70))
                logger.error("  CRITICAL: Using insecure default database password!")
                logger.error("  Set the DB_PASSWORD environment variable to a strong value.")
                logger.error("  The default password is publicly known and insecure.")
                logger.error("=".repeat(70))
                throw IllegalStateException(
                    "CRITICAL: Application cannot start with an insecure database password. " +
                    "Set the DB_PASSWORD environment variable to a strong value."
                )
            }
        }
        logger.debug("Database credential validation passed")
    }
}
