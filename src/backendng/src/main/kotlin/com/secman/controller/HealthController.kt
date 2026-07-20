package com.secman.controller

import com.zaxxer.hikari.HikariDataSource
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

@Controller
class HealthController @Inject constructor(
    private val dataSource: DataSource
) {

    companion object {
        private val logger = LoggerFactory.getLogger(HealthController::class.java)
        private const val DB_CHECK_TIMEOUT_SECONDS = 3L
        private val probeExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "health-db-probe").apply { isDaemon = true }
        }
    }

    @Serdeable
    data class HealthChecks(
        val database: String
    )

    @Serdeable
    data class HealthResponse(
        val status: String,
        val service: String,
        val version: String,
        val checks: HealthChecks
    )

    @Get("/health")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun health(): HttpResponse<HealthResponse> {
        val databaseUp = checkDatabase()
        val overallStatus = if (databaseUp) "UP" else "DOWN"
        val response = HealthResponse(
            status = overallStatus,
            service = "secman-backend-ng",
            version = "0.1",
            checks = HealthChecks(database = if (databaseUp) "UP" else "DOWN")
        )
        return if (databaseUp) {
            HttpResponse.ok(response)
        } else {
            HttpResponse.status<HealthResponse>(HttpStatus.SERVICE_UNAVAILABLE).body(response)
        }
    }

    /**
     * Bounded on a separate thread: Hikari's own connection-timeout is 30s,
     * far too slow for a watchdog probe that needs to detect DB loss quickly.
     */
    private fun checkDatabase(): Boolean {
        return try {
            probeExecutor.submit<Boolean> {
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT 1").use { it.next() }
                    }
                }
            }.get(DB_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            if (isPoolSaturatedWithLegitimateLoad()) {
                logger.warn(
                    "Health check: database probe timed out after {}s but HikariCP pool is " +
                        "fully saturated with active connections -- likely legitimate load " +
                        "(e.g. a CrowdStrike import), not a DB outage; reporting UP",
                    DB_CHECK_TIMEOUT_SECONDS
                )
                true
            } else {
                logger.warn("Health check: database probe timed out after {}s", DB_CHECK_TIMEOUT_SECONDS)
                false
            }
        } catch (e: Exception) {
            logger.warn("Health check: database probe failed: {}", e.message)
            false
        }
    }

    /**
     * Best-effort heuristic, invoked only when the probe above has already timed out:
     * distinguishes "pool busy with legitimate concurrent work" (e.g. a CrowdStrike import
     * holding several long transactions plus normal interactive traffic) from "pool
     * wedged/DB unreachable" -- the failure mode the probe's fast timeout exists to catch
     * (see docs/DB_CONNECTION_LOST_RUNBOOK.md). Saturated means every pooled connection is
     * checked out (active >= total) and the pool is at its configured ceiling
     * (total >= maximumPoolSize): no idle connection was available and there was no room to
     * open a new one, so a busy-but-healthy pool is the simplest explanation. Only meaningful
     * when backed by HikariCP (true in every deployed configuration); any other DataSource
     * (e.g. a test mock) safely falls through to false, preserving today's DOWN-on-timeout
     * behavior.
     */
    private fun isPoolSaturatedWithLegitimateLoad(): Boolean {
        val hikari = dataSource as? HikariDataSource ?: return false
        return try {
            val poolBean = hikari.hikariPoolMXBean ?: return false
            val active = poolBean.activeConnections
            val total = poolBean.totalConnections
            active >= total && total >= hikari.maximumPoolSize
        } catch (e: Exception) {
            logger.debug("Health check: unable to read HikariCP pool stats: {}", e.message)
            false
        }
    }
}
