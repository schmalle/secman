package com.secman.service

import com.secman.domain.UserMapping
import com.secman.dto.WorkgroupAccountLinkSummary
import com.secman.repository.UserMappingRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The `display_name` column already exists in real mapping exports (see
 * testdata/user-mappings/accounts-from-dynamodb.csv) and was silently ignored as an
 * extra column. These tests pin that it is now stored and linked — and that a file
 * without the column behaves exactly as it always did.
 */
class CSVUserMappingParserDisplayNameTest {

    private val repository = mockk<UserMappingRepository>(relaxed = true)
    private val linkService = mockk<WorkgroupAccountLinkService>(relaxed = true)
    private lateinit var parser: CSVUserMappingParser

    @BeforeEach
    fun setUp() {
        parser = CSVUserMappingParser(repository, linkService)
        every { repository.existsByEmailAndAwsAccountIdAndDomain(any(), any(), any()) } returns false
        every { linkService.link(any(), any(), any()) } returns WorkgroupAccountLinkSummary(processed = 1, linked = 1)
    }

    private fun csv(content: String): File {
        val file = Files.createTempFile("mapping-csv-", ".csv").toFile()
        file.deleteOnExit()
        file.writeText(content.trimIndent())
        return file
    }

    @Test
    fun `display_name is stored on the mapping and linked to a workgroup`() {
        val file = csv(
            """
            account_id,owner_email,display_name
            706840063453,owner@corp.com,Legacy-x
            """
        )

        val saved = slot<List<UserMapping>>()
        every { repository.saveAll(capture(saved)) } returns emptyList()

        val result = parser.parse(file, actorId = 7L)

        assertThat(result.imported).isEqualTo(1)
        assertThat(saved.captured.single().awsAccountName).isEqualTo("Legacy-x")

        val pairs = slot<List<WorkgroupAccountLinkService.AccountDisplayName>>()
        verify { linkService.link(capture(pairs), 7L, false) }
        assertThat(pairs.captured).containsExactly(
            WorkgroupAccountLinkService.AccountDisplayName("706840063453", "Legacy-x")
        )
        assertThat(result.workgroupLinks?.linked).isEqualTo(1)
    }

    @Test
    fun `a file without the column links nothing and reports nothing`() {
        val file = csv(
            """
            account_id,owner_email
            706840063453,owner@corp.com
            """
        )
        every { repository.saveAll(any<List<UserMapping>>()) } returns emptyList()

        val result = parser.parse(file, actorId = 7L)

        assertThat(result.imported).isEqualTo(1)
        // Not called at all — the byte-identical-behaviour guarantee for existing files.
        verify(exactly = 0) { linkService.link(any(), any(), any()) }
        assertThat(result.workgroupLinks).isNull()
    }

    @Test
    fun `a duplicate row still gets its display name written and still gets linked`() {
        val file = csv(
            """
            account_id,owner_email,display_name
            706840063453,owner@corp.com,Legacy-x
            """
        )
        every { repository.existsByEmailAndAwsAccountIdAndDomain(any(), any(), any()) } returns true

        val result = parser.parse(file, actorId = 7L)

        assertThat(result.skipped).isEqualTo(1)
        // One statement for the account, not a read-modify-write per mapping row.
        verify { repository.updateAwsAccountName("706840063453", "Legacy-x", any()) }
        // The mapping row was a duplicate, but the workgroup link may well be missing.
        verify { linkService.link(any(), 7L, false) }
    }

    @Test
    fun `the display name is written once per account, not once per owner`() {
        val file = csv(
            """
            account_id,owner_email,display_name
            706840063453,alice@corp.com,Legacy-x
            706840063453,bob@corp.com,Legacy-x
            706840063453,carol@corp.com,Legacy-x
            """
        )
        every { repository.saveAll(any<List<UserMapping>>()) } returns emptyList()

        parser.parse(file, actorId = 7L)

        verify(exactly = 1) { repository.updateAwsAccountName("706840063453", "Legacy-x", any()) }
    }

    @Test
    fun `a linking failure does not fail the import`() {
        val file = csv(
            """
            account_id,owner_email,display_name
            706840063453,owner@corp.com,Legacy-x
            """
        )
        every { repository.saveAll(any<List<UserMapping>>()) } returns emptyList()
        every { linkService.link(any(), any(), any()) } throws RuntimeException("workgroup table is on fire")

        val result = parser.parse(file, actorId = 7L)

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.workgroupLinks?.failed).isEqualTo(1)
    }
}
