package com.secman.config

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.ApplicationStartupEvent
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.security.SecureRandom

/**
 * Creates a default admin user on first application startup if no users exist.
 *
 * Default credentials:
 *   Username: admin
 *   Email:    admin@localhost
 *   Password: randomly generated (20 characters, printed to console)
 *   Roles:    ADMIN, USER
 *
 * IMPORTANT: Change the default password immediately after first login in production.
 */
@Requires(notEnv = ["cli"])
@Singleton
open class DefaultAdminBootstrapper(
    private val userRepository: UserRepository,
    @Value("\${SECMAN_TEST_ISOLATED_DB:}") private val isolatedDb: String = "",
    @Value("\${DB_CONNECT:}") private val dbConnect: String = "",
    @Value("\${SECMAN_E2E_ADMIN_NAME:}") private val isolatedAdminName: String = "",
    @Value("\${SECMAN_E2E_ADMIN_EMAIL:}") private val isolatedAdminEmail: String = "",
    @Value("\${SECMAN_E2E_ADMIN_PASS:}") private val isolatedAdminPass: String = ""
) : ApplicationEventListener<ApplicationStartupEvent> {

    private val log = LoggerFactory.getLogger(DefaultAdminBootstrapper::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

    private val isolatedTest = isolatedDb.isNotBlank().also { enabled ->
        if (enabled) {
            require(Regex("secman_e2e_[a-f0-9]{16}").matches(isolatedDb) &&
                dbConnect == "jdbc:mariadb://127.0.0.1:3306/$isolatedDb" &&
                isolatedAdminName.isNotBlank() && isolatedAdminEmail.isNotBlank() &&
                isolatedAdminPass.isNotBlank()) {
                "Isolated admin bootstrap requires a verified test database and credentials"
            }
        }
    }

    companion object {
        const val DEFAULT_ADMIN_USERNAME = "admin"
        const val DEFAULT_ADMIN_EMAIL = "admin@localhost"

        private const val PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%&*"
        private const val GENERATED_PASSWORD_LENGTH = 20

        fun generateSecurePassword(): String {
            val random = SecureRandom()
            return (1..GENERATED_PASSWORD_LENGTH)
                .map { PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)] }
                .joinToString("")
        }
    }

    override fun onApplicationEvent(event: ApplicationStartupEvent) {
        bootstrapDefaultAdmin()
    }

    @Transactional
    open fun bootstrapDefaultAdmin() {
        try {
            val userCount = userRepository.count()
            if (userCount > 0) {
                log.debug("Users already exist (count={}), skipping default admin creation", userCount)
                return
            }

            val generatedPassword = if (isolatedTest) isolatedAdminPass else generateSecurePassword()
            val adminName = if (isolatedTest) isolatedAdminName else DEFAULT_ADMIN_USERNAME
            val adminEmail = if (isolatedTest) isolatedAdminEmail else DEFAULT_ADMIN_EMAIL

            val admin = User(
                username = adminName,
                email = adminEmail,
                passwordHash = passwordEncoder.encode(generatedPassword)!!,
                roles = mutableSetOf(User.Role.ADMIN, User.Role.USER),
                authSource = User.AuthSource.LOCAL
            )

            userRepository.save(admin)
            if (isolatedTest) {
                log.info("Isolated test admin created (username={})", adminName)
                return
            }
            log.warn("Default admin user created (username={}); generated credential printed to console only, never logged", DEFAULT_ADMIN_USERNAME)
            // The generated credential is written straight to stdout, bypassing SLF4J/Logback
            // entirely, so it never reaches a log file, log appender or centralized log
            // aggregator. See CLAUDE.md A09: never log a password, token, cookie value or API
            // key. The banner text is assembled first and emitted with a single stdout write.
            val divider = "=".repeat(58)
            val firstBootBanner = buildString {
                appendLine(divider)
                appendLine("  DEFAULT ADMIN USER CREATED")
                appendLine("  Username: $DEFAULT_ADMIN_USERNAME")
                appendLine("  Password: $generatedPassword (CHANGE IMMEDIATELY!)")
                append(divider)
            }
            println(firstBootBanner)
        } catch (e: Exception) {
            log.error("Failed to bootstrap default admin user: {}", e.message, e)
        }
    }
}
