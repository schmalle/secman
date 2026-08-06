package com.secman.cli.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UserMappingValidatorTest {
    private val validator = UserMappingValidator()

    @Test
    fun `normalize helpers trim and lowercase where expected`() {
        assertEquals("user@example.com", validator.normalizeEmail(" User@Example.com "))
        assertEquals("example.org", validator.normalizeDomain(" Example.ORG "))
        assertEquals("123456789012", validator.normalizeAccountId(" 123456789012 "))
    }

    @Test
    fun `validateEmails throws for malformed input`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            validator.validateEmails(listOf("ok@example.com", "bad-email"))
        }
        assertEquals(true, ex.message!!.contains("Invalid email format"))
    }

    @Test
    fun `validateAwsAccountIds throws for malformed account`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            validator.validateAwsAccountIds(listOf("123456789012", "abc"))
        }
        assertEquals(true, ex.message!!.contains("Invalid AWS account ID"))
    }
}
