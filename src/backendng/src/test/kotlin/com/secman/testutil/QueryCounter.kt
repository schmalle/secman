package com.secman.testutil

import org.hibernate.resource.jdbc.spi.StatementInspector
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Records every SQL statement Hibernate prepares while a [QueryCounter.capture] block runs, so a
 * test can pin the number of round trips an endpoint or service call costs. Query counts are
 * deterministic where wall-clock timings are not, which makes them a stable read-performance gate
 * (docs/plans/DB_READ_OPTIMIZATION_PLAN.md, Phase 0).
 *
 * Registered for the test environment only (`hibernate.session_factory.statement_inspector` in
 * application-test.yml). Recording is global rather than per thread because Micronaut serves an
 * embedded-server request on a different thread from the test; tests that capture must not run
 * concurrently with other database work, which is already true of the integration suite.
 */
class QueryCounter : StatementInspector {
    override fun inspect(sql: String): String {
        if (recording) statements.add(sql)
        return sql
    }

    companion object {
        @Volatile private var recording = false
        private val statements = ConcurrentLinkedQueue<String>()

        /** Runs [block] and returns the statements it caused, in order. */
        fun capture(block: () -> Unit): List<String> {
            statements.clear()
            recording = true
            try {
                block()
            } finally {
                recording = false
            }
            return statements.toList()
        }
    }
}
