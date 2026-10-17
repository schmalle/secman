package com.secman.cli.service

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UserMappingWelcomeTestSampleTest {
    private val service = UserMappingCliService(UserMappingValidator(), CliJavaHttpClientFactory(), mockk())

    @Test fun `sample selects normalized first owner and only that owners distinct sorted AWS accounts`() {
        val parsed = UserMappingCliService.ParseResult(listOf(
            mapOf("email" to "z@example.test", "awsAccountId" to "333333333333"),
            mapOf("email" to " A@example.test ", "awsAccountId" to "222222222222"),
            mapOf("email" to "a@example.test", "awsAccountId" to "111111111111"),
            mapOf("email" to "a@example.test", "awsAccountId" to "111111111111"),
            mapOf("email" to "a@example.test", "domain" to "example.test")), emptyList())
        val sample = service.selectWelcomeTestSample(parsed)!!
        assertEquals("a@example.test", sample.owner)
        assertEquals(listOf("111111111111", "222222222222"), sample.accountIds)
        assertEquals(sample, service.selectWelcomeTestSample(parsed.copy(entries = parsed.entries.reversed())))
    }

    @Test fun `empty domain only and invalid input never select a sample`() {
        assertNull(service.selectWelcomeTestSample(UserMappingCliService.ParseResult(emptyList(), emptyList())))
        assertNull(service.selectWelcomeTestSample(UserMappingCliService.ParseResult(
            listOf(mapOf("email" to "a@example.test", "domain" to "example.test")), emptyList())))
        assertThrows(IllegalArgumentException::class.java) {
            service.selectWelcomeTestSample(UserMappingCliService.ParseResult(emptyList(), listOf("invalid record")))
        }
    }
}
