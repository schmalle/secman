package com.secman.service

import com.secman.domain.UserMapping
import com.secman.dto.CreateUserMappingRequest
import com.secman.dto.UpdateUserMappingRequest
import com.secman.dto.UserMappingResponse
import com.secman.dto.toResponse
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
open class UserMappingService(
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository
) {
    
    fun getUserMappings(userId: Long): List<UserMappingResponse> {
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }
        
        val mappings = userMappingRepository.findByEmail(user.email)
        return mappings.map { it.toResponse() }
    }
    
    @Transactional
    open fun createMapping(userId: Long, request: CreateUserMappingRequest): UserMappingResponse {
        // Validate at least one field
        if (request.awsAccountId == null && request.domain == null) {
            throw IllegalArgumentException("At least one of Domain or AWS Account ID must be provided")
        }
        
        // Get user email
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }
        
        // Check for duplicates
        if (userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                user.email, request.awsAccountId, request.domain
            )) {
            throw IllegalStateException("This mapping already exists")
        }
        
        // Create and save
        val mapping = UserMapping(
            email = user.email,
            awsAccountId = request.awsAccountId,
            domain = request.domain
        )
        
        val savedMapping = userMappingRepository.save(mapping)
        return savedMapping.toResponse()
    }
    
    @Transactional
    open fun updateMapping(userId: Long, mappingId: Long, request: UpdateUserMappingRequest): UserMappingResponse {
        // Validate at least one field
        if (request.awsAccountId == null && request.domain == null) {
            throw IllegalArgumentException("At least one of Domain or AWS Account ID must be provided")
        }
        
        // Get user
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }
        
        // Get mapping and verify ownership
        val mapping = userMappingRepository.findById(mappingId)
            .orElseThrow { NoSuchElementException("Mapping not found") }
        
        if (mapping.email != user.email) {
            throw IllegalArgumentException("Mapping does not belong to user")
        }
        
        // Check for duplicates (excluding current mapping)
        val isDuplicate = userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
            user.email, request.awsAccountId, request.domain
        ) && (mapping.awsAccountId != request.awsAccountId || mapping.domain != request.domain)
        
        if (isDuplicate) {
            throw IllegalStateException("This mapping already exists")
        }
        
        // Update
        mapping.awsAccountId = request.awsAccountId
        mapping.domain = request.domain
        
        val updated = userMappingRepository.update(mapping)
        return updated.toResponse()
    }
    
    @Transactional
    open fun deleteMapping(userId: Long, mappingId: Long): Boolean {
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }
        
        val mapping = userMappingRepository.findById(mappingId)
            .orElseThrow { NoSuchElementException("Mapping not found") }
        
        if (mapping.email != user.email) {
            throw IllegalArgumentException("Mapping does not belong to user")
        }
        
        userMappingRepository.delete(mapping)
        return true
    }
}
