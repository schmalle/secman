package com.secman.config

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.ApplicationStartupEvent
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/**
 * Creates a default admin user on first application startup if no users exist.
 *
 * Default credentials:
 *   Username: admin
 *   Email:    admin@localhost
 *   Password: password
 *   Roles:    ADMIN, USER
 *
 * IMPORTANT: Change the default password immediately after first login in production.
 */
@Singleton
open class DefaultAdminBootstrapper(
    private val userRepository: UserRepository
) : ApplicationEventListener<ApplicationStartupEvent> {

    private val log = LoggerFactory.getLogger(DefaultAdminBootstrapper::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

    companion object {
        const val DEFAULT_ADMIN_USERNAME = "admin"
        const val DEFAULT_ADMIN_EMAIL = "admin@localhost"
        const val DEFAULT_ADMIN_PASSWORD = "password"
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

            val admin = User(
                username = DEFAULT_ADMIN_USERNAME,
                email = DEFAULT_ADMIN_EMAIL,
                passwordHash = passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD),
                roles = mutableSetOf(User.Role.ADMIN, User.Role.USER),
                authSource = User.AuthSource.LOCAL
            )

            userRepository.save(admin)
            log.warn("==========================================================")
            log.warn("  DEFAULT ADMIN USER CREATED")
            log.warn("  Username: {}", DEFAULT_ADMIN_USERNAME)
            log.warn("  Password: {} (CHANGE IMMEDIATELY!)", DEFAULT_ADMIN_PASSWORD)
            log.warn("==========================================================")
        } catch (e: Exception) {
            log.error("Failed to bootstrap default admin user: {}", e.message, e)
        }
    }
}
