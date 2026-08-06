package com.secman.testutil

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import org.junit.jupiter.api.TestInstance

/**
 * Base class for integration tests.
 *
 * Runs against an EXTERNAL MariaDB — no Docker / Testcontainers. The datasource is configured in
 * `src/test/resources/application-test.yml` from the environment variables:
 *
 *   - `TEST_DB_URL`      (default `jdbc:mariadb://localhost:3306/secman_test`)
 *   - `TEST_DB_USERNAME` (default `secman_test`)
 *   - `TEST_DB_PASSWORD` (default `secman_test`)
 *
 * Per project convention these should be supplied via `pass-cli` when running the suite.
 *
 * SAFETY: the test schema is created and dropped on every run via Hibernate
 * `hbm2ddl.auto=create-drop`, so the target database MUST be a dedicated, disposable test database.
 * Never point `TEST_DB_URL` at the dev or production database (`DB_CONNECT`) — it would drop tables.
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseIntegrationTest
