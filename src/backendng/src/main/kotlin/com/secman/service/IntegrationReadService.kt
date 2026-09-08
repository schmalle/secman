package com.secman.service

import com.secman.domain.*
import com.secman.dto.*
import com.secman.repository.IntegrationRepository
import com.secman.repository.IntegrationPageQuery
import com.secman.repository.IntegrationCountQuery
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import java.time.Instant

@Singleton
@Transactional
open class IntegrationReadService(private val repository: IntegrationRepository, private val access: IntegrationAccessService,
                                  private val mapper: com.fasterxml.jackson.databind.ObjectMapper) {
    open fun scanners(auth: Authentication): List<IntegrationScannerDto> {
        val ids = access.assetIds(auth)
        val admin = "ADMIN" in auth.roles
        if (!admin && ids.isEmpty()) return emptyList()
        return repository.page(IntegrationScanner::class.java, IntegrationPageQuery.SCANNERS,
            mapOf("unscoped" to admin, "assets" to ids.ifEmpty { setOf(-1L) }), 0, 100).content.map(IntegrationAdminService::scannerDto)
    }

    open fun subjects(scannerId: Long, page: Int, size: Int, auth: Authentication): IntegrationPage<IntegrationSubjectDto> {
        pagination(page, size)
        val ids = access.assetIds(auth)
        if (ids.isEmpty()) return emptyPage(page, size)
        val result = repository.page(IntegrationSubject::class.java, IntegrationPageQuery.SUBJECTS,
            mapOf("scanner" to scannerId, "assets" to ids), page, size)
        return mapped(result, subjectDtos(result.content))
    }

    open fun subject(id: Long, auth: Authentication): IntegrationSubjectDto {
        val subject = repository.find(IntegrationSubject::class.java, id) ?: access.notFound()
        access.requireAsset(subject.assetId, auth)
        return subjectDtos(listOf(subject)).single()
    }

    open fun runs(page: Int, size: Int, scannerId: Long?, subjectId: Long?, auth: Authentication): IntegrationPage<IntegrationRunDto> {
        pagination(page, size)
        val ids = access.assetIds(auth)
        if (ids.isEmpty()) return emptyPage(page, size)
        val result = repository.page(IntegrationRun::class.java, IntegrationPageQuery.RUNS,
            mapOf("assets" to ids, "scanner" to scannerId, "subject" to subjectId), page, size)
        val subjects = repository.findMany(IntegrationSubject::class.java, result.content.map { it.subjectId }).associateBy { it.id }
        val assets = repository.findMany(Asset::class.java, subjects.values.map { it.assetId }).associateBy { it.id }
        val scanners = repository.findMany(IntegrationScanner::class.java, result.content.map { it.scannerId }).associateBy { it.id }
        return mapped(result, result.content.map { r ->
            IntegrationRunDto(r.id!!, r.scannerId, r.subjectId, scanners.getValue(r.scannerId).name,
                assets.getValue(subjects.getValue(r.subjectId).assetId).name, r.status, r.completeCoverage,
                r.startedAt, r.completedAt, r.accepted, r.resolved, r.metadataJson)
        })
    }

    open fun findings(page: Int, size: Int, filter: IntegrationFindingFilter, auth: Authentication): IntegrationPage<IntegrationFindingDto> {
        pagination(page, size)
        if (filter.source != null && filter.source !in IntegrationRunValidator.SOURCES ||
            filter.severity != null && filter.severity !in IntegrationRunValidator.SEVERITIES ||
            filter.state != null && filter.state !in setOf("OPEN", "RESOLVED") ||
            (filter.search?.length ?: 0) > 200 || (filter.owner?.length ?: 0) > 255) invalid("Invalid finding filter")
        val ids = access.assetIds(auth)
        if (ids.isEmpty()) return emptyPage(page, size)
        val params = mapOf("assets" to ids, "scanner" to filter.scannerId, "subject" to filter.subjectId,
            "repo" to filter.githubRepositoryId, "source" to filter.source, "owner" to filter.owner,
            "severity" to filter.severity, "state" to filter.state, "search" to filter.search?.takeIf { it.isNotBlank() }?.lowercase())
        val result = repository.page(IntegrationFinding::class.java, IntegrationPageQuery.FINDINGS, params, page, size)
        return mapped(result, findingDtos(result.content))
    }

    open fun finding(id: Long, auth: Authentication): IntegrationFindingDto {
        val finding = authorizedFinding(id, auth)
        return findingDtos(listOf(finding)).single()
    }

    open fun attachment(findingId: Long, attachmentId: Long, auth: Authentication): IntegrationAttachment {
        authorizedFinding(findingId, auth)
        val attachment = repository.find(IntegrationAttachment::class.java, attachmentId) ?: access.notFound()
        if (attachment.findingId != findingId) access.notFound()
        return attachment
    }

    open fun summary(auth: Authentication): IntegrationSummaryDto = summaryFor(access.assetIds(auth))

    open fun run(id: Long, auth: Authentication): IntegrationRunDetailDto {
        val r = repository.find(IntegrationRun::class.java, id) ?: access.notFound()
        val s = repository.find(IntegrationSubject::class.java, r.subjectId) ?: access.notFound()
        access.requireAsset(s.assetId, auth)
        val a = repository.find(Asset::class.java, s.assetId) ?: access.notFound()
        val c = repository.find(IntegrationScanner::class.java, r.scannerId) ?: access.notFound()
        val dto = IntegrationRunDto(r.id!!, r.scannerId, r.subjectId, c.name, a.name, r.status, r.completeCoverage,
            r.startedAt, r.completedAt, r.accepted, r.resolved, r.metadataJson)
        val historical = mapper.readValue(r.findingsJson, Array<IntegrationFindingInput>::class.java).toList()
        val attachments = repository.runAttachmentMetadata(r.id!!).map {
            IntegrationRunAttachmentDto(it[0] as Long, it[1] as Long, it[2] as String, it[3] as String)
        }
        return IntegrationRunDetailDto(dto, historical, attachments)
    }

    /** Internal aggregate only; callers exposing this must enforce their ADMIN/SECCHAMPION policy. */
    open fun globalSummary(): IntegrationSummaryDto = summaryFor(null)

    private fun summaryFor(assetIds: Set<Long>?): IntegrationSummaryDto {
        if (assetIds?.isEmpty() == true) return IntegrationSummaryDto(0, 0, 0, 0, 0, 0, 0)
        fun count(query: IntegrationCountQuery) = repository.count(query, assetIds)
        return IntegrationSummaryDto(
            count(IntegrationCountQuery.SCANNERS), count(IntegrationCountQuery.SUBJECTS),
            count(IntegrationCountQuery.HEALTHY), count(IntegrationCountQuery.FAILED),
            count(IntegrationCountQuery.UNSCANNED), count(IntegrationCountQuery.STALE), count(IntegrationCountQuery.FINDINGS)
        )
    }

    private fun authorizedFinding(id: Long, auth: Authentication): IntegrationFinding {
        val finding = repository.find(IntegrationFinding::class.java, id) ?: access.notFound()
        val subject = repository.find(IntegrationSubject::class.java, finding.subjectId) ?: access.notFound()
        access.requireAsset(subject.assetId, auth)
        return finding
    }

    private fun subjectDtos(subjects: List<IntegrationSubject>): List<IntegrationSubjectDto> {
        val assets = repository.findMany(Asset::class.java, subjects.map { it.assetId }).associateBy { it.id }
        val scanners = repository.findMany(IntegrationScanner::class.java, subjects.map { it.scannerId }).associateBy { it.id }
        val repos = repository.findMany(GithubRepository::class.java, subjects.mapNotNull { it.githubRepositoryId }).associateBy { it.id }
        val counts = repository.openCounts(subjects.map { it.id!! })
        return subjects.map { s ->
            val a = assets.getValue(s.assetId)
            val c = scanners.getValue(s.scannerId)
            val r = repos[s.githubRepositoryId]
            IntegrationSubjectDto(s.id!!, s.scannerId, s.assetId, s.githubRepositoryId, a.name, a.uri, a.owner,
                r?.githubInstance, r?.githubRepoId, s.lastStatus, s.lastScanAt, s.lastSuccessfulScanAt,
                c.enabled && (s.lastSuccessfulScanAt ?: s.createdAt).plusSeconds(c.staleAfterHours * 3600L) < Instant.now(), counts[s.id] ?: 0)
        }
    }

    private fun findingDtos(findings: List<IntegrationFinding>): List<IntegrationFindingDto> {
        val subjects = repository.findMany(IntegrationSubject::class.java, findings.map { it.subjectId }).associateBy { it.id }
        val assets = repository.findMany(Asset::class.java, subjects.values.map { it.assetId }).associateBy { it.id }
        val scanners = repository.findMany(IntegrationScanner::class.java, subjects.values.map { it.scannerId }).associateBy { it.id }
        val projections = repository.findMany(Vulnerability::class.java, findings.mapNotNull { it.vulnerabilityId }).associateBy { it.id }
        val attachments = repository.attachmentMetadata(findings.map { it.id!! }).groupBy { it[0] as Long }
        return findings.map { f ->
            val s = subjects.getValue(f.subjectId); val a = assets.getValue(s.assetId); val c = scanners.getValue(s.scannerId)
            IntegrationFindingDto(f.id!!, c.id!!, c.name, c.source, s.id!!, a.name, a.id!!, s.githubRepositoryId, a.owner,
                f.externalId, f.severity, f.state, f.title, f.description, f.recommendation, f.evidence, f.filePath, f.lineRange,
                f.url, f.confidence, f.engine, f.model, f.commitSha, f.issueUrl, f.fixPrUrl,
                f.firstSeenAt, f.lastSeenAt, f.resolvedAt, f.vulnerabilityId?.takeIf { it in projections },
                projections[f.vulnerabilityId]?.excepted ?: false,
                attachments[f.id].orEmpty().map { IntegrationAttachmentDto(it[1] as Long, it[2] as String, it[3] as String) })
        }
    }

    private fun pagination(page: Int, size: Int) { if (page !in 0..100000 || size !in 1..100) invalid("Invalid pagination") }
    private fun invalid(message: String): Nothing = throw HttpStatusException(HttpStatus.BAD_REQUEST, message)
    private fun <T> emptyPage(page: Int, size: Int) = IntegrationPage<T>(emptyList(), 0, 0, page, size)
    private fun <T, R> mapped(page: IntegrationPage<T>, content: List<R>) = IntegrationPage(content, page.totalElements, page.totalPages, page.number, page.size)
}
