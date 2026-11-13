package com.secman.cli.service

import com.secman.crowdstrike.client.QueriedHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReconcileScopeTest {

    private fun host(name: String?, aid: String) = QueriedHost(name, null, aid)

    @Test
    fun `failed FQDN no longer shields a different machine sharing its short name`() {
        val failed = host("web01.a.example", "aid-1")
        val other = host("web01.b.example", "aid-2")

        val scope = reconcileScopeHosts(setOf(failed, other), emptySet(), listOf("web01.a.example"), setOf("aid-1"))

        assertThat(scope).containsExactly(other)
    }

    @Test
    fun `bare short name on either side still excludes the matching host`() {
        val qualified = host("web01.a.example", "aid-1")
        val bare = host("WEB01", "aid-2")

        assertThat(reconcileScopeHosts(setOf(qualified), emptySet(), listOf("web01"), emptySet())).isEmpty()
        assertThat(reconcileScopeHosts(setOf(bare), emptySet(), listOf("web01.a.example"), emptySet())).isEmpty()
    }

    @Test
    fun `failed AID and fetch-side failures are excluded whatever the hostname`() {
        val byAid = host("db01.a.example", "aid-9")
        val fetchFailed = host("app01.a.example", "aid-3")
        val healthy = host("app02.a.example", "aid-4")

        val scope = reconcileScopeHosts(setOf(byAid, fetchFailed, healthy), setOf(fetchFailed), emptyList(), setOf("aid-9"))

        assertThat(scope).containsExactly(healthy)
    }

    @Test
    fun `hosts without a hostname are kept unless their AID failed`() {
        val nameless = host(null, "aid-5")

        assertThat(reconcileScopeHosts(setOf(nameless), emptySet(), listOf("web01"), emptySet())).containsExactly(nameless)
        assertThat(reconcileScopeHosts(setOf(nameless), emptySet(), emptyList(), setOf("aid-5"))).isEmpty()
    }

    @Test
    fun `sameMachineName ignores case and surrounding whitespace`() {
        assertThat(sameMachineName(" Web01.A.Example ", "web01.a.example")).isTrue()
        assertThat(sameMachineName("web01.a.example", "web02.a.example")).isFalse()
    }
}
