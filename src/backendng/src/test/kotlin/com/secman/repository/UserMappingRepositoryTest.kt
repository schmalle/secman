package com.secman.repository

import com.secman.domain.UserMapping
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach

/**
 * Test suite for UserMappingRepository
 * Feature: 013-user-mapping-upload
 * 
 * Tests repository CRUD operations and custom query methods.
 * Following TDD approach - these tests are written BEFORE repository implementation.
 */
@MicronautTest
class UserMappingRepositoryTest {

    @Inject
    lateinit var repository: UserMappingRepository

    @AfterEach
    fun cleanup() {
        repository.deleteAll()
    }

    @Test
    fun `should save and retrieve user mapping`() {
        val mapping = UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        )
        
        val saved = repository.save(mapping)
        
        assertNotNull(saved.id, "ID should be generated")
        assertEquals("test@example.com", saved.email)
        assertEquals("123456789012", saved.awsAccountId)
        assertEquals("example.com", saved.domain)
        
        val retrieved = repository.findById(saved.id!!).orElse(null)
        assertNotNull(retrieved, "Should retrieve saved mapping")
        assertEquals(saved.id, retrieved.id)
    }

    @Test
    fun `should find mappings by email`() {
        repository.save(UserMapping(email = "user@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "user@example.com", awsAccountId = "987654321098", domain = "example.com"))
        repository.save(UserMapping(email = "other@example.com", awsAccountId = "111111111111", domain = "example.com"))
        
        val mappings = repository.findByEmail("user@example.com")
        
        assertEquals(2, mappings.size, "Should find 2 mappings for user@example.com")
        assertTrue(mappings.all { it.email == "user@example.com" }, "All mappings should have correct email")
    }

    @Test
    fun `should find mappings by AWS account ID`() {
        repository.save(UserMapping(email = "user1@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "user2@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "user3@example.com", awsAccountId = "987654321098", domain = "example.com"))
        
        val mappings = repository.findByAwsAccountId("123456789012")
        
        assertEquals(2, mappings.size, "Should find 2 mappings for AWS account 123456789012")
        assertTrue(mappings.all { it.awsAccountId == "123456789012" }, "All mappings should have correct AWS account")
    }

    @Test
    fun `should find mappings by domain`() {
        repository.save(UserMapping(email = "user1@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "user2@example.com", awsAccountId = "987654321098", domain = "example.com"))
        repository.save(UserMapping(email = "user3@example.com", awsAccountId = "111111111111", domain = "other.com"))
        
        val mappings = repository.findByDomain("example.com")
        
        assertEquals(2, mappings.size, "Should find 2 mappings for domain example.com")
        assertTrue(mappings.all { it.domain == "example.com" }, "All mappings should have correct domain")
    }

    @Test
    fun `should detect duplicate mappings`() {
        repository.save(UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        ))
        
        val exists = repository.existsByEmailAndAwsAccountIdAndDomain(
            "test@example.com",
            "123456789012",
            "example.com"
        )
        
        assertTrue(exists, "Should detect existing mapping")
    }

    @Test
    fun `should return false for non-existent mapping`() {
        val exists = repository.existsByEmailAndAwsAccountIdAndDomain(
            "nonexistent@example.com",
            "123456789012",
            "example.com"
        )
        
        assertFalse(exists, "Should return false for non-existent mapping")
    }

    @Test
    fun `should find mapping by composite key`() {
        repository.save(UserMapping(
            email = "test@example.com",
            awsAccountId = "123456789012",
            domain = "example.com"
        ))
        
        val found = repository.findByEmailAndAwsAccountIdAndDomain(
            "test@example.com",
            "123456789012",
            "example.com"
        )
        
        assertTrue(found.isPresent, "Should find mapping by composite key")
        assertEquals("test@example.com", found.get().email)
    }

    @Test
    fun `should count mappings by email`() {
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "987654321098", domain = "example.com"))
        repository.save(UserMapping(email = "other@example.com", awsAccountId = "111111111111", domain = "example.com"))
        
        val count = repository.countByEmail("test@example.com")
        
        assertEquals(2, count, "Should count 2 mappings for test@example.com")
    }

    @Test
    fun `should find distinct AWS accounts for user`() {
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "987654321098", domain = "example.com"))
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "123456789012", domain = "other.com"))
        
        val accounts = repository.findDistinctAwsAccountIdByEmail("test@example.com")
        
        assertEquals(2, accounts.size, "Should find 2 distinct AWS accounts")
        assertTrue(accounts.contains("123456789012"), "Should contain first AWS account")
        assertTrue(accounts.contains("987654321098"), "Should contain second AWS account")
    }

    @Test
    fun `should find distinct domains for user`() {
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "123456789012", domain = "example.com"))
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "987654321098", domain = "other.com"))
        repository.save(UserMapping(email = "test@example.com", awsAccountId = "123456789012", domain = "example.com"))
        
        val domains = repository.findDistinctDomainByEmail("test@example.com")
        
        assertEquals(2, domains.size, "Should find 2 distinct domains")
        assertTrue(domains.contains("example.com"), "Should contain example.com")
        assertTrue(domains.contains("other.com"), "Should contain other.com")
    }
}
