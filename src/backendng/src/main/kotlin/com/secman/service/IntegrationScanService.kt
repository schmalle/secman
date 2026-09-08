package com.secman.service

import com.secman.domain.*
import com.secman.dto.*
import com.secman.repository.IntegrationRepository
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Validates the complete body before entering the atomic database apply. */
@Singleton
class IntegrationScanService(private val validator: IntegrationRunValidator, private val writer: IntegrationRunWriter) {
    fun submit(request: IntegrationRunRequest, authentication: Authentication): IntegrationRunAck {
        writer.authorize(request.scannerId, request.subjectId, authentication)
        return writer.apply(validator.validate(request), authentication)
    }
}

@Singleton
open class IntegrationRunWriter(
    private val repository: IntegrationRepository,
    private val access: IntegrationAccessService,
    private val exceptions: ExceptionMaterializationService
) {
    private val log = LoggerFactory.getLogger(IntegrationRunWriter::class.java)

    /** Reject unassigned callers before image decoding; apply rechecks under locks after validation. */
    @Transactional
    open fun authorize(scannerId: Long, subjectId: Long, auth: Authentication) {
        val scanner = repository.find(IntegrationScanner::class.java, scannerId) ?: access.notFound()
        access.requireWriter(scanner, auth)
        val subject = repository.find(IntegrationSubject::class.java, subjectId) ?: access.notFound()
        if (subject.scannerId != scanner.id) access.notFound()
        access.requireAsset(subject.assetId, auth)
    }

    @Transactional
    open fun apply(validated: ValidatedIntegrationRun, auth: Authentication): IntegrationRunAck {
        val request = validated.request
        val scanner = repository.scannerForSubmission(request.scannerId) ?: access.notFound()
        access.requireWriter(scanner, auth)
        val subject = repository.lock(IntegrationSubject::class.java, request.subjectId) ?: access.notFound()
        if (subject.scannerId != scanner.id) access.notFound()
        access.requireAsset(subject.assetId, auth)
        repository.replay(request.scannerId, request.subjectId, request.runKey)?.let {
            if (it.contentDigest != validated.digest) conflict("Run key was already used with different content")
            return ack(it, true)
        }
        if (!IntegrationLifecycle.isNewer(request, subject.lastScanAt)) conflict("A newer or simultaneous snapshot already exists")
        // Also serialize adoption across different scanners bound to the same asset.
        val asset = repository.lock(Asset::class.java, subject.assetId) ?: access.notFound()
        val run = IntegrationRun(scannerId = request.scannerId, subjectId = request.subjectId, runKey = request.runKey,
            contentDigest = validated.digest, status = request.status, completeCoverage = request.completeCoverage,
            startedAt = request.startedAt, completedAt = request.completedAt, metadataJson = request.metadataJson,
            findingsJson = validated.findingsJson, accepted = request.findings.size)
        repository.persist(run)
        request.findings.forEachIndexed { index, input ->
            observe(subject, asset, run, input, validated.attachments[index])
        }
        if (IntegrationLifecycle.resolvesAbsent(request)) {
            val present = request.findings.mapTo(hashSetOf()) { it.externalId }
            var after = 0L
            do {
                val batch = repository.openFindingPage(subject.id!!, after)
                batch.filter { it.externalId !in present }.forEach { finding ->
                    finding.state = "RESOLVED"
                    finding.resolvedAt = request.completedAt
                    val projection = finding.vulnerabilityId?.let { repository.find(Vulnerability::class.java, it) }
                    finding.vulnerabilityId = null
                    repository.flush()
                    if (projection != null) repository.remove(projection)
                    run.resolved++
                }
                after = batch.lastOrNull()?.id ?: after
            } while (batch.size == 500)
        }
        subject.lastScanAt = request.completedAt
        subject.lastStatus = request.status
        if (request.status == "SUCCESS") subject.lastSuccessfulScanAt = request.completedAt
        repository.flush()
        exceptions.recomputeForAsset(asset.id!!)
        log.info("Integration run accepted: actorId={} scannerId={} subjectId={} runId={} accepted={} resolved={}",
            scanner.serviceUserId, scanner.id, subject.id, run.id, run.accepted, run.resolved)
        return ack(run, false)
    }

    private fun observe(subject: IntegrationSubject, asset: Asset, run: IntegrationRun, input: IntegrationFindingInput,
                        attachments: List<ValidatedIntegrationAttachment>) {
        val finding = repository.finding(subject.id!!, input.externalId) ?: IntegrationFinding(
            subjectId = subject.id!!, externalId = input.externalId, firstSeenAt = run.completedAt
        )
        var projection = finding.vulnerabilityId?.let { repository.find(Vulnerability::class.java, it) }
        if (finding.id == null && input.legacyIds.isNotEmpty()) {
            val candidates = repository.legacyCandidates(asset.id!!, input.legacyIds)
            if (repository.legacyIdentityClaimed(asset.id!!, input.legacyIds) || candidates.size > 1 ||
                candidates.any { it.source != "CLI_MANUAL" || repository.projectionClaimed(it.id!!) }) conflict("Legacy finding adoption conflicts")
            projection = candidates.singleOrNull()
            projection?.let {
                val original = (it.firstSeenAt ?: it.scanTimestamp).toInstant(ZoneOffset.UTC)
                if (original < finding.firstSeenAt) finding.firstSeenAt = original
                finding.projectionKey = it.vulnerabilityId!!
                finding.projectionProduct = it.vulnerableProductVersions
            }
        }
        val timestamp = LocalDateTime.ofInstant(run.completedAt, ZoneOffset.UTC)
        val newProjection = projection == null
        if (projection == null) projection = Vulnerability(asset = asset, scanTimestamp = timestamp)
        if (finding.projectionKey.isEmpty()) {
            finding.projectionKey = identifier(run.scannerId, subject.id!!, input.externalId)
            finding.projectionProduct = input.externalId
        }
        projection.vulnerabilityId = finding.projectionKey
        projection.source = com.secman.constants.VulnerabilitySources.INTEGRATION
        projection.cvssSeverity = when (input.severity) {
            "INFO" -> "Informational"
            else -> input.severity.lowercase().replaceFirstChar { it.uppercase() }
        }
        // The stable external ID also anchors PRODUCT-scoped exceptions across title changes.
        projection.vulnerableProductVersions = finding.projectionProduct
        projection.scanTimestamp = timestamp
        projection.firstSeenAt = LocalDateTime.ofInstant(finding.firstSeenAt, ZoneOffset.UTC)
        projection.importTimestamp = timestamp
        if (newProjection) repository.persist(projection)
        finding.vulnerabilityId = projection.id
        finding.state = "OPEN"; finding.resolvedAt = null; finding.lastSeenAt = run.completedAt
        finding.lastRunId = run.id!!
        finding.severity = input.severity; finding.title = input.title; finding.description = input.description
        finding.recommendation = input.recommendation; finding.evidence = input.evidence; finding.filePath = input.filePath
        finding.lineRange = input.lineRange; finding.url = input.url; finding.confidence = input.confidence
        finding.engine = input.engine; finding.model = input.model; finding.commitSha = input.commitSha
        finding.issueUrl = input.issueUrl; finding.fixPrUrl = input.fixPrUrl
        if (finding.id == null) repository.persist(finding)
        attachments.forEach {
            repository.persist(IntegrationAttachment(findingId = finding.id!!, runId = run.id!!,
                fileName = it.fileName, contentType = it.contentType, content = it.bytes))
        }
    }

    private fun identifier(scannerId: Long, subjectId: Long, externalId: String): String =
        "INT:$scannerId:$subjectId:" + MessageDigest.getInstance("SHA-256").digest(externalId.toByteArray())
            .joinToString("") { "%02x".format(it) }
    private fun ack(run: IntegrationRun, replayed: Boolean) =
        IntegrationRunAck(run.id!!, run.scannerId, run.subjectId, run.status, run.accepted, run.resolved, replayed)
    private fun conflict(message: String): Nothing = throw HttpStatusException(HttpStatus.CONFLICT, message)
}
