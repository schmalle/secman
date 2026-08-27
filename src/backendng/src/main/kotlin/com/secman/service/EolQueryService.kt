package com.secman.service

import com.secman.domain.EolFinding
import com.secman.domain.EolProduct
import com.secman.domain.EolStatus
import com.secman.dto.EolAccountSummary
import com.secman.dto.EolCatalogStatusResponse
import com.secman.dto.EolComponentSummary
import com.secman.dto.EolFindingListResponse
import com.secman.dto.EolFindingResponse
import com.secman.dto.EolRepositoryRankResponse
import com.secman.dto.EolSummaryResponse
import com.secman.dto.EolTopRepositoriesResponse
import com.secman.repository.EolFindingRepository
import com.secman.repository.EolProductRepository
import com.secman.repository.EolReleaseRepository
import com.secman.repository.EolSyncRunRepository
import io.micronaut.context.annotation.Value
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

/**
 * Read side of the EOL feature.
 *
 * **Every asset-scoped read resolves the caller's accessible asset ids first and
 * passes them as a bound `IN` list.** There is no code path here that reads
 * findings for an asset id supplied by the client without that check — an id in
 * a request is untrusted input (CLAUDE.md §A01), and this table denormalizes
 * hostnames, owners and account ids that must not leak across tenants.
 *
 * Repository rankings are *not* asset-scoped; they are gated to ADMIN /
 * SECCHAMPION at the controller, mirroring `GithubRepositoryController`.
 */
