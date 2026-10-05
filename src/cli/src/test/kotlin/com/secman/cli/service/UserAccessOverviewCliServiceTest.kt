package com.secman.cli.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.cli.commands.UserAccessOverviewCommand
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class UserAccessOverviewCliServiceTest {
    private val mapper = jacksonObjectMapper()

    private fun page(index: Int, id: Long = index + 1L, total: Int = 2): String = mapper.writeValueAsString(mapOf(
        "user" to mapOf("id" to 7, "username" to "viewer", "email" to "viewer@example.com", "enabled" to true, "roles" to listOf("USER", "VULN")),
        "generatedAt" to "2026-10-04T12:00:00Z", "vulnerabilityAccess" to true, "globalAccess" to false,
        "totalAssets" to total, "awsAccounts" to emptyList<Any>(), "adDomains" to emptyList<Any>(),
        "assets" to if (total == 0) emptyList() else listOf(mapOf("id" to id, "name" to "asset-$id", "reasons" to emptyList<Any>())),
        "page" to index, "size" to 1, "totalPages" to total
    ))

    private fun withServer(report: (Int) -> Pair<Int, String>, action: (URI) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val token = UUID.randomUUID().toString()
        val redirects = AtomicInteger()
        server.createContext("/redirected") { exchange ->
            redirects.incrementAndGet()
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.createContext("/api/auth/login") { exchange ->
            assertThat(exchange.requestMethod).isEqualTo("POST")
            exchange.responseHeaders.add("Set-Cookie", "secman_auth=$token; HttpOnly; Path=/")
            exchange.responseHeaders.add("Content-Type", "application/json")
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/users/access-overview") { exchange ->
            assertThat(exchange.requestHeaders.getFirst("Authorization")).isEqualTo("Bearer $token")
            assertThat(exchange.requestURI.rawQuery).contains("email=viewer%40example.com")
            val index = Regex("page=(\\d+)").find(exchange.requestURI.rawQuery)!!.groupValues[1].toInt()
            val (status, body) = report(index)
            if (status == 302) exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/redirected")
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            action(URI("http://127.0.0.1:${server.address.port}"))
            assertThat(redirects.get()).isZero()
        } finally { server.stop(0) }
    }

    private fun fetch(url: URI) = UserAccessOverviewCliService(mapper).fetch(url, "admin", UUID.randomUUID().toString(), "viewer@example.com", 1)

    @Test
    fun `cookie authentication follows every page and produces a complete JSON report`() {
        withServer({ 200 to page(it) }) { url ->
            val result = fetch(url)
            assertThat(result.path("assets").map { it.path("id").asLong() }).containsExactly(1L, 2L)
            assertThat(result.path("complete").asBoolean()).isTrue()
            assertThat(result.has("page")).isFalse()
            assertThat(mapper.readTree(mapper.writeValueAsString(result))).isEqualTo(result)
        }
    }

    @Test
    fun `empty access is successful and distinct from failure`() {
        withServer({ 200 to page(it, total = 0) }) { url ->
            assertThat(fetch(url).path("assets").size()).isZero()
        }
    }

    @Test
    fun `denials missing users and server errors have explicit exit codes`() {
        for ((status, code) in listOf(400 to 2, 403 to 3, 404 to 4, 500 to 4)) {
            withServer({ status to "{}" }) { url ->
                assertThat(assertThrows(UserAccessOverviewCliService.ReportFailure::class.java) { fetch(url) }.exitCode).isEqualTo(code)
            }
        }
    }

    @Test
    fun `duplicate missing and changing pages never return a partial report`() {
        val malformed = listOf<(Int) -> Pair<Int, String>>(
            { 200 to page(it, id = 1) },
            { 200 to page(it).replace("\"assets\":[{", "\"assets\":[],\"discarded\":[{") },
            { 200 to page(it).replace("\"globalAccess\":false", "\"globalAccess\":${it > 0}") }
        )
        for (response in malformed) withServer(response) { url ->
            assertThat(assertThrows(UserAccessOverviewCliService.ReportFailure::class.java) { fetch(url) }.exitCode).isEqualTo(4)
        }
    }

    @Test
    fun `redirects do not forward authentication to another origin`() {
        withServer({ 302 to "{}" }) { url ->
            assertThat(assertThrows(Exception::class.java) { fetch(url) }).isNotNull()
        }
    }

    @Test
    fun `backend origin validation rejects credential and unencrypted remote URLs`() {
        for (url in listOf("http://remote.example", "https://admin:secret@example.com", "https://example.com/path", "https://example.com?x=y", "https://example.com#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { UserAccessOverviewCommand.validateBackendUrl(url) }
        }
        assertThat(UserAccessOverviewCommand.validateBackendUrl("example.com").scheme).isEqualTo("https")
        assertThat(UserAccessOverviewCommand.validateBackendUrl("http://127.0.0.1:18080").port).isEqualTo(18080)
    }

    @Test
    fun `table escapes terminal controls and distinguishes partial scope`() {
        val report = mapper.readTree(page(0))
        (report.path("assets")[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("name", "unsafe\u001b[31m\nname")
        (report as com.fasterxml.jackson.databind.node.ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>("awsAccounts", mapper.readTree("""
            [{"value":"111111111111","visibleAssetCount":1,"wholeScopeAccess":false,"reasons":[]},
             {"value":"999999999999","visibleAssetCount":0,"wholeScopeAccess":true,"reasons":[{"type":"PERSONAL_MAPPING"}]}]
        """))
        val output = UserAccessOverviewCommand.renderTable(report)
        assertThat(output).doesNotContain("\u001b", "unsafe\u001b")
        assertThat(output).contains("Vulnerability list access: yes", "Visible assets: 2")
        assertThat(output).contains("visible assets only", "whole scope", "(no matching assets)", "PERSONAL_MAPPING")
    }
}
