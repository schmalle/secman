package com.secman.controller

import com.secman.domain.Classification
import com.secman.domain.Demand
import com.secman.domain.DemandClassificationResult
import com.secman.domain.DemandType
import com.secman.repository.DemandClassificationResultRepository
import com.secman.repository.DemandClassificationRuleRepository
import com.secman.repository.DemandRepository
import com.secman.repository.UserRepository
import com.secman.service.DemandClassificationService
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

/**
 * Authorization coverage for [DemandClassificationController.getResultByHash].
 *
 * `classificationHash` (SHA-256 of `demandId-classification-timestamp`) exists to make
 * classification results independently addressable, not as a secret capability token — its
 * inputs (a small sequential demand id, one of 3 classification values) are far too
 * low-entropy to rely on for access control. A result with `demand == null` came from the
 * genuinely public `/public/classify` endpoint and anonymous lookup is fine. A result with a
 * non-null `demand` came from the ADMIN/SECCHAMPION-only `/classify-demand` endpoint and
 * carries a real internal demand's title/description/businessJustification — anonymous callers
 * must not be able to read that merely by holding (or brute-forcing/leaking) the hash.
 */
class DemandClassificationControllerAuthorizationTest {

    private val classificationService: DemandClassificationService = mockk()
    private val ruleRepository: DemandClassificationRuleRepository = mockk()
    private val resultRepository: DemandClassificationResultRepository = mockk()
    private val demandRepository: DemandRepository = mockk()
    private val userRepository: UserRepository = mockk()

    private val controller = DemandClassificationController(
        classificationService, ruleRepository, resultRepository, demandRepository, userRepository
    )

    private fun auth(roles: Set<String>): Authentication = mockk {
        every { name } returns "someuser"
        every { this@mockk.roles } returns roles
    }

    private fun publicResult() = DemandClassificationResult(
        demand = null,
        classification = Classification.B,
        confidenceScore = 0.9,
        ruleEvaluationLog = "",
        classificationHash = "publichash",
        inputData = "{}",
        classifiedAt = LocalDateTime.now()
    )

    private fun demandLinkedResult(): DemandClassificationResult {
        val demand = Demand(
            id = 7L,
            title = "Confidential internal demand",
            description = "sensitive business justification",
            demandType = DemandType.CREATE_NEW,
            requestor = mockk(relaxed = true)
        )
        return DemandClassificationResult(
            demand = demand,
            classification = Classification.A,
            confidenceScore = 0.95,
            ruleEvaluationLog = "",
            classificationHash = "demandhash",
            inputData = "{\"title\":\"Confidential internal demand\"}",
            classifiedAt = LocalDateTime.now()
        )
    }

    @Test
    fun `anonymous caller can fetch a public classification result by hash`() {
        every { resultRepository.findByClassificationHash("publichash") } returns Optional.of(publicResult())

        val response = controller.getResultByHash("publichash", authentication = null)

        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `anonymous caller cannot fetch a demand-linked classification result by hash`() {
        every { resultRepository.findByClassificationHash("demandhash") } returns Optional.of(demandLinkedResult())

        val response = controller.getResultByHash("demandhash", authentication = null)

        assertEquals(HttpStatus.NOT_FOUND, response.status)
    }

    @Test
    fun `plain USER cannot fetch a demand-linked classification result by hash`() {
        every { resultRepository.findByClassificationHash("demandhash") } returns Optional.of(demandLinkedResult())

        val response = controller.getResultByHash("demandhash", auth(setOf("USER")))

        assertEquals(HttpStatus.NOT_FOUND, response.status)
    }

    @Test
    fun `ADMIN can fetch a demand-linked classification result by hash`() {
        every { resultRepository.findByClassificationHash("demandhash") } returns Optional.of(demandLinkedResult())

        val response = controller.getResultByHash("demandhash", auth(setOf("ADMIN")))

        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `SECCHAMPION can fetch a demand-linked classification result by hash`() {
        every { resultRepository.findByClassificationHash("demandhash") } returns Optional.of(demandLinkedResult())

        val response = controller.getResultByHash("demandhash", auth(setOf("SECCHAMPION")))

        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `unknown hash is 404 regardless of authentication`() {
        every { resultRepository.findByClassificationHash("missing") } returns Optional.empty()

        val response = controller.getResultByHash("missing", authentication = null)

        assertEquals(HttpStatus.NOT_FOUND, response.status)
    }
}
