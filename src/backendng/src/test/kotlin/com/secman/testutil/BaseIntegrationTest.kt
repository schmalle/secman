package com.secman.testutil

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Base class for integration tests using Testcontainers.
 * Feature: 056-test-suite
 *
 * Provides:
 * - MariaDB container shared across all test classes (singleton container pattern)
 * - Dynamic property injection for datasource configuration
 * - Test isolation via transaction rollback (@MicronautTest default)
 * - Auto-disable when Docker is not available
 *
 * Usage:
 * ```kotlin
 * class MyIntegrationTest : BaseIntegrationTest() {
 *     @Inject
 *     lateinit var myRepository: MyRepository
 *
 *     @Test
 *     fun `should do something`() {
 *         // test code
 *     }
 * }
 * ```
 */
@MicronautTest(environments = ["test"])
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseIntegrationTest : TestPropertyProvider {

    companion object {
        /**
         * Singleton MariaDB container shared across all test classes.
         * Using MariaDB 11.4 to match production environment.
         *
         * Container is started once and reused for all tests,
         * significantly reducing test execution time.
         *
         * Note: Container is started lazily only when Docker is available.
         */
        @JvmStatic
        val mariadb: MariaDBContainer<*> by lazy {
            MariaDBContainer("mariadb:11.4")
                .withDatabaseName("secman_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true)
                .also { it.start() }
        }
    }

    /**
     * Provide dynamic properties for datasource configuration.
     * Called by Micronaut Test framework before test context initialization.
     */
    override fun getProperties(): MutableMap<String, String> {
        return mutableMapOf(
            "datasources.default.url" to mariadb.jdbcUrl,
            "datasources.default.username" to mariadb.username,
            "datasources.default.password" to mariadb.password,
            "datasources.default.driver-class-name" to "org.mariadb.jdbc.Driver"
        )
    }
}

/**
 * Helper class for JUnit 5 @EnabledIf condition.
 * Checks if Docker is available for Testcontainers.
 */
class DockerAvailable {
    companion object {
        @JvmStatic
        fun isDockerAvailable(): Boolean {
            return try {
                DockerClientFactory.instance().isDockerAvailable
            } catch (e: Exception) {
                false
            }
        }
    }
}
