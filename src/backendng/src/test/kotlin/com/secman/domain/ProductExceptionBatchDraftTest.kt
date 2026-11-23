package com.secman.domain

import com.secman.dto.ProductExceptionBatchDraft
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ProductExceptionBatchDraftTest {
    private val clock = Clock.fixed(Instant.parse("2027-03-29T23:59:59Z"), ZoneOffset.UTC)
    private val draft = ProductExceptionBatchDraft(
        1, listOf("047116934344"), ProductExceptionBatchDraft.Products(listOf("Tomcat", "tomcat"), emptyList()),
        LocalDate.parse("2027-03-29"), "Business justification supplied by the requesting account owner."
    )

    @Test
    fun normalizesWithoutChangingAccountIdentifiers() {
        val validated = draft.validated(clock)
        assertEquals(listOf("047116934344"), validated.accounts)
        assertEquals(listOf("tomcat"), validated.products.exact)
        assertEquals(Instant.parse("2027-03-30T00:00:00Z"), validated.expiresAt())
    }

    @Test
    fun rejectsExpiredAndInvalidScopes() {
        assertThrows(IllegalArgumentException::class.java) { draft.validated(Clock.fixed(draft.expiresAt(), ZoneOffset.UTC)) }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(accounts = listOf("*")).validated(clock) }
    }
}
