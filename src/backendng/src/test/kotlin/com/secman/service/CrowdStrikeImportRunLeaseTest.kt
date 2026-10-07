package com.secman.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CrowdStrikeImportRunLeaseTest {
    private val lease = CrowdStrikeImportRunLease()
    private val start = Instant.parse("2026-10-06T10:00:00Z")
    private fun advance(seconds: Long) { lease.clock = Clock.fixed(start.plusSeconds(seconds), ZoneOffset.UTC) }

    @Test
    fun `only initiating owner can heartbeat or finish and competing starts conflict`() {
        advance(0)
        val id = lease.start("owner")
        assertThatThrownBy { lease.start("other") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { lease.heartbeat(id, "other") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { lease.finish(id, "other") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(lease.activeRunId()).isEqualTo(id)
        lease.finish(id, "owner")
        assertThat(lease.activeRunId()).isNull()
    }

    @Test
    fun `heartbeats bridge long fetch gaps but a crashed importer expires`() {
        advance(0)
        val id = lease.start("owner")
        advance(590)
        lease.heartbeat(id, "owner")
        advance(1100)
        assertThat(lease.activeRunId()).isEqualTo(id)
        advance(1190)
        assertThat(lease.activeRunId()).isNull()
        assertThatThrownBy { lease.heartbeat(id, "owner") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(lease.start("new-owner")).isNotEqualTo(id)
    }
}
