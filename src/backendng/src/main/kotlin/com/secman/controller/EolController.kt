package com.secman.controller

import com.secman.dto.EolNotificationRequest
import com.secman.dto.EolSyncRequest
import com.secman.service.EolAdminService
import com.secman.service.EolNotificationService
import com.secman.service.EolQueryService
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Produces
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import org.slf4j.LoggerFactory

/**
 * End-of-life (EOL) lifecycle API.
 *
 * Access model, deliberately narrow by default (§A01/§A04):
 *  - read endpoints require authentication and are **asset-scoped** inside
 *    [EolQueryService] via `AssetFilterService`; a user only ever sees EOL
 *    components on systems they can already see;
 *  - the repository ranking is ADMIN / SECCHAMPION only, mirroring
 *    `GithubRepositoryController` — repository names are not asset-scoped data
 *    and must not fall out of a user-scoped endpoint;
 *  - catalogue sync, matching scan and owner notifications are ADMIN only, and
 *    each logs actor + outcome.
 */
@Controller("/api/eol")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class EolController(
    private val eolQueryService: EolQueryService,
    private val eolAdminService: EolAdminService,
    private val eolNotificationService: EolNotificationService
) {
    private val logger = LoggerFactory.getLogger(EolController::class.java)

    /** EOL / approaching-EOL components on the caller's accessible systems. */
    @Get("/findings")
    @Produces(MediaType.APPLICATION_JSON)
    open fun listFindings(
        authentication: Authentication,
        @Nullable @QueryValue status: String?,
        @Nullable @QueryValue search: String?,
        @Nullable @QueryValue cloudAccountId: String?,
        @Nullable @QueryValue page: Int?,
        @Nullable @QueryValue pageSize: Int?,
        /**
         * Opt back in to installer/setup payload findings ("Chrome Installer", "Photon Setup").
         * Default false. Widens visibility of rows the caller is already authorized to see;
         * the asset scope still comes from AssetFilterService via accessibleAssetIdsCache.
         */
        @QueryValue(defaultValue = "false") includeInstallerFindings: Boolean
    ): HttpResponse<*> = try {
        HttpResponse.ok(
            eolQueryService.listFindings(
                authentication, status, search, cloudAccountId, page, pageSize, includeInstallerFindings
            )
        )
    } catch (e: IllegalArgumentException) {
        HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
    }

    /** Counts, per-account rollup and top components, all within the caller's scope. */
    @Get("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    open fun summary(authentication: Authentication): HttpResponse<*> =
        HttpResponse.ok(eolQueryService.summary(authentication))

    /**
     * EOL components on one system. An asset outside the caller's scope answers
     * 404, identical to a nonexistent id — an out-of-scope id must not be
     * distinguishable from a missing one.
     */
    @Get("/assets/{assetId}")
    @Produces(MediaType.APPLICATION_JSON)
    open fun findingsForAsset(
        authentication: Authentication,
        @PathVariable assetId: Long
    ): HttpResponse<*> {
        val findings = eolQueryService.findingsForAsset(authentication, assetId)
            ?: return HttpResponse.notFound(mapOf("error" to "Asset not found"))
        return HttpResponse.ok(mapOf("findings" to findings))
    }

    /**
     * Systems affected by one EOL product, within the caller's accessible scope.
     * Backs the drilldown from the "Top 10 Most Often EOL Products" table on the
     * vulnerability statistics page — `product` is matched against the same
     * `componentName` field that table groups by.
     */
    @Get("/products/{product}/assets")
    @Produces(MediaType.APPLICATION_JSON)
    open fun findingsForProduct(
        authentication: Authentication,
        @PathVariable product: String,
        @Nullable @QueryValue page: Int?,
        @Nullable @QueryValue pageSize: Int?
    ): HttpResponse<*> =
        HttpResponse.ok(eolQueryService.findingsForProduct(authentication, product, page, pageSize))

    /** Catalogue size and last sync outcome. Contains no per-tenant data. */
    @Get("/catalog/status")
    @Produces(MediaType.APPLICATION_JSON)
    open fun catalogStatus(): HttpResponse<*> = HttpResponse.ok(eolQueryService.catalogStatus())

    /** Top repositories by number of distinct EOL components. */
    @Get("/repositories/top")
    @Secured("ADMIN", "SECCHAMPION")
    @Produces(MediaType.APPLICATION_JSON)
    open fun topRepositories(@Nullable @QueryValue limit: Int?): HttpResponse<*> =
        HttpResponse.ok(eolQueryService.topRepositories(limit))

    // ------------------------------------------------------------------- admin

    /**
     * Start an EOL catalogue download plus matching scan. Backs CLI
     * `secman eol-sync`.
     *
     * Returns **202 Accepted** with the run's handle as soon as the run is
     * recorded; the work continues on a background thread. A full run takes
     * minutes, which is longer than the 60s read timeout both Apache and nginx
     * apply by default, so answering synchronously produced a 504 at the proxy
     * while the work completed unseen. Poll [syncStatus] for the outcome.
     */
    @Post("/catalog/sync")
    @Secured("ADMIN")
    @Produces(MediaType.APPLICATION_JSON)
    open fun sync(
        @Body request: EolSyncRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info(
            "EOL catalogue sync requested by {} (products={}, scan={}, scanOnly={})",
            authentication.name, request.products.size, request.scan, request.scanOnly
        )
        if (request.products.size > MAX_EXPLICIT_PRODUCTS) {
            return HttpResponse.badRequest(
                mapOf("error" to "products must contain at most $MAX_EXPLICIT_PRODUCTS entries")
            )
        }
        val horizon = request.horizonMonths
        if (horizon != null && horizon !in 1L..120L) {
            return HttpResponse.badRequest(mapOf("error" to "horizonMonths must be between 1 and 120"))
        }
        return try {
            HttpResponse.accepted<Any>().body(eolAdminService.startSync(request, authentication.name))
        } catch (e: Exception) {
            // Generic message to the client, detail to the server log (§A05).
            logger.error("EOL catalogue sync failed", e)
            HttpResponse.serverError(mapOf("error" to "EOL catalogue sync failed"))
        }
    }

    /**
     * Current state of one sync run, for polling after [sync] returns 202.
     *
     * ADMIN only, matching the verb that creates the run: the response carries
     * estate-wide counts, which are not user-scoped data. The triggering actor
     * is deliberately *not* in the response — it stays in the audit row and the
     * log. Every EOL sync run is visible to every ADMIN by design: this is an
     * admin audit record, not an owner-scoped entity, so there is no per-row
     * ownership check to apply here.
     *
     * An unknown *and* a malformed handle both return the same generic 404, so
     * the endpoint is not an existence oracle. Validating the shape first also
     * keeps an attacker-supplied path value out of the log entirely, rather
     * than relying on sanitizing it at each call site (§A09 log forging).
     */
    @Get("/catalog/sync/{runId}")
    @Secured("ADMIN")
    @Produces(MediaType.APPLICATION_JSON)
    open fun syncStatus(@PathVariable runId: String): HttpResponse<*> {
        if (!RUN_ID_PATTERN.matches(runId)) {
            return HttpResponse.notFound(mapOf("error" to "Unknown sync run"))
        }
        val run = eolAdminService.findRun(runId)
            ?: return HttpResponse.notFound(mapOf("error" to "Unknown sync run"))
        return HttpResponse.ok(run)
    }

    /**
     * Email account owners about components reaching EOL within `months`.
     * Backs CLI `secman send-eol-notifications`.
     */
    @Post("/notifications/send")
    @Secured("ADMIN")
    @Produces(MediaType.APPLICATION_JSON)
    open fun sendNotifications(
        @Body request: EolNotificationRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info(
            "EOL owner notification requested by {} (months={}, dryRun={}, includeAlreadyEol={})",
            authentication.name, request.months, request.dryRun, request.includeAlreadyEol
        )
        if (request.months !in 1L..EolNotificationService.MAX_MONTHS) {
            return HttpResponse.badRequest(
                mapOf("error" to "months must be between 1 and ${EolNotificationService.MAX_MONTHS}")
            )
        }
        return try {
            HttpResponse.ok(
                eolNotificationService.sendEolNotifications(
                    months = request.months,
                    dryRun = request.dryRun,
                    onlyEmail = request.onlyEmail,
                    includeAlreadyEol = request.includeAlreadyEol
                )
            )
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            logger.error("EOL owner notification run failed", e)
            HttpResponse.serverError(mapOf("error" to "EOL notification run failed"))
        }
    }

    companion object {
        private const val MAX_EXPLICIT_PRODUCTS = 200

        /**
         * Run handles are server-generated UUIDs. Anything else is rejected
         * before it reaches the database or a log line.
         */
        private val RUN_ID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
