package com.secman.cli.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.DefaultHttpClientConfiguration
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.netty.DefaultHttpClient
import jakarta.inject.Singleton
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

@Singleton
class UserAccessOverviewCliService(private val mapper: ObjectMapper) {
    class ReportFailure(val exitCode: Int, message: String) : RuntimeException(message)

    fun fetch(baseUrl: URI, username: String, password: String, email: String, size: Int): ObjectNode {
        val config = DefaultHttpClientConfiguration().apply {
            setConnectTimeout(Duration.ofSeconds(30))
            setReadTimeout(Duration.ofSeconds(120))
            setFollowRedirects(false)
        }
        @Suppress("DEPRECATION")
        DefaultHttpClient(baseUrl, config).use { client ->
            val auth = CliHttpClient(client, mapper).authenticate(username, password, baseUrl.toString())
                ?: throw ReportFailure(3, "Authentication failed; check credentials, MFA, and backend connectivity")
            val encoded = URLEncoder.encode(email, StandardCharsets.UTF_8)
            fun read(page: Int): ObjectNode {
                val request = HttpRequest.GET<Any>("/api/users/access-overview?email=$encoded&page=$page&size=$size")
                    .bearerAuth(auth).accept(MediaType.APPLICATION_JSON)
                val body = try {
                    client.toBlocking().retrieve(request, String::class.java)
                } catch (e: HttpClientResponseException) {
                    throw when (e.status.code) {
                        400 -> ReportFailure(2, "Invalid email address or pagination")
                        401, 403 -> ReportFailure(3, "Access denied: an authenticated ADMIN caller is required")
                        404 -> ReportFailure(4, "User or access-overview endpoint not found")
                        else -> ReportFailure(4, "Backend request failed (HTTP ${e.status.code})")
                    }
                }
                return mapper.readTree(body) as? ObjectNode ?: throw ReportFailure(4, "Invalid report response")
            }
            val report = read(0)
            val allAssets = mapper.createArrayNode()
            val total = report.path("totalAssets").asLong(-1)
            val pages = report.path("totalPages").asInt(-1)
            if (total < 0 || pages < 0 || pages.toLong() != (total + size - 1) / size) {
                throw ReportFailure(4, "Invalid report pagination")
            }
            val seen = mutableSetOf<Long>()
            for (page in 0 until maxOf(1, pages)) {
                val next = if (page == 0) report else read(page)
                for (key in listOf("user", "vulnerabilityAccess", "globalAccess", "totalAssets", "totalPages", "awsAccounts", "adDomains")) {
                    if (next[key] != report[key]) throw ReportFailure(4, "Access scope changed during reporting; rerun the command")
                }
                val assets = next.path("assets")
                if (!assets.isArray || next.path("page").asInt(-1) != page || next.path("size").asInt(-1) != size) {
                    throw ReportFailure(4, "Invalid report page")
                }
                for (asset in assets) {
                    if (!seen.add(asset.path("id").asLong(-1))) throw ReportFailure(4, "Duplicate asset across pages; rerun the command")
                    allAssets.add(asset)
                }
            }
            if (allAssets.size().toLong() != total) throw ReportFailure(4, "Incomplete report; rerun the command")
            report.set<JsonNode>("assets", allAssets)
            report.remove(listOf("page", "size", "totalPages"))
            report.put("complete", true)
            return report
        }
    }
}
