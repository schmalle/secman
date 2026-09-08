package com.secman.controller

import com.secman.domain.DemandClassificationRule
import com.secman.repository.DemandClassificationResultRepository
import com.secman.repository.DemandClassificationRuleRepository
import com.secman.repository.DemandRepository
import com.secman.repository.UserRepository
import com.secman.service.DemandClassificationService
import io.micronaut.http.HttpStatus
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Regression coverage for the A08 finding: `/api/classification/rules/import` read an
 * uploaded file's bytes straight into a String and handed it to Jackson with no size,
 * extension or empty-file check first — the one upload endpoint in the codebase without
 * the validation every sibling importer has (see `ImportController.validateFile`).
 */
class DemandClassificationControllerUploadValidationTest {

    private val classificationService: DemandClassificationService = mockk()
    private val ruleRepository: DemandClassificationRuleRepository = mockk()
    private val resultRepository: DemandClassificationResultRepository = mockk()
    private val demandRepository: DemandRepository = mockk()
    private val userRepository: UserRepository = mockk()

    private val controller = DemandClassificationController(
        classificationService, ruleRepository, resultRepository, demandRepository, userRepository
    )

    private val auth: Authentication = mockk {
        every { name } returns "admin"
    }

    private fun upload(size: Long, filename: String): CompletedFileUpload = mockk {
        every { this@mockk.size } returns size
        every { this@mockk.filename } returns filename
    }

    @Test
    fun `oversized file is rejected before its bytes are read`() {
        val file = upload(size = DemandClassificationController.MAX_FILE_SIZE + 1, filename = "rules.json")

        val response = controller.importRules(file, auth)

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, response.status)
        verify(exactly = 0) { file.bytes }
    }

    @Test
    fun `empty file is rejected`() {
        val file = upload(size = 0L, filename = "rules.json")

        val response = controller.importRules(file, auth)

        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        verify(exactly = 0) { file.bytes }
    }

    @Test
    fun `non-json extension is rejected`() {
        val file = upload(size = 42L, filename = "rules.exe")

        val response = controller.importRules(file, auth)

        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        verify(exactly = 0) { file.bytes }
    }

    @Test
    fun `well-formed json upload within limits is imported`() {
        val json = """[{"name":"r1","condition":{"type":"KEYWORD_MATCH","field":"title","values":["x"]},"classification":"A"}]"""
        val file: CompletedFileUpload = mockk {
            every { size } returns json.length.toLong()
            every { filename } returns "rules.json"
            every { bytes } returns json.toByteArray()
        }
        every { userRepository.findByUsername("admin") } returns Optional.empty()
        every { classificationService.importRulesFromFile(json, null) } returns listOf(
            DemandClassificationRule(name = "r1", ruleJson = json)
        )

        val response = controller.importRules(file, auth)

        assertEquals(HttpStatus.OK, response.status)
    }
}
