package com.secman.relay

import com.secman.domain.ExceptionRequestStatus
import com.secman.repository.VulnerabilityExceptionRequestRepository
import com.secman.service.AdminSummaryService
import com.secman.service.AwsCleanServerKpiService
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.service.EdrCoverageKpiService
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Assembles the status snapshot that is pushed to the relay.
 *
 * Scope: this is the *administrative overview* — the same aggregate picture an
 * ADMIN sees on the dashboard, and nothing else. Three rules keep it that way,
 * and they are the reason a relay compromise is a disclosure of summary
 * statistics rather than of the vulnerability database:
 *
 *  1. **Aggregates, not records.** Counts and percentages, plus the two "top N"
 *     lists the admin summary email already sends. No CVE rows, no per-asset
 *     findings, no exception request contents, no user list.
 *  2. **No credentials, no configuration.** Nothing from `AppSettings`, no
 *     hostnames beyond the asset names already in the top-N lists, no tokens.
 *  3. **Opt-in per section.** `secman.relay.sections` selects what is built at
 *     all. A section that is not listed is never assembled, so it cannot leak.
 *
 * Everything here reads pre-computed caches or plain counts, so building a
 * snapshot every minute costs approximately nothing regardless of fleet size.
 */
@Singleton
open class RelaySnapshotBuilder(
    private val adminSummaryService: AdminSummaryService,
    private val awsCleanServerKpiService: AwsCleanServerKpiService,
    private val edrCoverageKpiService: EdrCoverageKpiService,
    private val crowdStrikeImportService: CrowdStrikeVulnerabilityImportService,
    private val exceptionRequestRepository: VulnerabilityExceptionRequestRepository
) {
    private val logger = LoggerFactory.getLogger(RelaySnapshotBuilder::class.java)

    companion object {
        const val SECTION_TOTALS = "totals"
        const val SECTION_KPIS = "kpis"
        const val SECTION_EXCEPTIONS = "exceptions"
        const val SECTION_IMPORTS = "imports"
        const val SECTION_TOP_PRODUCTS = "top-products"
        const val SECTION_TOP_SERVERS = "top-servers"

        /**
         * Every section this builder can produce. Section names are lowercase
         * with hyphens because the relay validates them against exactly that
         * character class before they reach a URL or a scope string.
         */
        val ALL_SECTIONS: List<String> = listOf(
            SECTION_TOTALS,
            SECTION_KPIS,
            SECTION_EXCEPTIONS,
            SECTION_IMPORTS,
            SECTION_TOP_PRODUCTS,
            SECTION_TOP_SERVERS
        )

        /** Renders an instant as the RFC 3339 UTC string the relay parses. */
        fun rfc3339(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

        /**
         * Renders a zone-less [LocalDateTime] as an ISO local timestamp.
         *
         * Deliberately *not* stamped with a zone: these values come out of the
         * database as LocalDateTime with no recorded offset, and inventing UTC
         * would silently shift every displayed time by the server's offset. The
         * app renders them as "server local time", which is what they are.
         */
        fun isoLocal(value: LocalDateTime?): String? =
            value?.let { DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(it) }
    }

    /**
     * Builds a snapshot containing the requested [sections].
     *
     * A section whose source throws is omitted and logged rather than failing
     * the whole push: a broken KPI cache should degrade one tile in the app,
     * not blind the on-call admin to everything else.
     */
    open fun build(instanceId: String, sections: List<String>): RelaySnapshot {
        val requested = sections.ifEmpty { ALL_SECTIONS }
        val built = LinkedHashMap<String, Any>()

        for (section in requested) {
            val value = try {
                buildSection(section)
            } catch (e: Exception) {
                logger.warn("Relay snapshot section '{}' could not be built and was omitted: {}", section, e.message)
                null
            }
            if (value != null) {
                built[section] = value
            }
        }

        if (built.isEmpty()) {
            // The relay refuses an empty snapshot, so say why here rather than
            // letting it come back as an opaque 400.
            throw IllegalStateException("No relay snapshot section could be built; check secman.relay.sections")
        }

        return RelaySnapshot(
            instanceId = instanceId,
            generatedAt = rfc3339(Instant.now()),
            sections = built
        )
    }

    private fun buildSection(section: String): Any? = when (section) {
        SECTION_TOTALS -> buildTotals()
        SECTION_KPIS -> buildKpis()
        SECTION_EXCEPTIONS -> buildExceptions()
        SECTION_IMPORTS -> buildImports()
        SECTION_TOP_PRODUCTS -> buildTopProducts()
        SECTION_TOP_SERVERS -> buildTopServers()
        else -> {
            logger.warn("Unknown relay snapshot section '{}' requested; ignoring", section)
            null
        }
    }

    private fun buildTotals(): Map<String, Any> {
        val stats = adminSummaryService.getSystemStatistics()
        return mapOf(
            "assets" to stats.assetCount,
            "vulnerabilities" to stats.vulnerabilityCount,
            "users" to stats.userCount
        )
    }

    private fun buildKpis(): Map<String, Any> {
        val aws = awsCleanServerKpiService.getKpi()
        val edr = edrCoverageKpiService.getKpi()

        // `available = false` is passed through rather than smoothed to zero:
        // "not measured yet" and "0% coverage" mean opposite things to whoever
        // is looking at the phone.
        val awsKpi = LinkedHashMap<String, Any>()
        awsKpi["available"] = aws.available
        aws.percentage?.let { awsKpi["percentage"] = it }
        aws.totalAwsServers?.let { awsKpi["totalServers"] = it }
        aws.cleanAwsServers?.let { awsKpi["cleanServers"] = it }
        isoLocal(aws.lastCalculatedAt)?.let { awsKpi["lastCalculatedAt"] = it }

        val edrKpi = LinkedHashMap<String, Any>()
        edrKpi["available"] = edr.available
        edr.percentage?.let { edrKpi["percentage"] = it }
        edr.totalEc2Instances?.let { edrKpi["totalInstances"] = it }
        edr.eligibleEc2Instances?.let { edrKpi["eligibleInstances"] = it }
        edr.coveredEc2Instances?.let { edrKpi["coveredInstances"] = it }
        edr.excludedByNoEdrException?.let { edrKpi["excludedByException"] = it }
        edr.agentSeenWithinDays?.let { edrKpi["agentSeenWithinDays"] = it }
        isoLocal(edr.lastCalculatedAt)?.let { edrKpi["lastCalculatedAt"] = it }

        return mapOf(
            "awsCleanServers" to awsKpi,
            "edrCoverage" to edrKpi
        )
    }

    private fun buildExceptions(): Map<String, Any> = mapOf(
        "pending" to exceptionRequestRepository.countByStatus(ExceptionRequestStatus.PENDING)
    )

    private fun buildImports(): Map<String, Any> {
        val status = crowdStrikeImportService.getLatestImportStatus()
            ?: return mapOf("crowdstrike" to mapOf("available" to false))

        val crowdstrike = LinkedHashMap<String, Any>()
        crowdstrike["available"] = true
        isoLocal(status.importedAt)?.let { crowdstrike["importedAt"] = it }
        crowdstrike["serversProcessed"] = status.serversProcessed
        crowdstrike["vulnerabilitiesImported"] = status.vulnerabilitiesImported
        crowdstrike["errorCount"] = status.errorCount
        // importedBy is a username. It is the one identity-bearing field in the
        // snapshot and it names an operator, not a data subject; still, it is
        // omitted rather than pushed — the app has no use for it.
        return mapOf("crowdstrike" to crowdstrike)
    }

    private fun buildTopProducts(): Map<String, Any> {
        val stats = adminSummaryService.getSystemStatistics()
        return mapOf(
            "items" to stats.topProducts.map {
                mapOf("name" to it.name, "vulnerabilities" to it.vulnerabilityCount)
            }
        )
    }

    private fun buildTopServers(): Map<String, Any> {
        val stats = adminSummaryService.getSystemStatistics()
        return mapOf(
            "items" to stats.topServers.map {
                mapOf("name" to it.name, "vulnerabilities" to it.vulnerabilityCount)
            }
        )
    }
}
