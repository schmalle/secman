package com.secman.controller

import com.secman.dto.ProductExceptionBatchDraft
import com.secman.service.*
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule

@Controller("/api/vulnerability-exception-requests/batches")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(SecurityRule.IS_AUTHENTICATED)
class ProductExceptionBatchController(private val batches: ProductExceptionBatchService, private val assets: AssetFilterService, private val previews: ProductBatchPreviewService) {
    @io.micronaut.serde.annotation.Serdeable
    data class LabelSearch(val accounts: List<String>, val search: String)

    @Post("/products")
    fun products(@Body input: LabelSearch, auth: Authentication) =
        previews.suggest(input.accounts, input.search, auth.name, assets.getAccessibleAssetIds(auth))

    @Get("/accounts")
    fun accounts(auth: Authentication) = previews.accountNames(auth.name)

    @Get("/{id}/impact")
    fun impact(id: String, auth: Authentication) = batches.impact(id, auth.name, assets.getAccessibleAssetIds(auth))

    @Post("/preview")
    fun preview(@Body draft: ProductExceptionBatchDraft, auth: Authentication) =
        batches.preview(draft, auth.name, assets.getAccessibleAssetIds(auth))

    @Post
    fun submit(@Body submission: BatchSubmission, auth: Authentication) = batches.submit(submission, auth.name)

    @Get("/pending{?page}")
    @Secured("ADMIN", "SECCHAMPION")
    fun pending(@QueryValue(defaultValue = "0") page: Int, auth: Authentication) = batches.pending(auth.name, page)

    @Get("/{id}")
    fun show(id: String, auth: Authentication) = batches.show(id, auth.name)

    @io.micronaut.serde.annotation.Serdeable
    data class Review(val note: String? = null)

    @Post("/{id}/{action}")
    fun decide(id: String, action: String, @Body review: Review, auth: Authentication) = batches.decide(id, action, auth.name, review.note)
}
