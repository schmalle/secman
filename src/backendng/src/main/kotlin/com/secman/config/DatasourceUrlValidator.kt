package com.secman.config

import io.micronaut.context.annotation.Context
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Context
@Requires(notEnv = ["test", "cli"])
@Singleton
class DatasourceUrlValidator(
    @Value("\${datasources.default.url}") private val jdbcUrl: String
) {
    private val logger = LoggerFactory.getLogger(DatasourceUrlValidator::class.java)

    @PostConstruct
    fun validate() {
        if (!jdbcUrl.startsWith("jdbc:mariadb://")) {
            logger.error("=".repeat(70))
            logger.error("  INVALID DATABASE URL")
            logger.error("  DB_CONNECT = '$jdbcUrl'")
            logger.error("  Expected format: jdbc:mariadb://hostname:3306/secman")
            logger.error("  Fix the DB_CONNECT environment variable and restart.")
            logger.error("=".repeat(70))
            throw IllegalStateException(
                "Invalid DB_CONNECT: '$jdbcUrl' — must start with 'jdbc:mariadb://'. " +
                "Expected format: jdbc:mariadb://hostname:3306/secman"
            )
        }
        logger.info("Database URL validated: jdbc:mariadb://****")
    }
}
