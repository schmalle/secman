package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class GithubAppClientServiceTest {

    private val mapper = ObjectMapper()
    private val client = GithubAppClientService(mapper)

    @Test
    fun `parseAlert maps a full GitHub Dependabot alert JSON node`() {
        val json = """
            {
              "number": 3,
              "security_advisory": {
                "severity": "high",
                "ghsa_id": "GHSA-xxxx-yyyy-zzzz",
                "cve_id": "CVE-2024-12345",
                "summary": "Prototype pollution in lodash"
              },
              "security_vulnerability": {
                "vulnerable_version_range": "< 4.17.21",
                "first_patched_version": { "identifier": "4.17.21" }
              },
              "dependency": {
                "package": { "name": "lodash", "ecosystem": "npm" },
                "manifest_path": "package.json"
              },
              "html_url": "https://github.com/org/repo/security/dependabot/3",
              "created_at": "2026-01-01T00:00:00Z",
              "updated_at": "2026-01-02T00:00:00Z"
            }
        """.trimIndent()
        val node = mapper.readTree(json)

        val alert = client.parseAlert(node, "high")

        assertThat(alert.alertNumber).isEqualTo(3)
        assertThat(alert.packageName).isEqualTo("lodash")
        assertThat(alert.ecosystem).isEqualTo("npm")
        assertThat(alert.manifestPath).isEqualTo("package.json")
        assertThat(alert.severity).isEqualTo("high")
        assertThat(alert.ghsaId).isEqualTo("GHSA-xxxx-yyyy-zzzz")
        assertThat(alert.cveId).isEqualTo("CVE-2024-12345")
        assertThat(alert.summary).isEqualTo("Prototype pollution in lodash")
        assertThat(alert.vulnerableVersionRange).isEqualTo("< 4.17.21")
        assertThat(alert.firstPatchedVersion).isEqualTo("4.17.21")
        assertThat(alert.htmlUrl).isEqualTo("https://github.com/org/repo/security/dependabot/3")
        assertThat(alert.alertCreatedAt.toString()).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(alert.alertUpdatedAt.toString()).isEqualTo("2026-01-02T00:00:00Z")
    }

    @Test
    fun `parseAlert tolerates missing optional fields`() {
        val json = """
            {
              "number": 9,
              "security_advisory": { "severity": "critical" },
              "security_vulnerability": {},
              "dependency": { "package": { "name": "left-pad", "ecosystem": "npm" } }
            }
        """.trimIndent()
        val node = mapper.readTree(json)

        val alert = client.parseAlert(node, "critical")

        assertThat(alert.alertNumber).isEqualTo(9)
        assertThat(alert.packageName).isEqualTo("left-pad")
        assertThat(alert.manifestPath).isNull()
        assertThat(alert.ghsaId).isNull()
        assertThat(alert.cveId).isNull()
        assertThat(alert.vulnerableVersionRange).isNull()
        assertThat(alert.firstPatchedVersion).isNull()
        assertThat(alert.alertCreatedAt).isNull()
    }

    /**
     * Reproduces the real GitHub behavior change: `page=` on the Dependabot
     * alerts endpoint now returns HTTP 400 ("Pagination using the `page`
     * parameter is not supported"); only `Link: rel="next"` cursor
     * pagination works. Fails against the old page-based implementation.
     */
    @Test
    fun `countOpenDependabotAlerts follows Link header pagination instead of a page query parameter`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestCount = AtomicInteger(0)
        server.createContext("/repos/owner/repo/dependabot/alerts") { exchange ->
            val queryParams = (exchange.requestURI.query ?: "").split("&").map { it.substringBefore("=") }
            val status: Int
            val body: String
            val linkHeader: String?
            if ("page" in queryParams) {
                status = 400
                body = """{"message":"Pagination using the `page` parameter is not supported.","status":"400"}"""
                linkHeader = null
            } else if (requestCount.incrementAndGet() == 1) {
                status = 200
                body = """[{"number":1,"security_advisory":{"severity":"critical"},"security_vulnerability":{},"dependency":{"package":{"name":"a","ecosystem":"npm"}}}]"""
                linkHeader = "<http://127.0.0.1:${exchange.localAddress.port}/repos/owner/repo/dependabot/alerts?state=open&per_page=100&after=CURSOR1>; rel=\"next\""
            } else {
                status = 200
                body = """[{"number":2,"security_advisory":{"severity":"high"},"security_vulnerability":{},"dependency":{"package":{"name":"b","ecosystem":"npm"}}}]"""
                linkHeader = null
            }
            linkHeader?.let { exchange.responseHeaders.add("Link", it) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val testClient = object : GithubAppClientService(mapper) {
                override val apiBaseUrl: String = "http://127.0.0.1:${server.address.port}"
            }

            val counts = testClient.countOpenDependabotAlerts("token", "owner", "repo")

            assertThat(counts.disabled).isFalse()
            assertThat(counts.critical).isEqualTo(1)
            assertThat(counts.high).isEqualTo(1)
            assertThat(counts.alerts).hasSize(2)
            assertThat(requestCount.get()).isEqualTo(2)
        } finally {
            server.stop(0)
        }
    }
}
