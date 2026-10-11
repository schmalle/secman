package com.secman.service.aibuddy

import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariDataSource
import com.secman.config.AiBuddyConfig
import com.secman.dto.AiBuddySystem
import com.secman.repository.ExceptionMatchSql
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Singleton
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

internal fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

data class RecordedFinding(
    val id: Long, val identifier: String?, val product: String?, val severity: String?, val source: String,
    val scanAt: Instant?, val importAt: Instant?, val firstSeenAt: Instant?, val patchDate: Instant?,
    val cveDate: Instant?, val excepted: Boolean, val productClass: String
) {
    val ref: String get() = "f$id"
    val excluded: Boolean get() = productClass == "INSTALLER_ARTIFACT"
    val cve: String? get() = identifier?.uppercase()?.takeIf { CVE.matches(it) }
    companion object { val CVE = Regex("CVE-[0-9]{4}-[0-9]{4,}") }
}
data class RecordedProduct(
    val id: Long, val name: String, val vendor: String?, val version: String?, val productClass: String,
    val importedAt: Instant?, val installedAt: Instant?, val lastUpdatedAt: Instant?, val runId: String?
)
data class RecordedContext(val id: Long, val kind: String, val version: String)
data class AiBuddySnapshot(
    val system: AiBuddySystem, val capturedAt: Instant, val lastSeen: Instant?, val sensorLastSeen: Instant?,
    val findings: List<RecordedFinding>, val products: List<RecordedProduct>, val aids: List<String>,
    val context: List<RecordedContext>, val sensitiveValues: List<String>, val fingerprint: String
)

