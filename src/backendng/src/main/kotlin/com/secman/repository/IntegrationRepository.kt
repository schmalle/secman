package com.secman.repository

import com.secman.domain.*
import com.secman.dto.IntegrationPage
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType

/** Persistence operations used inside the integration services' transactions. */
@Singleton
class IntegrationRepository(private val em: EntityManager) {
    fun <T : Any> findMany(type: Class<T>, ids: Collection<Long>): List<T> {
        if (ids.isEmpty()) return emptyList()
        val sql = when (type) {
            Asset::class.java -> "FROM Asset x WHERE x.id IN :ids"
            IntegrationScanner::class.java -> "FROM IntegrationScanner x WHERE x.id IN :ids"
            IntegrationSubject::class.java -> "FROM IntegrationSubject x WHERE x.id IN :ids"
            GithubRepository::class.java -> "FROM GithubRepository x WHERE x.id IN :ids"
            Vulnerability::class.java -> "FROM Vulnerability x WHERE x.id IN :ids"
            else -> error("Unsupported integration lookup")
        }
        return em.createQuery(sql, type).setParameter("ids", ids).resultList
    }
    fun attachmentMetadata(findingIds: Collection<Long>): List<Array<Any>> {
        if (findingIds.isEmpty()) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return em.createQuery("SELECT a.findingId, a.id, a.fileName, a.contentType FROM IntegrationAttachment a, IntegrationFinding f WHERE a.findingId = f.id AND a.runId = f.lastRunId AND f.id IN :ids ORDER BY a.id")
            .setParameter("ids", findingIds).resultList as List<Array<Any>>
    }
    fun runAttachmentMetadata(runId: Long): List<Array<Any>> {
        @Suppress("UNCHECKED_CAST")
        return em.createQuery("SELECT a.id, a.findingId, a.fileName, a.contentType FROM IntegrationAttachment a WHERE a.runId = :run ORDER BY a.id")
            .setParameter("run", runId).setMaxResults(5000).resultList as List<Array<Any>>
    }
    fun openCounts(subjectIds: Collection<Long>): Map<Long, Long> {
        if (subjectIds.isEmpty()) return emptyMap()
        return em.createQuery("SELECT f.subjectId, COUNT(f) FROM IntegrationFinding f WHERE f.subjectId IN :ids AND f.state = 'OPEN' GROUP BY f.subjectId")
            .setParameter("ids", subjectIds).resultList.associate { row ->
                val values = row as Array<*>
                (values[0] as Long) to (values[1] as Long)
            }
    }
    fun <T : Any> find(type: Class<T>, id: Long): T? = em.find(type, id)
    fun <T : Any> lock(type: Class<T>, id: Long): T? = em.find(type, id, LockModeType.PESSIMISTIC_WRITE)
    fun scannerForSubmission(id: Long): IntegrationScanner? =
        em.find(IntegrationScanner::class.java, id, LockModeType.PESSIMISTIC_READ)
    fun persist(entity: Any) { em.persist(entity) }
    fun remove(entity: Any) { em.remove(entity) }
    fun flush() { em.flush() }

    fun replay(scannerId: Long, subjectId: Long, key: String): IntegrationRun? =
        em.createQuery("FROM IntegrationRun r WHERE r.scannerId = :scanner AND r.subjectId = :subject AND r.runKey = :key", IntegrationRun::class.java)
            .setParameter("scanner", scannerId).setParameter("subject", subjectId).setParameter("key", key).resultList.singleOrNull()

    fun finding(subjectId: Long, externalId: String): IntegrationFinding? =
        em.createQuery("FROM IntegrationFinding f WHERE f.subjectId = :subject AND f.externalId = :external", IntegrationFinding::class.java)
            .setParameter("subject", subjectId).setParameter("external", externalId).resultList.singleOrNull()

    fun openFindingPage(subjectId: Long, afterId: Long): List<IntegrationFinding> =
        em.createQuery("FROM IntegrationFinding f WHERE f.subjectId = :subject AND f.state = 'OPEN' AND f.id > :after ORDER BY f.id", IntegrationFinding::class.java)
            .setParameter("subject", subjectId).setParameter("after", afterId).setMaxResults(500).resultList

    fun legacyCandidates(assetId: Long, legacyIds: List<String>): List<Vulnerability> =
        if (legacyIds.isEmpty()) emptyList() else em.createQuery(
            "FROM Vulnerability v WHERE v.asset.id = :asset AND v.vulnerabilityId IN :ids", Vulnerability::class.java
        ).setParameter("asset", assetId).setParameter("ids", legacyIds).setMaxResults(11).resultList

    fun projectionClaimed(id: Long): Boolean = em.createQuery(
        "SELECT COUNT(f) FROM IntegrationFinding f WHERE f.vulnerabilityId = :id", Long::class.javaObjectType
    ).setParameter("id", id).singleResult > 0

    fun legacyIdentityClaimed(assetId: Long, ids: List<String>): Boolean =
        if (ids.isEmpty()) false else em.createQuery(
            "SELECT COUNT(f) FROM IntegrationFinding f, IntegrationSubject s WHERE f.subjectId = s.id AND s.assetId = :asset AND f.projectionKey IN :ids",
            Long::class.javaObjectType
        ).setParameter("asset", assetId).setParameter("ids", ids).singleResult > 0

    fun attachments(findingId: Long, runId: Long): List<IntegrationAttachment> = em.createQuery(
        "FROM IntegrationAttachment a WHERE a.findingId = :finding AND a.runId = :run ORDER BY a.id", IntegrationAttachment::class.java
    ).setParameter("finding", findingId).setParameter("run", runId).setMaxResults(10).resultList

    fun <T : Any> page(type: Class<T>, template: IntegrationPageQuery, params: Map<String, Any?>, page: Int, size: Int): IntegrationPage<T> {
        require(page in 0..100000 && size in 1..100) { "Invalid pagination" }
        val query = em.createQuery(template.select, type)
        val count = em.createQuery(template.count, Long::class.javaObjectType)
        params.forEach { (name, value) -> query.setParameter(name, value); count.setParameter(name, value) }
        val total = count.singleResult
        return IntegrationPage(query.setFirstResult(page * size).setMaxResults(size).resultList,
            total, ((total + size - 1) / size).toInt(), page, size)
    }

    fun count(template: IntegrationCountQuery, assetIds: Set<Long>?): Long {
        val query = em.createQuery(template.sql, Long::class.javaObjectType)
            .setParameter("unscoped", assetIds == null).setParameter("assets", assetIds?.ifEmpty { setOf(-1L) } ?: setOf(-1L))
        if (template in setOf(IntegrationCountQuery.HEALTHY, IntegrationCountQuery.STALE)) query.setParameter("now", java.time.Instant.now())
        return query.singleResult
    }

    fun boundSubject(scannerId: Long, assetId: Long): IntegrationSubject? = em.createQuery(
        "FROM IntegrationSubject s WHERE s.scannerId = :scanner AND s.assetId = :asset", IntegrationSubject::class.java
    ).setParameter("scanner", scannerId).setParameter("asset", assetId).resultList.singleOrNull()

    fun repositorySubject(repositoryId: Long): IntegrationSubject? = em.createQuery(
        "FROM IntegrationSubject s WHERE s.githubRepositoryId = :repo ORDER BY s.id", IntegrationSubject::class.java
    ).setParameter("repo", repositoryId).setMaxResults(1).resultList.firstOrNull()

    fun legacyAssets(name: String): List<Asset> = em.createQuery(
        "FROM Asset a WHERE a.name = :name", Asset::class.java
    ).setParameter("name", name).setMaxResults(2).resultList
}
