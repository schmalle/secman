package com.secman.testutil

import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.serde.annotation.Serdeable

/**
 * Helper for authentication in integration tests.
 * Feature: 056-test-suite
 *
 * Provides JWT token generation for test users to make authenticated requests.
 */
object TestAuthHelper {

    @Serdeable
    data class LoginRequest(
        val username: String,
        val password: String
    )

    @Serdeable
    data class LoginResponse(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>,
        val token: String
    )

    /**
     * Obtain a JWT token for the given user credentials.
     *
     * @param client HttpClient connected to the test server
     * @param username Username to authenticate
     * @param password Password (defaults to TestDataFactory.DEFAULT_PASSWORD)
     * @return JWT token string for use in Authorization header
     * @throws HttpClientResponseException if authentication fails
     */
    fun getAuthToken(
        client: HttpClient,
        username: String,
        password: String = TestDataFactory.DEFAULT_PASSWORD
    ): String {
        val request = HttpRequest.POST(
            "/api/auth/login",
            LoginRequest(username, password)
        )

        val response = client.toBlocking().exchange(request, LoginResponse::class.java)
        return response.body()?.token
            ?: throw IllegalStateException("Login successful but no token returned")
    }

    /**
     * Attempt login and expect failure.
     *
     * @param client HttpClient connected to the test server
     * @param username Username to authenticate
     * @param password Password to use
     * @return HttpClientResponseException containing the error response
     */
    fun attemptLoginExpectingFailure(
        client: HttpClient,
        username: String,
        password: String
    ): HttpClientResponseException {
        val request = HttpRequest.POST(
            "/api/auth/login",
            LoginRequest(username, password)
        )

        return try {
            client.toBlocking().exchange(request, LoginResponse::class.java)
            throw AssertionError("Expected login to fail but it succeeded")
        } catch (e: HttpClientResponseException) {
            e
        }
    }

    /**
     * Create an authenticated HTTP request with Bearer token.
     *
     * @param method HTTP method
     * @param uri Request URI
     * @param body Request body (optional)
     * @param token JWT token
     * @return HttpRequest with Authorization header set
     */
    fun <T> authenticatedRequest(
        method: io.micronaut.http.HttpMethod,
        uri: String,
        body: T?,
        token: String
    ): HttpRequest<T> {
        val request = when (method) {
            io.micronaut.http.HttpMethod.GET -> HttpRequest.GET<T>(uri)
            io.micronaut.http.HttpMethod.POST -> HttpRequest.POST(uri, body)
            io.micronaut.http.HttpMethod.PUT -> HttpRequest.PUT(uri, body)
            io.micronaut.http.HttpMethod.DELETE -> HttpRequest.DELETE<T>(uri)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }
        return request.bearerAuth(token) as HttpRequest<T>
    }
}
