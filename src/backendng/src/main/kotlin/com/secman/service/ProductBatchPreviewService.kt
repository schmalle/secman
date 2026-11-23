package com.secman.service

import com.secman.domain.ProductSelector
import com.secman.dto.ProductExceptionBatchDraft
import io.micronaut.core.type.Argument
import io.micronaut.json.JsonMapper
import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.Clock
import java.time.Instant

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ProductImpact(val mode: String, val selector: String, var findings: Long = 0, val examples: MutableList<String> = mutableListOf())

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class AccountImpact(val account: String, var findings: Long = 0, var assets: Int = 0, var additional: Long = 0, var unresolved: Long = 0)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ProductBatchPreview(
    val version: Int, val draft: ProductExceptionBatchDraft, val observedAt: Instant,
    val accounts: List<AccountImpact>, val products: List<ProductImpact>, val expectedStatus: String,
    val combinations: Int = accounts.size * products.size,
    val findings: Long = accounts.sumOf { it.findings },
    val additional: Long = accounts.sumOf { it.additional },
    val unresolved: Long = accounts.sumOf { it.unresolved },
    val visibility: String = "Current assets visible to the caller"
)

@Singleton
open class ProductBatchPreviewService(
    private val entityManager: EntityManager,
    private val mapper: JsonMapper,
    private val exceptions: VulnerabilityExceptionService
) {
    fun availableAccounts(username: String) = exceptions.getAccessibleAwsAccounts(username)

    @Transactional
    open fun suggest(accounts: List<String>, search: String, username: String, assetIds: Set<Long>): List<String> {
        if (accounts.size !in 1..50 || search.trim().length !in 2..512) {
            throw io.micronaut.http.exceptions.HttpStatusException(io.micronaut.http.HttpStatus.BAD_REQUEST, "Select accounts and enter 2–512 characters")
        }
        authorize(accounts, username)
        val labels = sortedSetOf<String>()
        for (ids in assetIds.chunked(500)) {
            val query = entityManager.createNativeQuery(com.secman.repository.VulnQuerySql.BATCH_LABEL_SUGGESTIONS)
                .setParameter("ids", ids).setParameter("accounts", accounts).setParameter("search", search.trim())
            query.resultList.forEach { labels.add(it as String) }
            while (labels.size > 25) labels.remove(labels.last())
        }
        return labels.toList()
    }

    @Transactional
    open fun accountNames(username: String): Map<String, String> {
        val allowed = availableAccounts(username)
        val names = allowed.associateWith { "" }.toMutableMap()
        for (ids in allowed.chunked(500)) {
            val rows = entityManager.createQuery("""SELECT m.awsAccountId, m.awsAccountName, MAX(m.updatedAt)
                FROM UserMapping m WHERE m.awsAccountId IN :ids AND m.awsAccountName IS NOT NULL
                GROUP BY m.awsAccountId, m.awsAccountName ORDER BY MAX(m.updatedAt), m.awsAccountName""", Array<Any>::class.java)
                .setParameter("ids", ids).resultList
            for (row in rows) names[row[0] as String] = row[1] as String
        }
        return names
    }

    fun authorize(accounts: List<String>, username: String) {
        if (!availableAccounts(username).containsAll(accounts)) {
            throw io.micronaut.http.exceptions.HttpStatusException(io.micronaut.http.HttpStatus.FORBIDDEN, "One or more AWS accounts are unavailable")
        }
    }

    @Transactional
    open fun preview(input: ProductExceptionBatchDraft, username: String, visibleAssets: Set<Long>, autoApprove: Boolean): ProductBatchPreview {
        val draft = try {
            input.validated(Clock.systemUTC())
        } catch (invalid: IllegalArgumentException) {
            throw io.micronaut.http.exceptions.HttpStatusException(io.micronaut.http.HttpStatus.BAD_REQUEST, invalid.message ?: "Invalid draft")
        }
        authorize(draft.accounts, username)
        val selectors = draft.products.exact.map { ProductSelector(it, ProductSelector.Mode.EXACT) } +
            draft.products.glob.map { ProductSelector(it, ProductSelector.Mode.GLOB) }
        val impacts = draft.products.exact.map { ProductImpact("EXACT", it) } + draft.products.glob.map { ProductImpact("GLOB", it) }
        val accounts = draft.accounts.associateWith(::AccountImpact)
        val observedAt = Instant.now()
        val matchedAssets = draft.accounts.associateWith { mutableSetOf<Long>() }
        val started = System.nanoTime()
        for (ids in visibleAssets.chunked(500)) {
            var after = 0L
            do {
                if (System.nanoTime() - started > java.time.Duration.ofSeconds(30).toNanos()) {
                    throw io.micronaut.http.exceptions.HttpStatusException(io.micronaut.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "Preview exceeded its time budget. Use fewer accounts or narrower selectors.")
                }
                val rows = entityManager.createNativeQuery(
                    com.secman.repository.VulnQuerySql.BATCH_PREVIEW
                ).setParameter("ids", ids).setParameter("accounts", draft.accounts)
                    .setParameter("after", after).setHint("jakarta.persistence.query.timeout", 5000).setMaxResults(500).resultList
                for (record in rows) {
                    val row = record as Array<*>
                    after = (row[0] as Number).toLong()
                    val account = accounts.getValue(row[2] as String)
                    val json = row[3] as String?
                    val labels = json?.let { mapper.readValue(it, Argument.listOf(String::class.java)) }.orEmpty()
                    if (labels.isEmpty()) account.unresolved++
                    var matched = false
                    for ((index, selector) in selectors.withIndex()) {
                        val label = labels.firstOrNull(selector::matches) ?: continue
                        val impact = impacts[index]
                        impact.findings++
                        if (impact.examples.size < 5 && label !in impact.examples) impact.examples.add(label)
                        matched = true
                    }
                    if (matched) {
                        account.findings++
                        matchedAssets.getValue(account.account).add((row[1] as Number).toLong())
                        if (row[4] != true && (row[4] as? Number)?.toInt() != 1) account.additional++
                    }
                }
            } while (rows.size == 500)
        }
        accounts.forEach { (id, impact) -> impact.assets = matchedAssets.getValue(id).size }
        return ProductBatchPreview(1, draft, observedAt, accounts.values.toList(), impacts, if (autoApprove) "APPROVED" else "PENDING")
    }
}
