package com.secman.config

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.ApplicationStartupEvent
import io.micronaut.context.annotation.Requires
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
    private val userRepository: UserRepository
) : ApplicationEventListener<ApplicationStartupEvent> {

    private val log = LoggerFactory.getLogger(DefaultAdminBootstrapper::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

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

            val generatedPassword = generateSecurePassword()

            val admin = User(
                username = DEFAULT_ADMIN_USERNAME,
                email = DEFAULT_ADMIN_EMAIL,
                passwordHash = passwordEncoder.encode(generatedPassword)!!,
                roles = mutableSetOf(User.Role.ADMIN, User.Role.USER),
                authSource = User.AuthSource.LOCAL
            )

            userRepository.save(admin)
            log.warn("==========================================================")
            log.warn("  DEFAULT ADMIN USER CREATED")
            log.warn("  Username: {}", DEFAULT_ADMIN_USERNAME)
            log.warn("  Password: {} (CHANGE IMMEDIATELY!)", generatedPassword)
            log.warn("==========================================================")
        } catch (e: Exception) {
            log.error("Failed to bootstrap default admin user: {}", e.message, e)
        }
    }
}
