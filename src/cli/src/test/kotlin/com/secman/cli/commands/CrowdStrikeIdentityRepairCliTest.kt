package com.secman.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.cli.service.BatchStorageResult
import com.secman.crowdstrike.dto.CrowdStrikeHostImportOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter

class CrowdStrikeIdentityRepairCliTest {
    @Test
    fun `repair defaults to preview and apply must be explicit`() {
        val command = ReconcileCrowdStrikeIdentitiesCommand(jacksonObjectMapper())
        val cli = CommandLine(command)
        cli.parseArgs("--asset-id", "42", "--expected-aids", "aid-a,aid-b", "--reason", "Verified separate devices")
        assertThat(command.apply).isFalse()
        assertThat(command.aids).containsExactlyInAnyOrder("aid-a", "aid-b")
        cli.parseArgs("--asset-id", "42", "--expected-aids", "aid-a,aid-b", "--reason", "Verified separate devices", "--apply")
        assertThat(command.apply).isTrue()
    }

    @Test
    fun `malformed repair fails before network access`() {
        val cli = CommandLine(ReconcileCrowdStrikeIdentitiesCommand(jacksonObjectMapper()))
        cli.err = PrintWriter(StringWriter())
        assertThat(cli.execute("--asset-id", "0", "--expected-aids", "one", "--reason", "invalid")).isEqualTo(2)
    }

    @Test
    fun `age statistics require every device to commit including zero finding devices`() {
        val success = BatchStorageResult(2, 1, 1, 0, 0, 0, emptyList())
        assertThat(ServersCommand.canCaptureAgeSnapshot(success, 0)).isTrue()
        assertThat(ServersCommand.canCaptureAgeSnapshot(success, 1)).isFalse()
        assertThat(ServersCommand.canCaptureAgeSnapshot(success.copy(serversCreated = 0, serversUpdated = 0,
            errors = listOf("device-a failed", "device-b failed")), 0)).isFalse()
        assertThat(ServersCommand.canCaptureAgeSnapshot(success.copy(serversUpdated = 0,
            hostOutcomes = listOf(CrowdStrikeHostImportOutcome("same", "SKIPPED_RETENTION"))), 0)).isFalse()
    }

    @Test
    fun `HTTP request binds expected devices and preview apply mode and rejects incomplete responses`() {
        val mapper = jacksonObjectMapper()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val token = java.util.UUID.randomUUID().toString()
        var malformed = false
        val received = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        server.createContext("/api/auth/login") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "secman_auth=$token; HttpOnly; Path=/")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.createContext("/api/crowdstrike/identity-reconcile") { exchange ->
            if (exchange.requestMethod == "GET") {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            assertThat(exchange.requestHeaders.getFirst("Authorization")).isEqualTo("Bearer $token")
            val body = mapper.readTree(exchange.requestBody)
            received.add(body)
            val response = if (malformed) "{}" else mapper.writeValueAsString(mapOf("assetId" to 42,
                "dryRun" to body.path("dryRun").asBoolean(), "devices" to listOf(
                    mapOf("aid" to "aid-a", "assetId" to 42), mapOf("aid" to "aid-b", "assetId" to 43))))
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val command = ReconcileCrowdStrikeIdentitiesCommand(mapper).apply {
                assetId = 42; aids = setOf("aid-a", "aid-b"); reason = "Verified independent devices"
            }
            val origin = java.net.URI("http://127.0.0.1:${server.address.port}")
            val password = java.util.UUID.randomUUID().toString()
            assertThat(command.submit(origin, "admin", password).path("dryRun").asBoolean()).isTrue()
            command.apply = true
            assertThat(command.submit(origin, "admin", password).path("dryRun").asBoolean()).isFalse()
            assertThat(received.map { it.path("expectedAids").map { aid -> aid.asText() }.toSet() })
                .allMatch { it == setOf("aid-a", "aid-b") }
            malformed = true
            org.junit.jupiter.api.Assertions.assertThrows(com.secman.cli.service.RepairRequestUnknown::class.java) { command.submit(origin, "admin", password) }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `request status accepts only UUID and cannot be combined with apply`() {
        val command = ReconcileCrowdStrikeIdentitiesCommand(jacksonObjectMapper())
        val cli = CommandLine(command)
        cli.err = PrintWriter(StringWriter())
        assertThat(cli.execute("--request-status", "invalid")).isEqualTo(2)
        assertThat(cli.execute("--request-status", java.util.UUID.randomUUID().toString(), "--apply")).isEqualTo(2)
    }
}