@Singleton
open class AiBuddySnapshotService(
    private val dataSource: DataSource,
    private val mapper: ObjectMapper,
    private val config: AiBuddyConfig
) {
    /** A dedicated short JDBC transaction keeps all batches on one repeatable-read snapshot. */
    open fun capture(assetId: Long): AiBuddySnapshot = physicalDataSource().connection.use { connection ->
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.isReadOnly = true
        connection.autoCommit = false
        try {
            val snapshot = readSnapshot(connection, assetId)
            connection.commit()
            snapshot
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }

    // Match HealthController's JDBC unwrap: the contextual proxy needs an ambient transaction.
    // This operation deliberately owns a separate short transaction, never one spanning provider I/O.
    private fun physicalDataSource(): DataSource = when {
        dataSource is HikariDataSource -> dataSource
        dataSource.isWrapperFor(HikariDataSource::class.java) -> dataSource.unwrap(HikariDataSource::class.java)
        else -> dataSource
    }

    private fun readSnapshot(connection: Connection, assetId: Long): AiBuddySnapshot {
        val asset = query(connection, "SELECT * FROM asset WHERE id = ?", listOf(assetId)) { r ->
            Triple(
                AiBuddySystem(assetId, r.getString("name"), r.getString("os_version"),
                    build = Regex("(?:10\\.0\\.)?([0-9]{5}\\.[0-9]+)").find(r.getString("os_version") ?: "")?.groupValues?.get(1),
                    edition = Regex("(?i)\\b(Standard|Datacenter)\\b").find(r.getString("os_version") ?: "")?.value,
                    architecture = Regex("(?i)\\b(x64|ARM64|x86)\\b").find(r.getString("os_version") ?: "")?.value),
                r.instant("last_seen") to r.instant("crowdstrike_agent_seen_at"),
                listOfNotNull(r.getString("name"), r.getString("crowdstrike_hostname"), r.getString("ip"),
                    r.getString("owner"), r.getString("cloud_account_id"), r.getString("cloud_instance_id"), r.getString("ad_domain"))
            )
        }.singleOrNull() ?: throw HttpStatusException(HttpStatus.NOT_FOUND, "ASSET_OR_JOB_NOT_FOUND")
        val snapshotTime = query(connection, "SELECT NOW(6) AS snapshot_time", emptyList()) { it.getTimestamp("snapshot_time") }.single()
        val capturedAt = Instant.now()
        val findings = readFindings(connection, assetId, snapshotTime)
        val products = readProducts(connection, assetId)
        val aids = query(connection, "SELECT crowdstrike_aid FROM crowdstrike_asset_identity WHERE asset_id = ? ORDER BY crowdstrike_aid LIMIT 101", listOf(assetId)) { it.getString(1) }
        if (aids.size > 100) tooLarge()
        val ips = query(connection, "SELECT ip_address FROM asset_ip_address WHERE asset_id = ? LIMIT 101", listOf(assetId)) { it.getString(1) }
        if (ips.size > 100) tooLarge()
        val context = readContext(connection, assetId)
        val canonical = mapper.writeValueAsBytes(listOf("snapshot-v1", asset.first, asset.second, findings, products, aids, context))
        if (canonical.size > config.maxSnapshotBytes) tooLarge()
        return AiBuddySnapshot(asset.first, capturedAt, asset.second.first, asset.second.second,
            findings, products, aids, context, asset.third + ips + aids, digest(canonical))
    }

    private fun readFindings(connection: Connection, assetId: Long, snapshotTime: java.sql.Timestamp): List<RecordedFinding> {
        val rows = mutableListOf<RecordedFinding>()
        var after = 0L
        do {
            val page = query(connection, """
                SELECT v.*, EXISTS(SELECT 1 FROM vulnerability_exception e
                    WHERE ${ExceptionMatchSql.EXCEPTION_MATCH.replace("NOW()", "?")}) AS active_exception
                FROM ${com.secman.repository.CrowdStrikeSnapshotSql.CURRENT_FINDINGS} v JOIN asset a ON a.id = v.asset_id
                WHERE v.asset_id = ? AND v.id > ? AND UPPER(v.cvss_severity) IN ('HIGH', 'CRITICAL') ORDER BY v.id LIMIT 500
            """.trimIndent(), listOf(snapshotTime, assetId, after)) { r ->
                RecordedFinding(r.getLong("id"), r.getString("vulnerability_id"), r.getString("vulnerable_product_versions"),
                    r.getString("cvss_severity"), r.getString("source"), r.instant("scan_timestamp"),
                    r.instant("import_timestamp"), r.instant("first_seen_at"), r.instant("patch_publication_date"),
                    r.instant("cve_published_date"), r.getBoolean("active_exception"), r.getString("product_class"))
            }
            rows.addAll(page)
            if (rows.size > config.maxFindings) tooLarge()
            after = page.lastOrNull()?.id ?: after
        } while (page.size == 500)
        return rows
    }

    private fun readProducts(connection: Connection, assetId: Long): List<RecordedProduct> {
        val rows = mutableListOf<RecordedProduct>()
        var after = 0L
        do {
            val page = query(connection, "SELECT * FROM installed_product WHERE asset_id = ? AND id > ? ORDER BY id LIMIT 500", listOf(assetId, after)) { r ->
                RecordedProduct(r.getLong("id"), r.getString("name"), r.getString("vendor"), r.getString("version"),
                    r.getString("product_class"), r.instant("imported_at"), r.instant("installed_at"),
                    r.instant("last_updated_at"), r.getString("import_run_id"))
            }
            rows.addAll(page)
            if (rows.size > config.maxInventoryRows) tooLarge()
            after = page.lastOrNull()?.id ?: after
        } while (page.size == 500)
        return rows
    }

    private fun readContext(connection: Connection, assetId: Long): List<RecordedContext> {
        val integrations = query(connection, """
            SELECT f.id, f.state, SHA2(JSON_ARRAY(f.recommendation, f.evidence, f.last_seen_at, f.resolved_at), 256) AS context_digest
            FROM integration_finding f JOIN integration_subject s ON s.id = f.subject_id
            WHERE s.asset_id = ? ORDER BY f.id LIMIT 10001
        """.trimIndent(), listOf(assetId)) { r ->
            RecordedContext(r.getLong(1), "INTEGRATION_${r.getString(2)}",
                r.getString("context_digest"))
        }
        val lifecycle = query(connection, """
            SELECT id, status, component_name, component_version, eol_date, detected_at
            FROM eol_finding WHERE asset_id = ? ORDER BY id LIMIT 10001
        """.trimIndent(), listOf(assetId)) { r ->
            RecordedContext(r.getLong(1), "EOL_${r.getString(2)}",
                digest(mapper.writeValueAsBytes((3..6).map { r.getString(it) })))
        }
        if (integrations.size > 10_000 || lifecycle.size > 10_000) tooLarge()
        return integrations + lifecycle
    }

    private fun <T> query(connection: Connection, sql: String, values: List<Any>, map: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = 15
            values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
        }

    private fun ResultSet.instant(column: String): Instant? =
        getTimestamp(column)?.toLocalDateTime()?.toInstant(ZoneOffset.UTC)

    private fun tooLarge(): Nothing = throw HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SCOPE_TOO_LARGE")
}
