package com.secman.controller

import com.secman.dto.UserMappingResponse
import com.secman.dto.CreateUserMappingRequest
import com.secman.dto.UpdateUserMappingRequest
import com.secman.service.UserMappingService
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

@MicronautTest
class UserControllerMappingTest {
    
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient
    
    private lateinit var userMappingService: UserMappingService
    
    @BeforeEach
    fun setup() {
        userMappingService = mockk()
    }
    
    @Test
    fun `GET users-userId-mappings returns 200 with mappings array`() {
        // Given
        val userId = 1L
        val mappings = listOf(
            UserMappingResponse(
                1, 
                "user@example.com", 
                "123456789012", 
                null, 
                "2025-01-01T00:00:00Z", 
                "2025-01-01T00:00:00Z"
            )
        )
        every { userMappingService.getUserMappings(userId) } returns mappings
        
        // When
        val request = HttpRequest.GET<List<UserMappingResponse>>("/api/users/$userId/mappings")
        val response = client.toBlocking().exchange(request, List::class.java)
        
        // Then
        assertEquals(HttpStatus.OK, response.status)
    }
    
    @Test
    fun `GET returns empty array for user with no mappings`() {
        // Given
        val userId = 1L
        every { userMappingService.getUserMappings(userId) } returns emptyList()
        
        // When
        val request = HttpRequest.GET<List<UserMappingResponse>>("/api/users/$userId/mappings")
        val response = client.toBlocking().exchange(request, List::class.java)
        
        // Then
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }
    
    @Test
    fun `GET returns 404 for non-existent user`() {
        // Given
        val userId = 999L
        every { userMappingService.getUserMappings(userId) } throws NoSuchElementException("User not found")
        
        // When/Then
        val request = HttpRequest.GET<Any>("/api/users/$userId/mappings")
        try {
            client.toBlocking().exchange(request, Any::class.java)
            fail("Expected exception to be thrown")
        } catch (e: Exception) {
            // Expected - endpoint should return error
        }
    }
    
    @Test
    fun `POST creates mapping and returns 201`() {
        // Given
        val userId = 1L
        val createRequest = CreateUserMappingRequest(
            awsAccountId = "123456789012",
            domain = null
        )
        val createdMapping = UserMappingResponse(
            1,
            "user@example.com",
            "123456789012",
            null,
            "2025-01-01T00:00:00Z",
            "2025-01-01T00:00:00Z"
        )
        every { userMappingService.createMapping(userId, createRequest) } returns createdMapping
        
        // When
        val request = HttpRequest.POST("/api/users/$userId/mappings", createRequest)
        val response = client.toBlocking().exchange(request, UserMappingResponse::class.java)
        
        // Then
        assertEquals(HttpStatus.CREATED, response.status)
        assertEquals(createdMapping, response.body())
    }
    
    @Test
    fun `POST returns 400 when validation fails`() {
        // Given
        val userId = 1L
        val invalidRequest = CreateUserMappingRequest(
            awsAccountId = null,
            domain = null
        )
        every { userMappingService.createMapping(userId, invalidRequest) } throws 
            IllegalArgumentException("At least one field must be provided")
        
        // When/Then
        val request = HttpRequest.POST("/api/users/$userId/mappings", invalidRequest)
        try {
            client.toBlocking().exchange(request, Any::class.java)
            fail("Expected exception to be thrown")
        } catch (e: Exception) {
            // Expected - endpoint should return validation error
        }
    }
    
    @Test
    fun `PUT updates mapping and returns 200`() {
        // Given
        val userId = 1L
        val mappingId = 1L
        val updateRequest = UpdateUserMappingRequest(
            awsAccountId = "999999999999",
            domain = "updated.example.com"
        )
        val updatedMapping = UserMappingResponse(
            1,
            "user@example.com",
            "999999999999",
            "updated.example.com",
            "2025-01-01T00:00:00Z",
            "2025-01-02T00:00:00Z"
        )
        every { userMappingService.updateMapping(userId, mappingId, updateRequest) } returns updatedMapping
        
        // When
        val request = HttpRequest.PUT("/api/users/$userId/mappings/$mappingId", updateRequest)
        val response = client.toBlocking().exchange(request, UserMappingResponse::class.java)
        
        // Then
        assertEquals(HttpStatus.OK, response.status)
        assertEquals(updatedMapping, response.body())
    }
    
    @Test
    fun `PUT returns 404 when mapping not found`() {
        // Given
        val userId = 1L
        val mappingId = 999L
        val updateRequest = UpdateUserMappingRequest(
            awsAccountId = "999999999999",
            domain = null
        )
        every { userMappingService.updateMapping(userId, mappingId, updateRequest) } throws 
            NoSuchElementException("Mapping not found")
        
        // When/Then
        val request = HttpRequest.PUT("/api/users/$userId/mappings/$mappingId", updateRequest)
        try {
            client.toBlocking().exchange(request, Any::class.java)
            fail("Expected exception to be thrown")
        } catch (e: Exception) {
            // Expected - endpoint should return not found error
        }
    }
    
    @Test
    fun `PUT returns 403 when mapping belongs to different user`() {
        // Given
        val userId = 1L
        val mappingId = 1L
        val updateRequest = UpdateUserMappingRequest(
            awsAccountId = "999999999999",
            domain = null
        )
        every { userMappingService.updateMapping(userId, mappingId, updateRequest) } throws 
            IllegalArgumentException("Mapping does not belong to this user")
        
        // When/Then
        val request = HttpRequest.PUT("/api/users/$userId/mappings/$mappingId", updateRequest)
        try {
            client.toBlocking().exchange(request, Any::class.java)
            fail("Expected exception to be thrown")
        } catch (e: Exception) {
            // Expected - endpoint should return forbidden error
        }
    }
    
    @Test
    fun `DELETE removes mapping and returns 204`() {
        // Given
        val userId = 1L
        val mappingId = 1L
        every { userMappingService.deleteMapping(userId, mappingId) } returns Unit
        
        // When
        val request = HttpRequest.DELETE<Any>("/api/users/$userId/mappings/$mappingId")
        val response = client.toBlocking().exchange(request, Any::class.java)
        
        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.status)
        verify(exactly = 1) { userMappingService.deleteMapping(userId, mappingId) }
    }
    
    @Test
    fun `DELETE returns 404 when mapping not found`() {
        // Given
        val userId = 1L
        val mappingId = 999L
        every { userMappingService.deleteMapping(userId, mappingId) } throws 
            NoSuchElementException("Mapping not found")
        
        // When/Then
        val request = HttpRequest.DELETE<Any>("/api/users/$userId/mappings/$mappingId")
        try {
            client.toBlocking().exchange(request, Any::class.java)
            fail("Expected exception to be thrown")
        } catch (e: Exception) {
            // Expected - endpoint should return not found error
        }
    }
}
