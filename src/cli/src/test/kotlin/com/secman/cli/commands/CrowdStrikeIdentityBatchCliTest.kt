package com.secman.cli.commands

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID

class CrowdStrikeIdentityBatchCliTest {
    private val mapper = jacksonObjectMapper()
    private fun candidate(id: Long) = mapOf("assetId" to id, "hostname" to "same-hostname",
        "expectedAids" to listOf("aid-a-$id", "aid-b-$id"))
    private fun page(ids: List<Long>, next: Long, more: Boolean) = mapOf("candidates" to ids.map(::candidate),
        "nextAfterId" to next, "throughId" to 100, "hasMore" to more)
    private fun success(body: JsonNode): Map<String, Any> {
        val id = body.path("assetId").asLong()
        return mapOf("assetId" to id, "dryRun" to body.path("dryRun").asBoolean(),
            "devices" to body.path("expectedAids").mapIndexed { index, aid -> mapOf("aid" to aid.asText(), "assetId" to id + index) })
    }

    private fun run(pages: (String) -> Any, repair: (JsonNode) -> Pair<Int, Any>, apply: Boolean = false,
        after: Long = 0, lookup: ((String, Map<String, JsonNode>) -> Pair<Int, Any>)? = null): Triple<JsonNode, List<JsonNode>, List<String>> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val token = UUID.randomUUID().toString()
        val queries = mutableListOf<String>()
        var logins = 0
        val posted = linkedMapOf<String, JsonNode>()
        val handlerErrors = mutableListOf<Throwable>()
        fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: Any) {
            val bytes = mapper.writeValueAsBytes(body)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/login") { exchange ->
            logins++
            exchange.responseHeaders.add("Set-Cookie", "secman_auth=$token; HttpOnly; Path=/")
            respond(exchange, 200, emptyMap<String, Any>())
        }
        server.createContext("/api/crowdstrike/identity-reconcile") { exchange ->
            try {
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $token") respond(exchange, 403, emptyMap<String, Any>())
            else if (exchange.requestMethod == "GET" && exchange.requestURI.path.contains("/requests/")) {
                val id = exchange.requestURI.path.substringAfterLast('/')
                val (status, body) = lookup?.invoke(id, posted) ?: (404 to emptyMap<String, Any>())
                respond(exchange, status, body)
            }
            else if (exchange.requestMethod == "GET") {
                queries.add(exchange.requestURI.rawQuery)
                respond(exchange, 200, pages(exchange.requestURI.rawQuery))
            } else {
                val request = mapper.readTree(exchange.requestBody)
                posted[request.path("requestId").asText()] = request
                val (status, body) = repair(request)
                respond(exchange, status, body)
            }
            } catch (e: Throwable) {
                handlerErrors.add(e)
                respond(exchange, 500, emptyMap<String, Any>())
            }
        }
        server.start()
        try {
            val command = ReconcileCrowdStrikeIdentitiesCommand(mapper).apply {
                allCandidates = true; this.apply = apply; reason = "Verified legacy device recovery"; afterId = after; batchSize = 2
            }
            val output = StringWriter()
            CommandLine(command).out = PrintWriter(output)
            val summary = command.submit(URI("http://127.0.0.1:${server.address.port}"), "admin", UUID.randomUUID().toString())
            assertThat(logins).isEqualTo(1)
            assertThat(handlerErrors).isEmpty()
            val allEvents = output.toString().lineSequence().filter(String::isNotBlank).map(mapper::readTree).toList()
            assertThat(allEvents.filter { it.path("type").asText() == "request" }.map { it.path("requestId").asText() })
                .containsExactlyElementsOf(posted.keys)
            val events = allEvents.filter { it.path("type").asText() == "candidate" }
            return Triple(summary, events, queries.toList())
        } finally { server.stop(0) }
    }

    @Test
    fun `discovery forwards complete 26 and 100 AID sets and rejects 101`() {
        for (size in listOf(26, 100, 101)) {
            var writes = 0
            val aids = (0 until size).map { "aid-$it" }
            val (summary, events) = run(pages = {
                mapOf("candidates" to listOf(mapOf("assetId" to 42, "hostname" to "legacy-host",
                    "expectedAids" to aids)), "nextAfterId" to 42, "throughId" to 100, "hasMore" to false)
            }, repair = { body ->
                writes++
                assertThat(body.path("expectedAids").map { it.asText() }).containsExactlyElementsOf(aids)
                200 to success(body)
            })
            if (size <= 100) {
                assertThat(writes).isEqualTo(1)
                assertThat(summary.path("complete").asBoolean()).isTrue()
                assertThat(events.single().path("status").asText()).isEqualTo("PREVIEWED")
            } else {
                assertThat(writes).isZero()
                assertThat(summary.path("complete").asBoolean()).isFalse()
                assertThat(events).isEmpty()
            }
        }
    }

    @Test
    fun `all candidates are paged with one login and failures do not stop independent repairs`() {
        val calls = mutableListOf<Long>()
        val (summary, events, queries) = run(pages = { query ->
            if (query.contains("afterId=0&")) page(listOf(42, 43), 43, true) else page(listOf(44, 45), 45, false)
        }, repair = { body ->
            val id = body.path("assetId").asLong()
            calls.add(id)
            assertThat(body.path("dryRun").asBoolean()).isTrue()
            when (id) {
                43L -> 409 to mapOf("errorCode" to "IDENTITY_REJECTED")
                44L -> 500 to mapOf("error" to "Stored data preserved")
                45L -> 409 to mapOf("errorCode" to "ALREADY_RESOLVED")
                else -> 200 to success(body)
            }
        })
        assertThat(calls).containsExactly(42, 43, 44, 45)
        assertThat(events.map { it.path("status").asText() }).containsExactly("PREVIEWED", "REJECTED", "UNKNOWN", "ALREADY_RESOLVED")
        assertThat(summary.path("complete").asBoolean()).isTrue()
        assertThat(summary.path("discovered").asLong()).isEqualTo(4)
        assertThat(summary.path("unresolved").asLong()).isEqualTo(2)
        assertThat(summary.path("resumeAfterId").asLong()).isEqualTo(42)
        assertThat(queries.last()).contains("afterId=43&", "throughId=100")
    }

    @Test
    fun `apply mode and resume cursor are sent without requiring any asset IDs`() {
        val (summary, events, queries) = run(pages = { page(listOf(43), 43, false) }, repair = { body ->
            assertThat(body.path("dryRun").asBoolean()).isFalse()
            assertThat(body.path("expectedAids").map { it.asText() }).containsExactly("aid-a-43", "aid-b-43")
            200 to success(body)
        }, apply = true, after = 42)
        assertThat(queries.single()).contains("afterId=42&")
        assertThat(events.single().path("status").asText()).isEqualTo("REPAIRED")
        assertThat(summary.path("unresolved").asLong()).isZero()
        assertThat(summary.path("resumeAfterId").asLong()).isEqualTo(43)
    }

    @Test
    fun `unknown repair outcome preserves checkpoint and later candidates still run`() {
        val (summary, events) = run(pages = { page(listOf(42, 43), 43, false) }, repair = { body ->
            200 to if (body.path("assetId").asLong() == 42L) emptyMap<String, Any>() else success(body)
        })
        assertThat(events.map { it.path("status").asText() }).containsExactly("UNKNOWN", "PREVIEWED")
        assertThat(summary.path("resumeAfterId").asLong()).isZero()
        assertThat(summary.path("totals").path("UNKNOWN").asLong()).isEqualTo(1)
    }

    @Test
    fun `empty advancing pages are followed and no candidate result is fabricated`() {
        val (summary, events) = run(pages = { query ->
            if (query.contains("afterId=0&")) page(emptyList(), 42, true) else page(listOf(43), 43, false)
        }, repair = { 200 to success(it) })
        assertThat(events).hasSize(1)
        assertThat(summary.path("complete").asBoolean()).isTrue()
        assertThat(summary.path("resumeAfterId").asLong()).isEqualTo(43)
        val (empty, none) = run(pages = { page(emptyList(), 0, false) }, repair = { error("No repair expected") })
        assertThat(none).isEmpty()
        assertThat(empty.path("discovered").asLong()).isZero()
        assertThat(empty.path("complete").asBoolean()).isTrue()
    }

    @Test
    fun `nonadvancing pagination stops with an incomplete summary`() {
        val (summary, events, queries) = run(pages = { page(emptyList(), 0, true) }, repair = { error("No repair expected") })
        assertThat(summary.path("complete").asBoolean()).isFalse()
        assertThat(events).isEmpty()
        assertThat(queries).hasSize(1)
    }

    @Test
    fun `authorization loss stops further processing and retains the last safe checkpoint`() {
        val (summary, events) = run(pages = { page(listOf(42, 43), 43, true) }, repair = { body ->
            if (body.path("assetId").asLong() == 42L) 200 to success(body) else 401 to emptyMap<String, Any>()
        })
        assertThat(events.map { it.path("status").asText() }).containsExactly("PREVIEWED", "FAILED")
        assertThat(summary.path("complete").asBoolean()).isFalse()
        assertThat(summary.path("resumeAfterId").asLong()).isEqualTo(42)
    }

    @Test
    fun `explicit asset and automatic discovery modes are mutually exclusive`() {
        val cli = CommandLine(ReconcileCrowdStrikeIdentitiesCommand(mapper))
        cli.err = PrintWriter(StringWriter())
        assertThat(cli.execute("--all-candidates", "--asset-id", "42", "--reason", "Verified device recovery")).isEqualTo(2)
        assertThat(cli.execute("--all-candidates", "--batch-size", "101", "--reason", "Verified device recovery")).isEqualTo(2)
    }

    @Test
    fun `specific rejection code explanation and AID reach the candidate output`() {
        val (summary, events) = run(pages = { page(listOf(42), 42, false) }, repair = {
            409 to mapOf("errorCode" to "DEVICE_ACCOUNT_MISSING", "error" to "Falcon device has no AWS account ID", "aid" to "aid-b-42")
        })
        assertThat(events.single().path("errorCode").asText()).isEqualTo("DEVICE_ACCOUNT_MISSING")
        assertThat(events.single().path("message").asText()).isEqualTo("Falcon device has no AWS account ID")
        assertThat(events.single().path("aid").asText()).isEqualTo("aid-b-42")
        assertThat(summary.path("resumeAfterId").asLong()).isZero()
    }

    @Test
    fun `gateway timeout remains UNKNOWN while backend has no terminal result`() {
        val (summary, events) = run(pages = { page(listOf(42), 42, false) }, repair = { 504 to emptyMap<String, Any>() },
            lookup = { id, posted ->
                200 to mapOf("requestId" to id, "assetId" to posted.getValue(id).path("assetId").asLong(),
                    "dryRun" to true, "state" to "RUNNING", "httpStatus" to 202)
            })
        assertThat(events.single().path("status").asText()).isEqualTo("UNKNOWN")
        assertThat(events.single().path("requestId").asText()).isNotBlank()
        assertThat(summary.path("totals").path("FAILED").asLong()).isZero()
        assertThat(summary.path("resumeAfterId").asLong()).isZero()
    }

    @Test
    fun `timeout recovers a committed result by status lookup without resubmitting a write`() {
        var writes = 0
        val (summary, events) = run(pages = { page(listOf(42), 42, false) }, repair = {
            writes++
            504 to emptyMap<String, Any>()
        }, apply = true, lookup = { id, posted ->
            200 to mapOf("requestId" to id, "assetId" to 42, "dryRun" to false, "state" to "REPAIRED", "httpStatus" to 200,
                "report" to success(posted.getValue(id)))
        })
        assertThat(writes).isEqualTo(1)
        assertThat(events.single().path("status").asText()).isEqualTo("REPAIRED")
        assertThat(summary.path("unresolved").asLong()).isZero()
    }

    @Test
    fun `status lookup can confirm a failed request instead of guessing from gateway response`() {
        val (summary, events) = run(pages = { page(listOf(42), 42, false) }, repair = { 502 to emptyMap<String, Any>() },
            lookup = { id, _ -> 200 to mapOf("requestId" to id, "assetId" to 42, "dryRun" to true, "state" to "FAILED",
                "httpStatus" to 502, "errorCode" to "REPAIR_EXECUTION_FAILED", "message" to "Provider metadata lookup failed") })
        assertThat(events.single().path("status").asText()).isEqualTo("FAILED")
        assertThat(events.single().path("errorCode").asText()).isEqualTo("REPAIR_EXECUTION_FAILED")
        assertThat(summary.path("unresolved").asLong()).isEqualTo(1)
    }
}
