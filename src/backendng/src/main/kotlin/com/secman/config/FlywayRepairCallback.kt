package com.secman.config

import io.micronaut.flyway.FlywayConfigurationCustomizer
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.slf4j.LoggerFactory

/**
 * Repairs Flyway schema history before migration runs.
 * - Removes failed migration entries (success=0)
 * - Drops and recreates flyway_schema_history if baseline is wrong
 *
 * This handles the case where Flyway is introduced to an existing database
 * where Hibernate hbm2ddl.auto:update had previously managed the schema.
 */
@Singleton
@Named("default")
class FlywayRepairCustomizer : FlywayConfigurationCustomizer {
    private val logger = LoggerFactory.getLogger(FlywayRepairCustomizer::class.java)

    override fun getName(): String = "default"

    override fun customizeFluentConfiguration(fluentConfiguration: FluentConfiguration) {
        try {
            val ds = fluentConfiguration.dataSource ?: return
            ds.connection.use { conn ->
                val stmt = conn.createStatement()

                // Check if flyway_schema_history exists
                val rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'"
                )
                rs.next()
                val tableExists = rs.getInt(1) > 0
                rs.close()

                if (tableExists) {
                    // Check if baseline is wrong (version < 185 means old/broken state)
                    val baselineRs = stmt.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE type = 'BASELINE' ORDER BY installed_rank LIMIT 1"
                    )
                    val needsReset = if (baselineRs.next()) {
                        val baselineVersion = baselineRs.getString(1)
                        baselineVersion.toIntOrNull()?.let { it < 185 } ?: true
                    } else {
                        // No baseline at all, check for failed entries
                        false
                    }
                    baselineRs.close()

                    if (needsReset) {
                        logger.warn("Dropping flyway_schema_history to re-baseline (old baseline found)")
                        stmt.executeUpdate("DROP TABLE flyway_schema_history")
                        // Flyway will re-create and baseline at the configured version
                    } else {
                        // Just clean up any failed entries
                        val deleted = stmt.executeUpdate(
                            "DELETE FROM flyway_schema_history WHERE success = 0"
                        )
                        if (deleted > 0) {
                            logger.warn("Removed $deleted failed migration entries from flyway_schema_history")
                        }
                    }
                }

                stmt.close()
            }
        } catch (e: Exception) {
            logger.warn("Could not repair flyway_schema_history: ${e.message}")
        }
    }
}
