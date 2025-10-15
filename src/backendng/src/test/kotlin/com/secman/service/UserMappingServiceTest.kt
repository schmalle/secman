package com.secman.service

import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.dto.CreateUserMappingRequest
import com.secman.dto.UpdateUserMappingRequest
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

@MicronautTest
class UserMappingServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var userMappingRepository: UserMappingRepository
    private lateinit var ipAddressParser: IpAddressParser
    private lateinit var userMappingService: UserMappingService

    @BeforeEach
    fun setup() {
        userRepository = mockk()
        userMappingRepository = mockk()
        ipAddressParser = IpAddressParser()  // Use real parser for IP tests
        userMappingService = UserMappingService(userRepository, userMappingRepository, ipAddressParser)
    }

    @Test
    fun `getUserMappings returns mappings for valid user`() {
        // Given
        val userId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        
        val mapping = UserMapping().apply {
            id = 1L
            email = "user@example.com"
            awsAccountId = "123456789012"
            domain = null
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { userMappingRepository.findByEmail("user@example.com") } returns listOf(mapping)

        // When
        val result = userMappingService.getUserMappings(userId)

        // Then
        assertEquals(1, result.size)
        assertEquals("user@example.com", result[0].email)
        assertEquals("123456789012", result[0].awsAccountId)
        verify { userRepository.findById(userId) }
        verify { userMappingRepository.findByEmail("user@example.com") }
    }

    @Test
    fun `getUserMappings throws exception for non-existent user`() {
        // Given
        val userId = 999L
        every { userRepository.findById(userId) } returns Optional.empty()

        // When/Then
        assertThrows<NoSuchElementException> {
            userMappingService.getUserMappings(userId)
        }
        verify { userRepository.findById(userId) }
    }

    @Test
    fun `createMapping validates at least one field provided`() {
        // Given
        val userId = 1L
        val request = CreateUserMappingRequest(null, null)

        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            userMappingService.createMapping(userId, request)
        }
        assertTrue(exception.message!!.contains("at least one", ignoreCase = true))
    }

    @Test
    fun `createMapping detects duplicate mappings`() {
        // Given
        val userId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val request = CreateUserMappingRequest("123456789012", null)
        
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { 
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                "user@example.com", "123456789012", null
            ) 
        } returns true

        // When/Then
        val exception = assertThrows<IllegalStateException> {
            userMappingService.createMapping(userId, request)
        }
        assertTrue(exception.message!!.contains("already exists", ignoreCase = true))
        verify { userRepository.findById(userId) }
        verify { 
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                "user@example.com", "123456789012", null
            )
        }
    }

    @Test
    fun `createMapping succeeds with valid data`() {
        // Given
        val userId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val request = CreateUserMappingRequest("123456789012", null)
        val savedMapping = UserMapping().apply {
            id = 1L
            email = "user@example.com"
            awsAccountId = "123456789012"
            domain = null
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { 
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(any(), any(), any()) 
        } returns false
        every { userMappingRepository.save(any<UserMapping>()) } returns savedMapping

        // When
        val result = userMappingService.createMapping(userId, request)

        // Then
        assertEquals("user@example.com", result.email)
        assertEquals("123456789012", result.awsAccountId)
        verify { userRepository.findById(userId) }
        verify { userMappingRepository.save(any<UserMapping>()) }
    }

    @Test
    fun `updateMapping validates at least one field provided`() {
        // Given
        val userId = 1L
        val mappingId = 1L
        val request = UpdateUserMappingRequest(null, null)

        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            userMappingService.updateMapping(userId, mappingId, request)
        }
        assertTrue(exception.message!!.contains("at least one", ignoreCase = true))
    }

    @Test
    fun `updateMapping detects duplicate mappings excluding current`() {
        // Given
        val userId = 1L
        val mappingId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val existingMapping = UserMapping().apply {
            id = mappingId
            email = "user@example.com"
            awsAccountId = "111111111111"
            domain = null
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        val request = UpdateUserMappingRequest("222222222222", null)
        
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { userMappingRepository.findById(mappingId) } returns Optional.of(existingMapping)
        every { 
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                "user@example.com", "222222222222", null
            ) 
        } returns true

        // When/Then
        val exception = assertThrows<IllegalStateException> {
            userMappingService.updateMapping(userId, mappingId, request)
        }
        assertTrue(exception.message!!.contains("already exists", ignoreCase = true))
    }

    @Test
    fun `updateMapping verifies mapping belongs to user`() {
        // Given
        val userId = 1L
        val mappingId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val mapping = UserMapping().apply {
            id = mappingId
            email = "other@example.com"  // Different email!
            awsAccountId = "123456789012"
            domain = null
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        val request = UpdateUserMappingRequest("111111111111", null)
        
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { userMappingRepository.findById(mappingId) } returns Optional.of(mapping)

        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            userMappingService.updateMapping(userId, mappingId, request)
        }
        assertTrue(exception.message!!.contains("does not belong", ignoreCase = true) || 
                   exception.message!!.contains("mapping", ignoreCase = true))
    }

    @Test
    fun `updateMapping succeeds with valid data`() {
        // Given
        val userId = 1L
        val mappingId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val existingMapping = UserMapping().apply {
            id = mappingId
            email = "user@example.com"
            awsAccountId = "111111111111"
            domain = null
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        val request = UpdateUserMappingRequest("222222222222", null)
        val updatedMapping = UserMapping().apply {
            id = mappingId
            email = "user@example.com"
            awsAccountId = "222222222222"
            domain = null
            createdAt = existingMapping.createdAt
            updatedAt = Instant.now()
        }
        
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { userMappingRepository.findById(mappingId) } returns Optional.of(existingMapping)
        every { 
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(any(), any(), any()) 
        } returns false
        every { userMappingRepository.update(any<UserMapping>()) } returns updatedMapping

        // When
        val result = userMappingService.updateMapping(userId, mappingId, request)

        // Then
        assertEquals("user@example.com", result.email)
        assertEquals("222222222222", result.awsAccountId)
        verify { userRepository.findById(userId) }
        verify { userMappingRepository.update(any<UserMapping>()) }
    }

    @Test
    fun `deleteMapping verifies mapping belongs to user`() {
        // Given
        val userId = 1L
        val mappingId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val mapping = UserMapping().apply {
            id = mappingId
            email = "other@example.com"  // Different email!
            awsAccountId = "123456789012"
            domain = null
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { userMappingRepository.findById(mappingId) } returns Optional.of(mapping)

        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            userMappingService.deleteMapping(userId, mappingId)
        }
        assertTrue(exception.message!!.contains("does not belong", ignoreCase = true) || 
                   exception.message!!.contains("mapping", ignoreCase = true))
    }

    @Test
    fun `deleteMapping succeeds`() {
        // Given
        val userId = 1L
        val mappingId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val mapping = UserMapping().apply {
            id = mappingId
            email = "user@example.com"
            awsAccountId = "123456789012"
            domain = null
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { userMappingRepository.findById(mappingId) } returns Optional.of(mapping)
        every { userMappingRepository.delete(any<UserMapping>()) } returns Unit

        // When
        val result = userMappingService.deleteMapping(userId, mappingId)

        // Then
        assertTrue(result)
        verify { userRepository.findById(userId) }
        verify { userMappingRepository.delete(mapping) }
    }

    // ========== IP Address Mapping Tests (Feature 020) ==========

    @Test
    fun `createMapping with single IP address parses and saves correctly`() {
        // Given
        val userId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val request = CreateUserMappingRequest(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com",
            ipAddress = "192.168.1.100"
        )
        val savedMapping = UserMapping(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com"
        ).apply {
            id = 1L
            ipAddress = "192.168.1.100"
            ipRangeType = com.secman.domain.IpRangeType.SINGLE
            ipRangeStart = 3232235876L  // 192.168.1.100 in numeric form
            ipRangeEnd = 3232235876L
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }

        every { userRepository.findById(userId) } returns Optional.of(user)
        every {
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(any(), any(), any())
        } returns false
        every { userMappingRepository.save(any<UserMapping>()) } returns savedMapping

        // When
        val result = userMappingService.createMapping(userId, request)

        // Then
        assertEquals("user@example.com", result.email)
        assertEquals("192.168.1.100", result.ipAddress)
        assertEquals("SINGLE", result.ipRangeType.toString())
        assertEquals(1L, result.ipCount)
        verify { userMappingRepository.save(any<UserMapping>()) }
    }

    @Test
    fun `createMapping with CIDR range parses correctly`() {
        // Given
        val userId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val request = CreateUserMappingRequest(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com",
            ipAddress = "10.0.0.0/24"
        )
        val savedMapping = UserMapping(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com"
        ).apply {
            id = 1L
            ipAddress = "10.0.0.0/24"
            ipRangeType = com.secman.domain.IpRangeType.CIDR
            ipRangeStart = 167772160L  // 10.0.0.0
            ipRangeEnd = 167772415L    // 10.0.0.255
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }

        every { userRepository.findById(userId) } returns Optional.of(user)
        every {
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(any(), any(), any())
        } returns false
        every { userMappingRepository.save(any<UserMapping>()) } returns savedMapping

        // When
        val result = userMappingService.createMapping(userId, request)

        // Then
        assertEquals("10.0.0.0/24", result.ipAddress)
        assertEquals("CIDR", result.ipRangeType.toString())
        assertEquals(256L, result.ipCount)
    }

    @Test
    fun `createMapping with dash range parses correctly`() {
        // Given
        val userId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val request = CreateUserMappingRequest(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com",
            ipAddress = "172.16.0.1-172.16.0.100"
        )
        val savedMapping = UserMapping(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com"
        ).apply {
            id = 1L
            ipAddress = "172.16.0.1-172.16.0.100"
            ipRangeType = com.secman.domain.IpRangeType.DASH_RANGE
            ipRangeStart = 2886729729L  // 172.16.0.1
            ipRangeEnd = 2886729828L    // 172.16.0.100
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }

        every { userRepository.findById(userId) } returns Optional.of(user)
        every {
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(any(), any(), any())
        } returns false
        every { userMappingRepository.save(any<UserMapping>()) } returns savedMapping

        // When
        val result = userMappingService.createMapping(userId, request)

        // Then
        assertEquals("172.16.0.1-172.16.0.100", result.ipAddress)
        assertEquals("DASH_RANGE", result.ipRangeType.toString())
        assertEquals(100L, result.ipCount)
    }

    @Test
    fun `createMapping with invalid IP format throws exception`() {
        // Given
        val userId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val request = CreateUserMappingRequest(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com",
            ipAddress = "999.999.999.999"  // Invalid IP
        )

        every { userRepository.findById(userId) } returns Optional.of(user)

        // When/Then
        val exception = assertThrows<IllegalArgumentException> {
            userMappingService.createMapping(userId, request)
        }
        assertTrue(exception.message!!.contains("Invalid IP") || exception.message!!.contains("invalid"))
    }

    @Test
    fun `createMapping with both AWS account and IP succeeds`() {
        // Given
        val userId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val request = CreateUserMappingRequest(
            email = "user@example.com",
            awsAccountId = "123456789012",
            domain = "example.com",
            ipAddress = "192.168.1.100"
        )
        val savedMapping = UserMapping(
            email = "user@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        ).apply {
            id = 1L
            ipAddress = "192.168.1.100"
            ipRangeType = com.secman.domain.IpRangeType.SINGLE
            ipRangeStart = 3232235876L
            ipRangeEnd = 3232235876L
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }

        every { userRepository.findById(userId) } returns Optional.of(user)
        every {
            userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(any(), any(), any())
        } returns false
        every { userMappingRepository.save(any<UserMapping>()) } returns savedMapping

        // When
        val result = userMappingService.createMapping(userId, request)

        // Then
        assertEquals("123456789012", result.awsAccountId)
        assertEquals("192.168.1.100", result.ipAddress)
    }

    @Test
    fun `createMapping detects duplicate IP mappings`() {
        // Given
        val userId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val request = CreateUserMappingRequest(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com",
            ipAddress = "192.168.1.100"
        )

        every { userRepository.findById(userId) } returns Optional.of(user)
        every {
            userMappingRepository.existsByEmailAndIpAddressAndDomain(
                "user@example.com", "192.168.1.100", "example.com"
            )
        } returns true

        // When/Then
        val exception = assertThrows<IllegalStateException> {
            userMappingService.createMapping(userId, request)
        }
        assertTrue(exception.message!!.contains("already exists", ignoreCase = true))
    }

    @Test
    fun `updateMapping updates IP address correctly`() {
        // Given
        val userId = 1L
        val mappingId = 1L
        val user = User(
            username = "testuser",
            email = "user@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = userId
        }
        val existingMapping = UserMapping(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com"
        ).apply {
            id = mappingId
            ipAddress = "192.168.1.100"
            ipRangeType = com.secman.domain.IpRangeType.SINGLE
            ipRangeStart = 3232235876L
            ipRangeEnd = 3232235876L
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        val request = UpdateUserMappingRequest(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com",
            ipAddress = "10.0.0.50"  // New IP
        )
        val updatedMapping = UserMapping(
            email = "user@example.com",
            awsAccountId = null,
            domain = "example.com"
        ).apply {
            id = mappingId
            ipAddress = "10.0.0.50"
            ipRangeType = com.secman.domain.IpRangeType.SINGLE
            ipRangeStart = 167772210L  // 10.0.0.50
            ipRangeEnd = 167772210L
            createdAt = existingMapping.createdAt
            updatedAt = Instant.now()
        }

        every { userRepository.findById(userId) } returns Optional.of(user)
        every { userMappingRepository.findById(mappingId) } returns Optional.of(existingMapping)
        every {
            userMappingRepository.existsByEmailAndIpAddressAndDomain(any(), any(), any())
        } returns false
        every { userMappingRepository.update(any<UserMapping>()) } returns updatedMapping

        // When
        val result = userMappingService.updateMapping(userId, mappingId, request)

        // Then
        assertEquals("10.0.0.50", result.ipAddress)
        verify { userMappingRepository.update(any<UserMapping>()) }
    }
}
