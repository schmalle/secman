package com.secman.service

import com.secman.domain.*
import com.secman.dto.ProductExceptionBatchDraft
import com.secman.repository.UserRepository
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.json.JsonMapper
import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.*
import java.util.UUID

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class BatchSubmission(val previewId: String, val requestKey: UUID)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class BatchReceipt(val batchId: String, val status: String, val draft: ProductExceptionBatchDraft, val decidedBy: String?, val decidedAt: Instant?, val history: List<BatchDecision>, val actions: List<String>, val version: Int = 1)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ReviewedBatch(val previewId: String, val impact: ProductBatchPreview, val version: Int = 1)

@Singleton
@Transactional
open class ProductExceptionBatchService(
    private val em: EntityManager, private val users: UserRepository, private val mapper: JsonMapper,
    private val previews: ProductBatchPreviewService,
    private val events: io.micronaut.context.event.ApplicationEventPublisher<BatchDecisionEvent>,
    private val materialization: ExceptionMaterializationService
) {
    private fun lockedActor(name: String) = users.findByUsernameForUpdate(name).filter { it.enabled }.orElseThrow { denied() }
    private fun actor(name: String) = users.findByUsername(name).filter { it.enabled }.orElseThrow { denied() }
    open fun delegatedName(id: Long, expectedName: String): String {
        val user = users.findById(id).filter { it.enabled && it.username == expectedName }.orElseThrow { denied() }
        return user.username
    }

    private fun reviewer(user: User) = user.hasRole(User.Role.ADMIN) || user.hasRole(User.Role.SECCHAMPION)
    private fun denied() = HttpStatusException(HttpStatus.FORBIDDEN, "Batch or account unavailable")
    private fun conflict() = HttpStatusException(HttpStatus.CONFLICT, "Refresh the preview or check the current batch status")
    private fun draft(batch: ProductExceptionBatch) = checkNotNull(mapper.readValue(batch.manifest, ProductExceptionBatchDraft::class.java))
    private fun receipt(b: ProductExceptionBatch, user: User): BatchReceipt {
        val draft = draft(b)
        val status = if (b.status == "APPROVED" && !draft.expiresAt().isAfter(Instant.now())) "EXPIRED" else b.status
        val actions = buildList {
            if (status == "PENDING" && reviewer(user)) addAll(listOf("approve", "reject"))
            if (status in setOf("PENDING", "APPROVED") && (user.hasRole(User.Role.ADMIN) ||
                    (b.ownerId == user.id && (status == "PENDING" || b.autoApprove)))) add("cancel")
        }
        return BatchReceipt(b.id, status, draft, b.decidedBy, b.decidedAt, b.history.toList(), actions)
    }

    @io.micronaut.scheduling.annotation.Scheduled(fixedDelay = "15m", initialDelay = "15m")
    open fun removeExpiredPreviews() {
        val expired = em.createQuery("SELECT b FROM ProductExceptionBatch b WHERE b.status = 'PREVIEW' AND b.created < :cutoff ORDER BY b.created",
            ProductExceptionBatch::class.java).setParameter("cutoff", Instant.now().minusSeconds(900))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE).setMaxResults(500).resultList
        expired.forEach(em::remove)
    }
    private fun checkScope(d: ProductExceptionBatchDraft, name: String) = previews.authorize(d.accounts, name)

    open fun preview(input: ProductExceptionBatchDraft, name: String, assetIds: Set<Long>): ReviewedBatch {
        val user = lockedActor(name)
        val drafts = em.createQuery("SELECT b FROM ProductExceptionBatch b WHERE b.ownerId = :owner AND b.status = 'PREVIEW' ORDER BY b.created DESC, b.id",
            ProductExceptionBatch::class.java).setParameter("owner", user.id).setFirstResult(9).setMaxResults(500).resultList
        drafts.forEach(em::remove)
        val impact = previews.preview(input, name, assetIds, reviewer(user))
        val batch = ProductExceptionBatch(ownerId = user.id!!, manifest = mapper.writeValueAsString(impact.draft), autoApprove = reviewer(user), accounts = impact.draft.accounts.toMutableSet())
        em.persist(batch)
        return ReviewedBatch(batch.id, impact)
    }

    open fun submit(input: BatchSubmission, name: String): BatchReceipt {
        val user = lockedActor(name)
        val batch = em.find(ProductExceptionBatch::class.java, input.previewId, LockModeType.PESSIMISTIC_WRITE)
        if (batch == null || batch.ownerId != user.id) throw conflict()
        val d = draft(batch)
        checkScope(d, name)
        if (batch.requestKey == input.requestKey.toString()) return receipt(batch, user)
        if (batch.status != "PREVIEW" || batch.autoApprove != reviewer(user) ||
            !batch.created.plusSeconds(900).isAfter(Instant.now()) || !d.expiresAt().isAfter(Instant.now())) throw conflict()
        val used = em.createQuery("SELECT b.id FROM ProductExceptionBatch b WHERE b.ownerId = :owner AND b.requestKey = :key", String::class.java)
            .setParameter("owner", user.id).setParameter("key", input.requestKey.toString()).resultList
        if (used.isNotEmpty()) throw conflict()
        batch.requestKey = input.requestKey.toString()
        batch.status = "PENDING"
        if (batch.autoApprove) approve(batch, d, name, user.username)
        batch.history.add(BatchDecision(name, batch.status, Instant.now()))
        publish(batch, d)
        return receipt(batch, user)
    }

    open fun show(id: String, name: String): BatchReceipt {
        val b = em.find(ProductExceptionBatch::class.java, id) ?: throw denied()
        val user = actor(name)
        if (b.ownerId != user.id && !reviewer(user)) throw denied()
        checkScope(draft(b), name)
        return receipt(b, user)
    }

    open fun impact(id: String, name: String, ids: Set<Long>): ProductBatchPreview {
        val receipt = show(id, name)
        return previews.preview(receipt.draft, name, ids, reviewer(actor(name)))
    }

    open fun decide(id: String, action: String, name: String, note: String? = null): BatchReceipt {
        val b = locked(id)
        val user = actor(name)
        val d = draft(b)
        checkScope(d, name)
        when (action) {
            "approve", "reject" -> {
                if (!reviewer(user)) throw denied()
                if (b.status != "PENDING") throw conflict()
                if (action == "approve") {
                    val owner = users.findById(b.ownerId).filter { it.enabled }.orElseThrow { denied() }
                    checkScope(d, owner.username)
                    if (!d.expiresAt().isAfter(Instant.now())) throw conflict()
                    approve(b, d, name, owner.username)
                } else b.status = "REJECTED"
            }
            "cancel" -> {
                if (b.ownerId != user.id && !user.hasRole(User.Role.ADMIN)) throw denied()
                if (b.status == "APPROVED" && !b.autoApprove && !user.hasRole(User.Role.ADMIN)) throw denied()
                if (b.status !in setOf("PENDING", "APPROVED")) throw conflict()
                em.createQuery("DELETE FROM VulnerabilityException e WHERE e.batchId = :id").setParameter("id", id).executeUpdate()
                b.status = "CANCELLED"
                refresh(d)
            }
            else -> throw HttpStatusException(HttpStatus.BAD_REQUEST, "Unknown review action")
        }
        if (action == "reject" && (note.isNullOrBlank() || note.length > 2048)) throw HttpStatusException(HttpStatus.BAD_REQUEST, "Supply a rejection reason of up to 2048 characters")
        b.history.add(BatchDecision(name, b.status, Instant.now(), if (action == "reject") note?.trim() else null))
        b.decidedAt = Instant.now()
        b.decidedBy = name
        publish(b, d)
        return receipt(b, user)
    }

    private fun locked(id: String) = em.find(ProductExceptionBatch::class.java, id, LockModeType.PESSIMISTIC_WRITE) ?: throw denied()

    private fun approve(batch: ProductExceptionBatch, d: ProductExceptionBatchDraft, name: String, owner: String) {
        val expiration = LocalDateTime.ofInstant(d.expiresAt(), ZoneOffset.UTC)
        for (account in d.accounts) {
            for ((mode, selectors) in mapOf("EXACT" to d.products.exact, "GLOB" to d.products.glob)) {
                for (selector in selectors) em.persist(VulnerabilityException(
                    subject = VulnerabilityException.Subject.PRODUCT, scope = VulnerabilityException.Scope.AWS_ACCOUNT,
                    scopeValue = account, subjectValue = selector, productMode = mode, batchId = batch.id,
                    reason = d.reason, expirationDate = expiration, createdBy = owner
                ))
            }
        }
        batch.status = "APPROVED"
        batch.decidedAt = Instant.now()
        batch.decidedBy = name
        em.flush()
        refresh(d)
    }

    private fun publish(b: ProductExceptionBatch, d: ProductExceptionBatchDraft) {
        val recipients = if (b.status == "PENDING") {
            users.findByRolesContainingAny(listOf(User.Role.ADMIN, User.Role.SECCHAMPION))
        } else users.findById(b.ownerId).stream().toList()
        events.publishEvent(BatchDecisionEvent(b.id, recipients.filter { it.enabled }.map { it.email }.distinct(), b.status, d.through))
    }

    open fun pending(name: String, page: Int): List<BatchReceipt> {
        val user = actor(name)
        if (!reviewer(user)) throw denied()
        if (page !in 0..100000) throw HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid page")
        val accounts = previews.availableAccounts(name)
        if (accounts.isEmpty()) return emptyList()
        return em.createQuery("""SELECT b FROM ProductExceptionBatch b WHERE b.status = 'PENDING'
            AND NOT EXISTS (SELECT 1 FROM ProductExceptionBatch other JOIN other.accounts a
                            WHERE other.id = b.id AND a NOT IN :accounts) ORDER BY b.created, b.id""", ProductExceptionBatch::class.java)
            .setParameter("accounts", accounts).setFirstResult(page * 25).setMaxResults(25).resultList.map { receipt(it, user) }
    }

    private fun refresh(d: ProductExceptionBatchDraft) {
        d.accounts.forEach { materialization.onExceptionCreated(VulnerabilityException.Scope.AWS_ACCOUNT, it, null) }
    }
}
