package com.secman.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CSVGithubOwnerEmailMappingParserTest {

    private fun tempCsv(content: String): File {
        val file = Files.createTempFile("owner_email_mapping_test_", ".csv").toFile()
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `imports valid owner,email rows`() {
        val service = mockk<GithubOwnerEmailMappingService>()
        every { service.create(any(), any(), any()) } answers {
            com.secman.domain.GithubOwnerEmailMapping(id = 1, owner = firstArg(), email = secondArg(), createdBy = thirdArg())
        }
        val parser = CSVGithubOwnerEmailMappingParser(service)

        val file = tempCsv(
            """
            owner,email
            acme-corp,security@acme-corp.example.com
            other-org,owner@other-org.example.com
            """.trimIndent()
        )

        val result = parser.parse(file, "admin")

        assertThat(result.imported).isEqualTo(2)
        assertThat(result.skipped).isEqualTo(0)
        verify { service.create("acme-corp", "security@acme-corp.example.com", "admin") }
        verify { service.create("other-org", "owner@other-org.example.com", "admin") }
    }

    @Test
    fun `rejects file missing required headers`() {
        val service = mockk<GithubOwnerEmailMappingService>()
        val parser = CSVGithubOwnerEmailMappingParser(service)
        val file = tempCsv("owner\nacme-corp")

        assertThatThrownBy { parser.parse(file, "admin") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("email")
    }

    @Test
    fun `skips duplicate owner within the same file`() {
        val service = mockk<GithubOwnerEmailMappingService>()
        every { service.create(any(), any(), any()) } answers {
            com.secman.domain.GithubOwnerEmailMapping(id = 1, owner = firstArg(), email = secondArg(), createdBy = thirdArg())
        }
        val parser = CSVGithubOwnerEmailMappingParser(service)

        val file = tempCsv(
            """
            owner,email
            acme-corp,first@example.com
            acme-corp,second@example.com
            """.trimIndent()
        )

        val result = parser.parse(file, "admin")

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(1)
        verify(exactly = 1) { service.create(any(), any(), any()) }
    }

    @Test
    fun `records a row-level error when the service rejects a duplicate owner already in the database`() {
        val service = mockk<GithubOwnerEmailMappingService>()
        every { service.create("acme-corp", "security@acme-corp.example.com", "admin") } throws
            GithubOwnerEmailMappingService.DuplicateOwnerException("acme-corp")
        val parser = CSVGithubOwnerEmailMappingParser(service)

        val file = tempCsv("owner,email\nacme-corp,security@acme-corp.example.com")

        val result = parser.parse(file, "admin")

        assertThat(result.imported).isEqualTo(0)
        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.errors).hasSize(1)
    }
}
