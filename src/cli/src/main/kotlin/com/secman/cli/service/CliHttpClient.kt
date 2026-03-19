package com.secman.cli.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Base HTTP client for CLI operations.
 * Provides authentication and common request/response patterns
 * for all CLI commands that communicate with the backend API.
 */
@Singleton
class CliHttpClient(
    @Client("\${secman.backend.base-url:http://localhost:8080}")
    private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(CliHttpClient::class.java)

    /**
     * Authenticate with backend API and get JWT token
     */
    fun authenticate(username: String, password: String, backendUrl: String): String? {
        try {
            val endpoint = "$backendUrl/api/auth/login"
            val request = HttpRequest.POST(endpoint, mapOf(
                "username" to username,
                "password" to password
            )).contentType(MediaType.APPLICATION_JSON)

            val response: HttpResponse<Map<*, *>> = httpClient.toBlocking()
                .exchange(request, Map::class.java)

            if (response.status.code == 200) {
                val body = response.body()
                val token = body?.get("access_token")?.toString()
                    ?: body?.get("token")?.toString()
                    ?: body?.get("accessToken")?.toString()

                if (token != null) {
                    log.info("Successfully authenticated with backend")
                    return token
                }
            }

            log.error("Authentication failed: status={}", response.status)
            return null
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            log.error("Authentication error: {} - {}", e.status.code, e.message)
            return null
        } catch (e: Exception) {
            log.error("Authentication error: {}", e.message, e)
            return null
        }
    }

    /**
     * Send a GET request with JWT authentication
     */
    fun <T> get(url: String, authToken: String, responseType: Class<T>): T? {
        val request = HttpRequest.GET<Any>(url)
            .header("Authorization", "Bearer $authToken")
            .accept(MediaType.APPLICATION_JSON)

        return try {
            val response = httpClient.toBlocking().exchange(request, responseType)
            response.body()
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            log.error("GET {} failed: {} - {}", url, e.status.code, e.message)
            null
        } catch (e: Exception) {
            log.error("GET {} error: {}", url, e.message)
            null
        }
    }

    /**
     * Send a GET request returning a Map
     */
    fun getMap(url: String, authToken: String): Map<*, *>? {
        return get(url, authToken, Map::class.java)
    }

    /**
     * Send a POST request with JWT authentication and JSON body
     */
    fun postMap(url: String, body: Any, authToken: String): Map<*, *>? {
        val request = HttpRequest.POST(url, body)
            .header("Authorization", "Bearer $authToken")
            .contentType(MediaType.APPLICATION_JSON)

        return try {
            val response = httpClient.toBlocking().exchange(request, Map::class.java)
            response.body()
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            log.error("POST {} failed: {} - {}", url, e.status.code, e.message)
            null
        } catch (e: Exception) {
            log.error("POST {} error: {}", url, e.message)
            null
        }
    }

    /**
     * Send a POST request returning raw string body
     */
    fun postString(url: String, body: Any, authToken: String): String? {
        val request = HttpRequest.POST(url, body)
            .header("Authorization", "Bearer $authToken")
            .contentType(MediaType.APPLICATION_JSON)

        return try {
            val response = httpClient.toBlocking().exchange(request, String::class.java)
            response.body()
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            log.error("POST {} failed: {} - {}", url, e.status.code, e.message)
            null
        } catch (e: Exception) {
            log.error("POST {} error: {}", url, e.message)
            null
        }
    }

    /**
     * Send a GET request returning a list of maps (for array responses)
     */
    @Suppress("UNCHECKED_CAST")
    fun getList(url: String, authToken: String): List<Map<String, Any?>>? {
        val request = HttpRequest.GET<Any>(url)
            .header("Authorization", "Bearer $authToken")
            .accept(MediaType.APPLICATION_JSON)

        return try {
            val response = httpClient.toBlocking().exchange(request, String::class.java)
            val body = response.body() ?: return null
            objectMapper.readValue(body, List::class.java) as? List<Map<String, Any?>>
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            log.error("GET {} failed: {} - {}", url, e.status.code, e.message)
            null
        } catch (e: Exception) {
            log.error("GET {} error: {}", url, e.message)
            null
        }
    }

    fun getObjectMapper(): ObjectMapper = objectMapper
}
