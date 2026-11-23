package com.secman.integration

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.Vulnerability
import com.secman.dto.ProductExceptionBatchDraft
import com.secman.service.BatchSubmission
import com.secman.service.ProductExceptionBatchService
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class ProductExceptionBatchIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var em: EntityManager
    @Inject lateinit var service: ProductExceptionBatchService
    @Inject lateinit var previews: com.secman.service.ProductBatchPreviewService

    @Test
    fun oneUnmappedAccountRejectsTheEntireDraft() {
        val user = User(username = "limited-batch-owner", email = "limited-batch@example.invalid", passwordHash = UUID.randomUUID().toString())
        em.persist(user)
        em.persist(com.secman.domain.UserMapping(user = user, email = user.email, awsAccountId = "000000000001", domain = null))
        em.flush()
        val draft = ProductExceptionBatchDraft(1, listOf("000000000001", "000000000002"),
            ProductExceptionBatchDraft.Products(listOf("tomcat"), emptyList()), LocalDate.now().plusMonths(1),
            "Scoped product exception request with one account outside the requester's grant.")
        val failure = assertThrows(io.micronaut.http.exceptions.HttpStatusException::class.java) {
            service.preview(draft, user.username, emptySet())
        }
        assertEquals(403, failure.status.code)
    }

    @Test
    fun mappedRequesterGoesToReviewAndOneDecisionCoversEveryCombination() {
        val requester = User(username = "batch-owner", email = "batch-owner@example.invalid", passwordHash = UUID.randomUUID().toString())
        val reviewer = User(username = "batch-champion", email = "batch-champion@example.invalid", passwordHash = UUID.randomUUID().toString(),
            roles = mutableSetOf(User.Role.SECCHAMPION))
        em.persist(requester)
        em.persist(reviewer)
        val accounts = (1..5).map { it.toString().padStart(12, '0') }
        for (account in accounts) {
            for (user in listOf(requester, reviewer)) em.persist(com.secman.domain.UserMapping(
                email = user.email, user = user, awsAccountId = account, domain = null
            ))
        }
        em.flush()
        val draft = ProductExceptionBatchDraft(1, accounts,
            ProductExceptionBatchDraft.Products((1..22).map { "Product $it" }, emptyList()), LocalDate.now().plusMonths(2),
            "The account owner requests time to complete deployment across these five accounts.")
        val preview = service.preview(draft, requester.username, emptySet())
        val receipt = service.submit(BatchSubmission(preview.previewId, UUID.randomUUID()), requester.username)
        assertEquals("PENDING", receipt.status)
        assertTrue(service.pending(reviewer.username, 0).any { it.batchId == receipt.batchId })
        val approved = service.decide(receipt.batchId, "approve", reviewer.username)
        assertEquals(listOf("PENDING", "APPROVED"), approved.history.map { it.outcome })
        val count = em.createQuery("SELECT COUNT(e) FROM VulnerabilityException e WHERE e.batchId = :id", Long::class.javaObjectType)
            .setParameter("id", receipt.batchId).singleResult
        assertEquals(110L, count)
    }

    @Test
    fun previewSubmitReplayAndCancel() {
        val user = User(username = "batch-reviewer", email = "batch@example.invalid",
            passwordHash = UUID.randomUUID().toString(), roles = mutableSetOf(User.Role.ADMIN))
        val asset = Asset(name = "batch-server", type = "SERVER", owner = user.username,
            cloudAccountId = "047116934344", verifiedObservedAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(3))
        em.persist(user)
        em.persist(asset)
        val finding = Vulnerability(asset = asset, scanTimestamp = LocalDateTime.now(), productLabelsJson = """["Tomcat 10.1"]""")
        em.persist(finding)
        em.persist(Vulnerability(asset = asset, scanTimestamp = LocalDateTime.now(), vulnerableProductVersions = "Tomcat 10.1"))
        em.flush()
        val draft = ProductExceptionBatchDraft(1, listOf(asset.cloudAccountId!!),
            ProductExceptionBatchDraft.Products(listOf("tomcat 10.1"), listOf("tomcat*")), LocalDate.now().plusMonths(2),
            "Business justification for the scoped test account and product exceptions.")
        assertEquals(listOf("Tomcat 10.1"), previews.suggest(draft.accounts, "tom", user.username, setOf(asset.id!!)))
        val preview = service.preview(draft, user.username, setOf(asset.id!!))
        val impact = preview.impact.accounts.single()
        assertEquals(1L, impact.findings)
        assertEquals(1L, impact.unresolved)
        assertEquals(listOf(1L, 1L), preview.impact.products.map { it.findings })
        val command = BatchSubmission(preview.previewId, UUID.randomUUID())
        val receipt = service.submit(command, user.username)
        assertEquals("APPROVED", receipt.status)
        assertEquals(receipt, service.submit(command, user.username))
        assertTrue(service.pending(user.username, 0).isEmpty())
        em.clear()
        assertTrue(em.find(Vulnerability::class.java, finding.id).excepted)
        val visible = "SELECT COUNT(*) FROM vulnerability v WHERE v.id = :id AND " + com.secman.repository.VulnQuerySql.NOT_EXCEPTED
        em.createNativeQuery("UPDATE vulnerability SET excepted_until = UTC_TIMESTAMP() - INTERVAL 1 SECOND WHERE id = :id")
            .setParameter("id", finding.id).executeUpdate()
        assertEquals(1L, (em.createNativeQuery(visible).setParameter("id", finding.id).singleResult as Number).toLong())
        assertEquals("CANCELLED", service.decide(receipt.batchId, "cancel", user.username).status)
        em.clear()
        assertFalse(em.find(Vulnerability::class.java, finding.id).excepted)
    }
}
