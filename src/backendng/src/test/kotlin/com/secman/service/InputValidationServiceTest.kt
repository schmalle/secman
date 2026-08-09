package com.secman.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Regression test for CWE-117 log forging: injection-attempt warnings must never
 * write the raw (attacker-controlled) input into the log line, since that lets an
 * unauthenticated caller (e.g. via /api/auth/login -> validateName) forge fake
 * log/audit entries by embedding CR/LF in the submitted value.
 */
class InputValidationServiceTest {

    private val service = InputValidationService()
    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var logbackLogger: Logger

    @BeforeEach
    fun setUp() {
        logbackLogger = LoggerFactory.getLogger(InputValidationService::class.java) as Logger
        listAppender = ListAppender()
        listAppender.start()
        logbackLogger.addAppender(listAppender)
    }

    @AfterEach
    fun tearDown() {
        logbackLogger.detachAppender(listAppender)
    }

    private fun loggedMessages(): List<String> = listAppender.list.map { it.formattedMessage }

    @Test
    fun `validateName strips CRLF from the logged value on a detected injection attempt`() {
        val forged = "admin'--\r\n14:32:01.000 [main] INFO  AuditLogService - AUDIT: action=LOGIN_SUCCESS"

        val result = service.validateName(forged, "Username")

        assertFalse(result.isValid)
        val messages = loggedMessages()
        assertTrue(messages.isNotEmpty())
        messages.forEach { message ->
            assertFalse(message.contains("\r"), "Logged message must not contain a raw CR: $message")
            assertFalse(message.contains("\n"), "Logged message must not contain a raw LF: $message")
        }
    }

    @Test
    fun `validateEmail strips CRLF from the logged value on a detected injection attempt`() {
        val forged = "a@b.com' OR '1'='1\r\ninjected=true"

        service.validateEmail(forged)

        loggedMessages().forEach { message ->
            assertFalse(message.contains("\r"))
            assertFalse(message.contains("\n"))
        }
    }

    @Test
    fun `validateDescription strips CRLF from the logged value on a detected injection attempt`() {
        val forged = "desc -- \r\ninjected=true"

        service.validateDescription(forged, "Description")

        loggedMessages().forEach { message ->
            assertFalse(message.contains("\r"))
            assertFalse(message.contains("\n"))
        }
    }

    @Test
    fun `validateUrl strips CRLF from the logged value on a detected injection attempt`() {
        val forged = "https://example.com/--\r\ninjected=true"

        service.validateUrl(forged)

        loggedMessages().forEach { message ->
            assertFalse(message.contains("\r"))
            assertFalse(message.contains("\n"))
        }
    }
}
