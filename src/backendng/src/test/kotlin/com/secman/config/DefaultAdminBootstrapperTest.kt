package com.secman.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.secman.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * A09 regression test: the freshly generated default admin password must never be handed to
 * the logging framework (any sink it's wired to - file, console appender, aggregator). It must
 * still reach the operator, via a direct stdout write that bypasses SLF4J/Logback entirely.
 */
@DisplayName("DefaultAdminBootstrapper password exposure")
class DefaultAdminBootstrapperTest {

    private val userRepository = mockk<UserRepository>(relaxed = true)
    private lateinit var bootstrapper: DefaultAdminBootstrapper

    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private lateinit var originalOut: PrintStream
    private lateinit var capturedOut: ByteArrayOutputStream

    @BeforeEach
    fun setUp() {
        bootstrapper = DefaultAdminBootstrapper(userRepository)
        every { userRepository.count() } returns 0L

        val logger = LoggerFactory.getLogger(DefaultAdminBootstrapper::class.java) as Logger
        logAppender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logAppender)

        originalOut = System.out
        capturedOut = ByteArrayOutputStream()
        System.setOut(PrintStream(capturedOut))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        val logger = LoggerFactory.getLogger(DefaultAdminBootstrapper::class.java) as Logger
        logger.detachAppender(logAppender)
    }

    @Test
    fun `generated password is never passed to the logger`() {
        val savedUser = slot<com.secman.domain.User>()
        every { userRepository.save(capture(savedUser)) } answers { savedUser.captured }

        bootstrapper.bootstrapDefaultAdmin()

        verify(exactly = 1) { userRepository.save(any()) }

        // The plaintext password only ever appears on stdout, never in a log record.
        val console = capturedOut.toString()
        val passwordLine = console.lines().first { it.contains("Password:") }
        val generatedPassword = passwordLine
            .substringAfter("Password:")
            .substringBefore("(CHANGE IMMEDIATELY!)")
            .trim()

        assertThat(generatedPassword).isNotBlank()
        assertThat(BCryptPasswordEncoder().matches(generatedPassword, savedUser.captured.passwordHash)).isTrue()

        val loggedMessages = logAppender.list.map { it.formattedMessage }
        assertThat(loggedMessages).noneMatch { it.contains(generatedPassword) }

        // The console banner still carries the operator-facing content and warning.
        assertThat(console).contains("DEFAULT ADMIN USER CREATED")
        assertThat(console).contains("CHANGE IMMEDIATELY")
    }

    @Test
    fun `skips bootstrap when users already exist`() {
        every { userRepository.count() } returns 5L

        bootstrapper.bootstrapDefaultAdmin()

        verify(exactly = 0) { userRepository.save(any()) }
        assertThat(capturedOut.toString()).isEmpty()
    }
}
