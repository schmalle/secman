package com.secman.dto

import com.secman.domain.ProductSelector
import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.serde.annotation.Serdeable
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ProductExceptionBatchDraft(
    val version: Int,
    val accounts: List<String>,
    val products: Products,
    val through: LocalDate,
    val reason: String
) {
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    data class Products(val exact: List<String>, val glob: List<String>)

    fun validated(clock: Clock): ProductExceptionBatchDraft {
        require(version == 1) { "Unsupported manifest version" }
        require(accounts.size in 1..50 && accounts.all { accountId.matches(it) }) {
            "Supply 1–50 twelve-digit AWS account IDs"
        }
        require(products.exact.size + products.glob.size in 1..100) { "Supply 1–100 product selectors" }
        require(reason.trim().length in 50..2048) { "Reason must contain 50–2048 characters" }
        require(through.year in 1000..9998) { "Choose a supported through date" }
        require(expiresAt() > clock.instant()) { "Expiry must be in the future" }

        val ids = accounts.distinct().sorted()
        val selectors = Products(
            canonical(products.exact, ProductSelector.Mode.EXACT),
            canonical(products.glob, ProductSelector.Mode.GLOB)
        )
        require(ids.size * (selectors.exact.size + selectors.glob.size) <= 1000) {
            "At most 1,000 account and product combinations are allowed"
        }
        return copy(accounts = ids, products = selectors, reason = reason.trim())
    }

    fun expiresAt(): Instant = through.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    private fun canonical(values: List<String>, mode: ProductSelector.Mode): List<String> =
        values.map { ProductSelector(it, mode).pattern }.distinct().sorted()

    private companion object {
        val accountId = Regex("[0-9]{12}")
    }
}
