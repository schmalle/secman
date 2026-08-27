package com.secman.cli.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The CLI is the only place that understands the Cloud Custodian account file, so a
 * display name it drops here can never reach the backend and no workgroup is ever linked.
 * These tests pin that the field survives parsing in both formats.
 */
class UserMappingCliServiceDisplayNameTest {

    private val service = UserMappingCliService(UserMappingValidator(), CliJavaHttpClientFactory())

    private fun tempFile(suffix: String, content: String): File {
        val file = Files.createTempFile("mapping-test-", suffix).toFile()
        file.deleteOnExit()
        file.writeText(content)
        return file
    }

    @Test
    fun `display_name from the Cloud Custodian JSON reaches the parsed entry`() {
        val file = tempFile(
            ".json",
            """
            {"accounts": [
              {"account_id": "706840063453", "email": "aws-root@corp.com",
               "display_name": "Legacy-x", "name": "706840063453", "status": "ACTIVE",
               "vars": {"cov:owner": "owner@corp.com"}}
            ]}
            """.trimIndent()
        )

        val result = service.parseLocalMappingFile(file.absolutePath)

        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        // The real owner still wins over the root account address...
        assertEquals("owner@corp.com", entry["email"])
        assertEquals("706840063453", entry["awsAccountId"])
        // ...and the display name rides along with it.
        assertEquals("Legacy-x", entry["displayName"])
    }

    @Test
    fun `a JSON entry without display_name parses exactly as before`() {
        val file = tempFile(
            ".json",
            """[{"email": "owner@corp.com", "awsAccounts": ["111111111111"]}]"""
        )

        val result = service.parseLocalMappingFile(file.absolutePath)

        assertEquals(1, result.entries.size)
        assertNull(result.entries.single()["displayName"])
    }

    @Test
    fun `the optional display_name CSV column reaches the parsed entry`() {
        val file = tempFile(
            ".csv",
            """
            email,type,value,display_name
            owner@corp.com,AWS_ACCOUNT,111111111111,DevOps-x
            """.trimIndent()
        )

        val result = service.parseLocalMappingFile(file.absolutePath)

        assertEquals(1, result.entries.size)
        assertEquals("DevOps-x", result.entries.single()["displayName"])
    }

    @Test
    fun `a CSV without the display_name column still parses`() {
        // commons-csv throws on get() for an unknown header, so the absence of the column
        // has to be detected rather than caught — this is the regression guard for that.
        val file = tempFile(
            ".csv",
            """
            email,type,value
            owner@corp.com,AWS_ACCOUNT,111111111111
            owner@corp.com,DOMAIN,corp.com
            """.trimIndent()
        )

        val result = service.parseLocalMappingFile(file.absolutePath)

        assertTrue(result.errors.isEmpty(), "unexpected parse errors: ${result.errors}")
        assertEquals(2, result.entries.size)
        assertNull(result.entries.first()["displayName"])
    }

    @Test
    fun `a domain row ignores display_name`() {
        val file = tempFile(
            ".csv",
            """
            email,type,value,display_name
            owner@corp.com,DOMAIN,corp.com,DevOps-x
            """.trimIndent()
        )

        val result = service.parseLocalMappingFile(file.absolutePath)

        // Display names name AWS accounts; a domain mapping has no account to link.
        assertNull(result.entries.single()["displayName"])
        assertEquals("corp.com", result.entries.single()["domain"])
    }
}
