package com.secman.service.aibuddy

import com.secman.config.AiBuddyConfig
import com.secman.dto.*
import com.secman.security.BetaFeatureAccess
import com.secman.security.isBetaEligible
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

private class BuddyJob(val id: String, val requestId: String, val assetId: Long, val owner: Long, val auth: Authentication, val inventory: AiBuddyInventoryInput?) {
    val createdAt: Instant = Instant.now()
    var startedAt: Instant? = null
    var finishedAt: Instant? = null
    var leaseAt: Instant = createdAt
    var expiresAt: Instant? = null
    var state = "RUNNING"
    var stage = "SNAPSHOT"
    var error: AiBuddyError? = null
    var report: AiBuddyReport? = null
    var configuration = ""
    var bytes = 0
    var future: Future<*>? = null
}

@Singleton
open class AiBuddyJobService(
    private val config: AiBuddyConfig, private val settings: AiBuddySettingsService, private val beta: BetaFeatureAccess,
    private val access: AiBuddyAccessGuard, private val snapshots: AiBuddySnapshotService,
    private val microsoft: MicrosoftRemediationEvidenceSource, private val crowdstrike: CrowdStrikeRemediationEvidenceSource,
    private val mozilla: MozillaRemediationEvidenceSource,
    private val planner: RemediationPlanner, private val model: AiBuddyModelClient, private val json: AiBuddyJson,
    @Named("ai") private val executor: ExecutorService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobs = linkedMapOf<String, BuddyJob>()
    private val starts = ArrayDeque<Triple<Instant, Long, Long>>()

    fun capabilities(auth: Authentication): AiBuddyCapabilities {
        val user = beta.current(auth)
        val eligible = user.roles.isBetaEligible()
        val selected = settings.current()
        val unavailable = selected.unavailableReason()
        return AiBuddyCapabilities(eligible = eligible, roles = user.roles.map { it.name }, available = eligible && unavailable == null,
            unavailableReason = unavailable, enabled = selected.config.enabled)
    }

    @Synchronized fun start(assetId: Long, requestId: String, auth: Authentication, inventory: AiBuddyInventoryInput? = null): AiBuddyStartResponse {
        val user = access.check(assetId, auth)
        available()
        if (runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false).not()) throw AiBuddyFailure("INVALID_REQUEST")
        purge()
        jobs.values.firstOrNull { it.owner == user.id && it.requestId == requestId }?.let {
            if (it.assetId != assetId || it.inventory != inventory) throw AiBuddyFailure("REQUEST_ID_CONFLICT")
            return started(it)
        }
        jobs.values.firstOrNull { it.owner == user.id && it.assetId == assetId && it.state == "RUNNING" }?.let {
            if (it.inventory != inventory) throw AiBuddyFailure("REQUEST_ID_CONFLICT")
            return started(it)
        }
        if (jobs.size >= config.maxRetainedJobs || jobs.values.sumOf { it.bytes.toLong() } >= config.maxRetainedBytes ||
            jobs.values.count { it.state == "RUNNING" } >= config.maxConcurrentJobs) throw AiBuddyFailure("CAPACITY_EXHAUSTED", true, 30)
        val hourAgo = Instant.now().minusSeconds(3600)
        while (starts.firstOrNull()?.first?.isBefore(hourAgo) == true) starts.removeFirst()
        if (starts.size >= 1000 || starts.count { it.second == user.id } >= config.perUserStartsPerHour || starts.count { it.third == assetId } >= config.perAssetStartsPerHour) throw AiBuddyFailure("REQUEST_LIMITED", true, 3600)
        val job = BuddyJob(UUID.randomUUID().toString(), requestId, assetId, user.id!!, auth, inventory)
        job.configuration = configurationFingerprint()
        jobs[job.id] = job
        starts.add(Triple(job.createdAt, job.owner, assetId))
        job.future = executor.submit { generate(job) }
        log.info("AI buddy actor={} asset={} job={} outcome=admitted", user.id, assetId, job.id)
        return started(job)
    }

    @Synchronized fun status(assetId: Long, jobId: String, auth: Authentication): AiBuddyJobStatus {
        val job = owned(assetId, jobId, auth)
        job.leaseAt = Instant.now()
        return AiBuddyJobStatus(jobId = job.id, state = job.state, stage = job.stage, completedBatches = if (job.report != null) 1 else 0,
            totalBatches = 1, createdAt = job.createdAt, startedAt = job.startedAt, finishedAt = job.finishedAt,
            expiresAt = job.expiresAt, error = job.error, reportAvailable = job.report != null)
    }

    fun report(assetId: Long, jobId: String, auth: Authentication): AiBuddyReport {
        val report = synchronized(this) { owned(assetId, jobId, auth).report ?: throw AiBuddyFailure("REPORT_NOT_READY", true) }
        val current = snapshots.capture(assetId)
        synchronized(this) {
            if (owned(assetId, jobId, auth).report == null) throw AiBuddyFailure("REPORT_NOT_READY")
        }
        val reasons = report.snapshot.staleReasons.toMutableSet()
        if (current.fingerprint != report.snapshot.fingerprint) reasons += "SOURCE_CHANGED"
        if (report.evidence.any { it.source == "MICROSOFT" && !microsoft.evidenceCurrent(it, Instant.now()) }) reasons += "EVIDENCE_CHANGED_OR_EXPIRED"
        if (synchronized(this) { jobs[jobId]?.configuration } != configurationFingerprint()) reasons += "MODEL_OR_EVIDENCE_CONFIGURATION_CHANGED"
        return report.copy(snapshot = report.snapshot.copy(sourceChanged = "SOURCE_CHANGED" in reasons,
            stale = reasons.isNotEmpty(), staleReasons = reasons.sorted()))
    }

    @Synchronized fun cancel(assetId: Long, jobId: String, auth: Authentication) {
        val job = owned(assetId, jobId, auth)
        cancelJob(job, "CANCELLED")
    }

    private fun configurationFingerprint() = settings.current().fingerprint

    private fun available() { settings.current().unavailableReason()?.let { throw AiBuddyFailure(it) } }
    private fun started(job: BuddyJob) = AiBuddyStartResponse(jobId = job.id, state = job.state, createdAt = job.createdAt, expiresAt = job.expiresAt)

    private fun owned(assetId: Long, jobId: String, auth: Authentication): BuddyJob {
        val user = try { access.check(assetId, auth) } catch (e: HttpStatusException) {
            jobs[jobId]?.takeIf { it.assetId == assetId && it.owner.toString() == auth.attributes["userId"]?.toString() }?.let { cancelJob(it, "ACCESS_REVOKED") }
            throw e
        }
        available()
        purge()
        return jobs[jobId]?.takeIf { it.assetId == assetId && it.owner == user.id }
            ?: throw HttpStatusException(HttpStatus.NOT_FOUND, "ASSET_OR_JOB_NOT_FOUND")
    }

    private fun checkpoint(job: BuddyJob) {
        synchronized(this) {
            if (job.state != "RUNNING" || Thread.currentThread().isInterrupted) throw AiBuddyFailure("CANCELLED")
            available()
            if (job.configuration != configurationFingerprint()) throw AiBuddyFailure("CONFIGURATION_CHANGED")
            if (Instant.now().isAfter(job.createdAt.plusSeconds(config.jobTimeoutSeconds))) throw AiBuddyFailure("JOB_TIMEOUT", true)
            if (Instant.now().isAfter(job.leaseAt.plusSeconds(config.clientLeaseSeconds))) throw AiBuddyFailure("CLIENT_LEASE_EXPIRED")
        }
        access.check(job.assetId, job.auth)
    }

    private fun generate(job: BuddyJob) {
        try {
            synchronized(this) { job.startedAt = Instant.now() }
            checkpoint(job)
            val recorded = snapshots.capture(job.assetId)
            val snapshot = job.inventory?.apply(recorded) ?: recorded
            synchronized(this) { job.stage = "EVIDENCE" }
            val selected = settings.current().config
            val budget = EvidenceBudget(selected) { checkpoint(job) }
            val sources = if (snapshot.findings.none { !it.excluded }) emptyList()
                else listOf(mozilla.fetch(snapshot, budget), microsoft.fetch(snapshot, budget), crowdstrike.fetch(snapshot, budget))
            val allCandidates = planner.groupInvestigations(sources.flatMap { it.candidates })
            var limitations = sources.flatMap { it.limitations }.toSet()
            if (job.inventory != null) limitations += "USER_SUPPLIED_INVENTORY_REQUIRES_CONFIRMATION"
            val allowedIds = allCandidates.map { it.id }.distinct().take(1000).toSet()
            if (allCandidates.any { it.id !in allowedIds }) limitations += "EVIDENCE_CANDIDATES_BOUNDED"
            val candidates = planner.select(planner.consolidate(allCandidates.filter { it.id in allowedIds }))
            var modelUsed: String? = null
            var ordered = candidates
            if (candidates.any { it.relationshipVerified }) {
                synchronized(this) { job.stage = "ANALYSIS" }
                try {
                    val ids = model.order(candidates) { checkpoint(job) }
                    ordered = ids.map { id -> candidates.single { it.id == id } }
                    modelUsed = selected.model
                } catch (e: AiBuddyFailure) {
                    if (e.code == "CANCELLED" || e.code == "CLIENT_LEASE_EXPIRED" || e.code == "JOB_TIMEOUT" || e.code == "CONFIGURATION_CHANGED") throw e
                    log.warn("AI buddy job={} stage=analysis outcome=partial code={}", job.id, e.code)
                    limitations = limitations + e.code + "DETERMINISTIC_EVIDENCE_REPORT_MODEL_UNAVAILABLE"
                }
            }
            checkpoint(job)
            synchronized(this) { job.stage = "VALIDATION" }
            val current = snapshots.capture(job.assetId)
            checkpoint(job)
            val expires = Instant.now().plusSeconds(config.retentionSeconds)
            val result = planner.report(job.id, expires, snapshot, ordered, modelUsed, limitations, current.fingerprint != snapshot.fingerprint, config.staleDays)
            AiBuddyReportValidator().validate(result, snapshot, ordered)
            val bytes = json.mapper.writeValueAsBytes(result).size
            if (bytes > config.maxReportBytes) throw AiBuddyFailure("SCOPE_TOO_LARGE")
            synchronized(this) {
                checkpoint(job)
                if (jobs.values.sumOf { it.bytes.toLong() } + bytes > config.maxRetainedBytes) throw AiBuddyFailure("CAPACITY_EXHAUSTED", true, 30)
                job.report = result; job.bytes = bytes; job.state = "COMPLETE"; job.stage = "COMPLETE"
                job.finishedAt = Instant.now(); job.expiresAt = expires
            }
            log.info("AI buddy job={} outcome=complete findings={} actions={} modelUsed={} bytes={}", job.id, snapshot.findings.size, ordered.size, modelUsed != null, bytes)
        } catch (e: Exception) {
            synchronized(this) {
                job.report = null; job.bytes = 0
                if (job.state == "RUNNING") {
                    job.state = "FAILED"; job.stage = "FAILED"; job.finishedAt = Instant.now()
                    job.expiresAt = Instant.now().plusSeconds(config.retentionSeconds)
                    job.error = if (e is AiBuddyFailure) AiBuddyError(e.code, e.retryable, e.retryAfter) else AiBuddyError("ANALYSIS_FAILED")
                }
            }
            log.warn("AI buddy job={} outcome={} code={}", job.id, job.state, job.error?.code)
        }
    }

    private fun cancelJob(job: BuddyJob, reason: String) {
        job.state = "CANCELLED"; job.stage = "CANCELLED"; job.report = null; job.bytes = 0
        job.error = AiBuddyError(reason); job.finishedAt = Instant.now(); job.expiresAt = Instant.now().plusSeconds(config.retentionSeconds)
        job.future?.cancel(true)
    }

    @Scheduled(fixedDelay = "15s") @Synchronized open fun purge() {
        val now = Instant.now()
        for (job in jobs.values) {
            if (job.state == "RUNNING" && now.isAfter(job.createdAt.plusSeconds(config.jobTimeoutSeconds))) cancelJob(job, "JOB_TIMEOUT")
            else if (job.state == "RUNNING" && now.isAfter(job.leaseAt.plusSeconds(config.clientLeaseSeconds))) cancelJob(job, "CLIENT_LEASE_EXPIRED")
            if (!settings.current().config.enabled && job.state != "CANCELLED") cancelJob(job, "FEATURE_DISABLED")
            if (job.state == "RUNNING") {
                try { access.check(job.assetId, job.auth) } catch (e: HttpStatusException) { cancelJob(job, "ACCESS_REVOKED") }
            }
        }
        jobs.entries.removeIf { it.value.expiresAt?.isBefore(now) == true }
    }
}
