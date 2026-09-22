package com.secman.repository

import com.secman.testutil.BaseIntegrationTest
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import io.micronaut.transaction.TransactionOperations
import java.sql.Connection

/** Connection-local fixtures rehearse additive migration SQL without touching shared tables. */
@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
class AuthorizationMigrationTest : BaseIntegrationTest() {
    @Inject lateinit var transactions: TransactionOperations<Connection>

    @Test fun `legacy answers and grants survive while legacy capabilities fail closed`() {
        transactions.executeWrite<Unit> { status ->
            val connection = status.connection
            connection.createStatement().use { sql ->
                try {
                    sql.execute("CREATE TEMPORARY TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(255))")
                    sql.execute("CREATE TEMPORARY TABLE risk_assessment (id BIGINT PRIMARY KEY, assessor_id BIGINT, respondent_id BIGINT, status VARCHAR(50))")
                    sql.execute("CREATE TEMPORARY TABLE response (risk_assessment_id BIGINT, requirement_id BIGINT, respondent_email VARCHAR(255), answer_type VARCHAR(50))")
                    sql.execute("CREATE TEMPORARY TABLE mcp_api_keys (id BIGINT PRIMARY KEY)")
                    sql.execute("INSERT INTO mcp_api_keys (id) VALUES (1)")
                    sql.execute("CREATE TEMPORARY TABLE assessment_token (id BIGINT PRIMARY KEY)")
                    sql.execute("INSERT INTO assessment_token (id) VALUES (1)")
                    sql.execute("CREATE TEMPORARY TABLE ai_suggestion_job (id BIGINT PRIMARY KEY)")
                    sql.execute("INSERT INTO ai_suggestion_job (id) VALUES (1)")
                    sql.execute("CREATE TEMPORARY TABLE workgroup_aws_account (id BIGINT PRIMARY KEY)")
                    sql.execute("INSERT INTO workgroup_aws_account (id) VALUES (1)")
                    sql.execute("CREATE TEMPORARY TABLE export_jobs (id BIGINT PRIMARY KEY)")
                    sql.execute("INSERT INTO export_jobs (id) VALUES (1)")
                    sql.execute("INSERT INTO users VALUES (1, 'author@example.test'), (2, 'reviewer@example.test')")
                    sql.execute("INSERT INTO risk_assessment VALUES (1, 2, 1, 'COMPLETED')")
                    sql.execute("INSERT INTO response VALUES (1, 9, 'author@example.test', 'YES')")
                    val migrations = listOf("V272__explicit_mcp_delegates.sql", "V273__assessment_authorization_history.sql",
                        "V274__ai_job_authorization.sql", "V275__workgroup_grant_provenance.sql",
                        "V276__export_authorization.sql", "V277__user_access_state.sql")
                    migrations.forEach { name ->
                        val script = javaClass.getResourceAsStream("/db/migration/$name")!!.bufferedReader().use { it.readText() }
                            .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                            .replace("CREATE TABLE ", "CREATE TEMPORARY TABLE ")
                        script.split(';').map(String::trim).filter(String::isNotBlank).forEach(sql::execute)
                    }
                    sql.executeQuery("SELECT status, authorship_complete, answer_revision FROM risk_assessment").use {
                        assertTrue(it.next()); assertEquals("COMPLETED", it.getString(1)); assertFalse(it.getBoolean(2)); assertEquals(0L, it.getLong(3))
                    }
                    sql.executeQuery("SELECT answer_type FROM response").use { assertTrue(it.next()); assertEquals("YES", it.getString(1)) }
                    sql.executeQuery("SELECT actor_user_id, source FROM assessment_contribution").use {
                        assertTrue(it.next()); assertEquals(1L, it.getLong(1)); assertEquals("LEGACY", it.getString(2))
                    }
                    sql.executeQuery("SELECT COUNT(*) FROM assessment_assignment").use { assertTrue(it.next()); assertEquals(2, it.getInt(1)) }
                    sql.executeQuery("SELECT assignment_id, assignment_version FROM assessment_token").use {
                        assertTrue(it.next()); assertNull(it.getObject(1)); assertEquals(-1L, it.getLong(2))
                    }
                    sql.executeQuery("SELECT manual_grant, owner_sync_grant FROM workgroup_aws_account").use {
                        assertTrue(it.next()); assertTrue(it.getBoolean(1)); assertFalse(it.getBoolean(2))
                    }
                    sql.executeQuery("SELECT allowed_delegate_user_ids FROM mcp_api_keys").use { assertTrue(it.next()); assertEquals("", it.getString(1)) }
                    sql.executeQuery("SELECT actor_user_id, scope_digest FROM export_jobs").use {
                        assertTrue(it.next()); assertNull(it.getObject(1)); assertNull(it.getObject(2))
                    }
                } finally {
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS mcp_api_keys")
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS users")
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS risk_assessment")
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS assessment_token")
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS response")
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS ai_suggestion_job")
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS workgroup_aws_account")
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS export_jobs")
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS assessment_assignment")
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS assessment_contribution")
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS assessment_acceptance")
                }
            }
        }
    }
}