@Singleton
open class EolQueryService(
    private val eolFindingRepository: EolFindingRepository,
    private val eolProductRepository: EolProductRepository,
    private val eolReleaseRepository: EolReleaseRepository,
    private val eolSyncRunRepository: EolSyncRunRepository,
    private val accessibleAssetIdsCache: AccessibleAssetIdsCache,

    @Value("\${secman.eol.horizon-months:12}")
    private val horizonMonths: Long
) {

    /** Status filter values the API accepts. Anything else is rejected, never passed through. */
    private fun resolveStatuses(status: String?): List<EolStatus> = when (status?.trim()?.uppercase()) {
        null, "", "ALL" -> listOf(EolStatus.EOL, EolStatus.APPROACHING_EOL)
        "EOL" -> listOf(EolStatus.EOL)
        "APPROACHING_EOL", "APPROACHING" -> listOf(EolStatus.APPROACHING_EOL)
        else -> throw IllegalArgumentException("status must be one of ALL, EOL, APPROACHING_EOL")
    }

    open fun listFindings(
        authentication: Authentication,
        status: String?,
        search: String?,
        cloudAccountId: String?,
        page: Int?,
        pageSize: Int?,
        includeInstallerFindings: Boolean = false
    ): EolFindingListResponse {
        val statuses = resolveStatuses(status)
        val effectivePage = (page ?: 0).coerceIn(0, MAX_PAGE)
        val effectiveSize = (pageSize ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val assetIds = accessibleAssetIdsCache.get(authentication)
        if (assetIds.isEmpty()) {
            return EolFindingListResponse(emptyList(), 0, effectivePage, effectiveSize)
        }

        val normalizedSearch = search?.trim()?.take(200)
        val normalizedAccount = cloudAccountId?.trim()?.take(64)

        val findings = eolFindingRepository.findForAssets(
            assetIds = assetIds,
            statuses = statuses,
            search = normalizedSearch,
            cloudAccountId = normalizedAccount,
            unassignedAccountToken = UNASSIGNED_ACCOUNT_LABEL,
            pageable = Pageable.from(effectivePage, effectiveSize),
            includeInstallerFindings = includeInstallerFindings
        )
        val total = eolFindingRepository.countForAssets(
            assetIds, statuses, normalizedSearch, normalizedAccount, UNASSIGNED_ACCOUNT_LABEL,
            includeInstallerFindings
        )
        return EolFindingListResponse(findings.map { it.toResponse() }, total, effectivePage, effectiveSize)
    }

    open fun summary(authentication: Authentication): EolSummaryResponse {
        val assetIds = accessibleAssetIdsCache.get(authentication)
        if (assetIds.isEmpty()) {
            return EolSummaryResponse(
                eolCount = 0,
                approachingCount = 0,
                affectedAssets = 0,
                horizonMonths = horizonMonths,
                accounts = emptyList(),
                topComponents = emptyList(),
                lastScanAt = lastRunAt()
            )
        }

        val byStatus = eolFindingRepository.countByStatusForAssets(assetIds)
            .mapNotNull { row ->
                val status = row.getOrNull(0) as? EolStatus ?: return@mapNotNull null
                status to ((row.getOrNull(1) as? Number)?.toLong() ?: 0L)
            }.toMap()

        val accounts = eolFindingRepository.countByAccountAndStatusForAssets(assetIds)
            .mapNotNull { row ->
                val account = row.getOrNull(0)?.toString().orEmpty()
                val status = row.getOrNull(1) as? EolStatus ?: return@mapNotNull null
                val count = (row.getOrNull(2) as? Number)?.toLong() ?: 0L
                Triple(account, status, count)
            }
            .groupBy { it.first }
            .map { (account, rows) ->
                EolAccountSummary(
                    cloudAccountId = account.ifEmpty { UNASSIGNED_ACCOUNT_LABEL },
                    eolCount = rows.filter { it.second == EolStatus.EOL }.sumOf { it.third },
                    approachingCount = rows.filter { it.second == EolStatus.APPROACHING_EOL }.sumOf { it.third }
                )
            }
            .sortedWith(compareByDescending<EolAccountSummary> { it.eolCount }.thenByDescending { it.approachingCount })
            .take(MAX_ACCOUNT_ROWS)

        val topComponents = eolFindingRepository
            .topComponentsForAssets(assetIds, Pageable.from(0, MAX_TOP_COMPONENTS))
            .mapNotNull { row ->
                val status = row.getOrNull(3) as? EolStatus ?: return@mapNotNull null
                EolComponentSummary(
                    componentName = row.getOrNull(0)?.toString().orEmpty(),
                    productKey = row.getOrNull(1)?.toString().orEmpty(),
                    cycle = row.getOrNull(2)?.toString().orEmpty(),
                    status = status,
                    affectedAssets = (row.getOrNull(4) as? Number)?.toLong() ?: 0L
                )
            }

        return EolSummaryResponse(
            eolCount = byStatus[EolStatus.EOL] ?: 0,
            approachingCount = byStatus[EolStatus.APPROACHING_EOL] ?: 0,
            affectedAssets = eolFindingRepository.countDistinctAssets(assetIds),
            horizonMonths = horizonMonths,
            accounts = accounts,
            topComponents = topComponents,
            lastScanAt = lastRunAt()
        )
    }

    /**
     * Findings for one asset. Returns null when the asset is outside the
     * caller's scope — the controller turns that into a 404, so an out-of-scope
     * id is indistinguishable from a nonexistent one.
     */
    open fun findingsForAsset(authentication: Authentication, assetId: Long): List<EolFindingResponse>? {
        val assetIds = accessibleAssetIdsCache.get(authentication)
        if (!assetIds.contains(assetId)) return null
        return eolFindingRepository
            .findByAssetId(assetId, Pageable.from(0, MAX_PAGE_SIZE))
            .map { it.toResponse() }
    }

    /**
     * Findings for one exact product name (e.g. "Internet Explorer"), scoped to
     * the caller's accessible assets — the drilldown behind the "Top 10 Most
     * Often EOL Products" table on the vulnerability statistics page, which
     * groups by this same `componentName` field.
     */
    open fun findingsForProduct(
        authentication: Authentication,
        product: String,
        page: Int?,
        pageSize: Int?
    ): EolFindingListResponse {
        val effectivePage = (page ?: 0).coerceIn(0, MAX_PAGE)
        val effectiveSize = (pageSize ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val assetIds = accessibleAssetIdsCache.get(authentication)
        if (assetIds.isEmpty()) {
            return EolFindingListResponse(emptyList(), 0, effectivePage, effectiveSize)
        }

        val normalizedProduct = product.trim().take(512)
        val findings = eolFindingRepository.findByComponentNameForAssets(
            normalizedProduct, assetIds, Pageable.from(effectivePage, effectiveSize)
        )
        val total = eolFindingRepository.countByComponentNameForAssets(normalizedProduct, assetIds)
        return EolFindingListResponse(findings.map { it.toResponse() }, total, effectivePage, effectiveSize)
    }

    /** ADMIN / SECCHAMPION only — the controller enforces it. */
    open fun topRepositories(limit: Int?): EolTopRepositoriesResponse {
        val effectiveLimit = (limit ?: DEFAULT_TOP_REPOSITORIES).coerceIn(1, MAX_TOP_REPOSITORIES)
        val rows = eolFindingRepository.topRepositoriesByEolComponents(Pageable.from(0, effectiveLimit))
        val repositories = rows.mapIndexedNotNull { position, row ->
            val repositoryId = (row.getOrNull(0) as? Number)?.toLong() ?: return@mapIndexedNotNull null
            EolRepositoryRankResponse(
                rank = position + 1,
                repositoryId = repositoryId,
                fullName = row.getOrNull(1)?.toString().orEmpty(),
                distinctEolComponents = (row.getOrNull(2) as? Number)?.toLong() ?: 0L,
                eolFindings = (row.getOrNull(3) as? Number)?.toLong() ?: 0L,
                approachingFindings = (row.getOrNull(4) as? Number)?.toLong() ?: 0L
            )
        }
        return EolTopRepositoriesResponse(repositories, effectiveLimit)
    }

    open fun catalogStatus(): EolCatalogStatusResponse {
        val lastRun = eolSyncRunRepository.findRecent(Pageable.from(0, 1)).firstOrNull()
        return EolCatalogStatusResponse(
            sourceKey = EolProduct.DEFAULT_SOURCE_KEY,
            products = eolProductRepository.countBySourceKey(EolProduct.DEFAULT_SOURCE_KEY),
            releases = eolReleaseRepository.countAll(),
            findings = eolFindingRepository.countAll(),
            lastSyncStatus = lastRun?.status,
            lastSyncAt = lastRun?.finishedAt ?: lastRun?.startedAt,
            lastSyncTriggeredBy = lastRun?.triggeredBy,
            lastSyncError = lastRun?.errorSummary
        )
    }

    private fun lastRunAt() = eolSyncRunRepository.findRecent(Pageable.from(0, 1))
        .firstOrNull()?.let { it.finishedAt ?: it.startedAt }

    private fun EolFinding.toResponse() = EolFindingResponse(
        id = id ?: 0,
        subjectType = subjectType,
        assetId = assetId,
        assetName = assetName,
        cloudAccountId = cloudAccountId,
        cloudInstanceId = cloudInstanceId,
        adDomain = adDomain,
        assetOwner = assetOwner,
        repositoryId = githubRepositoryId,
        repositoryFullName = repositoryFullName,
        componentName = componentName,
        componentVendor = componentVendor,
        componentVersion = componentVersion,
        ecosystem = ecosystem,
        productKey = eolProductKey,
        cycle = eolCycle,
        eolDate = eolDate,
        status = status,
        daysUntilEol = daysUntilEol,
        detectedAt = detectedAt,
        productClass = productClass
    )

    companion object {
        /**
         * Label the per-account rollup uses for findings with no cloud account, and
         * the value the findings filter accepts to mean "exactly those rows".
         *
         * One constant on purpose: the summary renders it as a clickable account and
         * the filter has to understand what came back, or clicking the largest bucket
         * silently returns every finding instead of the unassigned ones. Not a valid
         * AWS account id (those are 12 digits), so it cannot collide with real data.
         */
        const val UNASSIGNED_ACCOUNT_LABEL = "(no account)"

        private const val DEFAULT_PAGE_SIZE = 100
        private const val MAX_PAGE_SIZE = 500
        private const val MAX_PAGE = 10_000
        private const val MAX_ACCOUNT_ROWS = 50
        private const val MAX_TOP_COMPONENTS = 20
        private const val DEFAULT_TOP_REPOSITORIES = 10
        private const val MAX_TOP_REPOSITORIES = 50
    }
}
