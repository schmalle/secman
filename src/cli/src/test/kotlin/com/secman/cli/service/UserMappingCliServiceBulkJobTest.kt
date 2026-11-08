package com.secman.cli.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections

/**
 * The bulk import must survive a proxy that cannot hold one request open for the whole
 * import: the CLI starts a backend job and polls it. These tests drive the CLI against a
 * loopback stub backend.
 */
class UserMappingCliServiceBulkJobTest {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val service = UserMappingCliService(UserMappingValidator(), CliJavaHttpClientFactory(), io.mockk.mockk())
        .apply { bulkJobPollInterval = Duration.ofMillis(10) }
    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    private val resultJson = """{"totalProcessed":1,"created":0,"createdPending":0,"skipped":1,"errors":[]}"""

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun route(path: String, handler: (HttpExchange) -> Pair<Int, String>) {
        server.createContext(path) { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val (code, body) = handler(exchange)
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    private fun import() = service.addDomainMappings(listOf("owner@example.com"), listOf("example.com"), baseUrl, "token")

    @Test
    fun `starts a job, rides out proxy errors while polling, and returns the job result`() {
        var polls = 0
        route("/api/user-mappings/bulk/jobs") { ex ->
            if (ex.requestURI.path.endsWith("/status")) {
                polls++
                when (polls) {
                    1 -> 200 to """{"jobId":"j1","status":"RUNNING"}"""
                    2 -> 504 to "<html>504 Gateway Time-out</html>"
                    else -> 200 to """{"jobId":"j1","status":"COMPLETED","result":$resultJson}"""
                }
            } else {
                202 to """{"jobId":"j1","status":"PENDING"}"""
            }
        }
        server.start()

        val result = import()

        assertEquals(1, result.skipped)
        assertEquals(1, result.totalProcessed)
        assertEquals("POST /api/user-mappings/bulk/jobs", requests.first())
        assertTrue(requests.none { it == "POST /api/user-mappings/bulk" }, "must not also run the synchronous import")
    }

    @Test
    fun `falls back to the synchronous endpoint on a backend without jobs`() {
        route("/api/user-mappings/bulk") { ex ->
            if (ex.requestURI.path == "/api/user-mappings/bulk") 200 to resultJson else 404 to ""
        }
        server.start()

        assertEquals(1, import().skipped)
        assertEquals(listOf("POST /api/user-mappings/bulk/jobs", "POST /api/user-mappings/bulk"), requests.toList())
    }

    @Test
    fun `failed job surfaces the backend message`() {
        route("/api/user-mappings/bulk/jobs") { ex ->
            if (ex.requestURI.path.endsWith("/status")) {
                200 to """{"jobId":"j1","status":"FAILED","errorMessage":"Saved onboarding configuration is not ready"}"""
            } else {
                202 to """{"jobId":"j1","status":"PENDING"}"""
            }
        }
        server.start()

        val failure = assertThrows<IllegalArgumentException> { import() }
        assertEquals("Saved onboarding configuration is not ready", failure.message)
    }

    @Test
    fun `lost job and concurrent import are reported, not retried`() {
        route("/api/user-mappings/bulk/jobs") { ex ->
            if (ex.requestURI.path.endsWith("/status")) 404 to "" else 202 to """{"jobId":"j1","status":"PENDING"}"""
        }
        server.start()
        assertTrue(assertThrows<IllegalArgumentException> { import() }.message!!.contains("re-run the import"))

        server.removeContext("/api/user-mappings/bulk/jobs")
        route("/api/user-mappings/bulk/jobs") { 409 to """{"error":"Conflict","jobId":"j0"}""" }
        assertTrue(assertThrows<IllegalArgumentException> { import() }.message!!.contains("job j0"))
    }
}
