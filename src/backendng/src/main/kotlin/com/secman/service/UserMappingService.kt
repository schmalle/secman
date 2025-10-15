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
    private val userMappingRepository: UserMappingRepository,
    private val ipAddressParser: IpAddressParser
) {
    
    fun getUserMappings(userId: Long): List<UserMappingResponse> {
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }
        
        val mappings = userMappingRepository.findByEmail(user.email)
        return mappings.map { it.toResponse() }
    }
    
    @Transactional
    open fun createMapping(userId: Long, request: CreateUserMappingRequest): UserMappingResponse {
        // Validate at least one field (Feature 020: extended to include ipAddress)
        if (request.awsAccountId == null && request.domain == null && request.ipAddress == null) {
            throw IllegalArgumentException("At least one of Domain, AWS Account ID, or IP Address must be provided")
        }

        // Get user email
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }

        // Parse IP address if provided (Feature 020)
        var ipParseResult: IpAddressParser.IpParseResult? = null
        if (request.ipAddress != null) {
            try {
                ipParseResult = ipAddressParser.parse(request.ipAddress)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid IP address format: ${e.message}", e)
            }
        }

        // Check for duplicates (extended for IP addresses - Feature 020)
        if (request.ipAddress != null) {
            if (userMappingRepository.existsByEmailAndIpAddressAndDomain(
                    user.email, request.ipAddress, request.domain
                )) {
                throw IllegalStateException("IP mapping already exists for this email, IP address, and domain")
            }
        } else {
            if (userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                    user.email, request.awsAccountId, request.domain
                )) {
                throw IllegalStateException("AWS mapping already exists for this email, AWS account, and domain")
            }
        }

        // Create and save
        val mapping = UserMapping(
            email = user.email,
            awsAccountId = request.awsAccountId,
            domain = request.domain
        )

        // Set IP fields if IP address was provided (Feature 020)
        if (ipParseResult != null) {
            mapping.ipAddress = request.ipAddress
            mapping.ipRangeType = ipParseResult.rangeType
            mapping.ipRangeStart = ipParseResult.startIpNumeric
            mapping.ipRangeEnd = ipParseResult.endIpNumeric
        }

        val savedMapping = userMappingRepository.save(mapping)
        return savedMapping.toResponse()
    }
    
    @Transactional
    open fun updateMapping(userId: Long, mappingId: Long, request: UpdateUserMappingRequest): UserMappingResponse {
        // Validate at least one field (Feature 020: extended to include ipAddress)
        if (request.awsAccountId == null && request.domain == null && request.ipAddress == null) {
            throw IllegalArgumentException("At least one of Domain, AWS Account ID, or IP Address must be provided")
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

        // Parse IP address if provided (Feature 020)
        var ipParseResult: IpAddressParser.IpParseResult? = null
        if (request.ipAddress != null) {
            try {
                ipParseResult = ipAddressParser.parse(request.ipAddress)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid IP address format: ${e.message}", e)
            }
        }

        // Check for duplicates (excluding current mapping) - Feature 020
        if (request.ipAddress != null) {
            val isDuplicate = userMappingRepository.existsByEmailAndIpAddressAndDomain(
                user.email, request.ipAddress, request.domain
            ) && (mapping.ipAddress != request.ipAddress || mapping.domain != request.domain)

            if (isDuplicate) {
                throw IllegalStateException("IP mapping already exists for this email, IP address, and domain")
            }
        } else {
            val isDuplicate = userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                user.email, request.awsAccountId, request.domain
            ) && (mapping.awsAccountId != request.awsAccountId || mapping.domain != request.domain)

            if (isDuplicate) {
                throw IllegalStateException("AWS mapping already exists for this email, AWS account, and domain")
            }
        }

        // Update
        mapping.awsAccountId = request.awsAccountId
        mapping.domain = request.domain

        // Update IP fields if IP address was provided (Feature 020)
        if (ipParseResult != null) {
            mapping.ipAddress = request.ipAddress
            mapping.ipRangeType = ipParseResult.rangeType
            mapping.ipRangeStart = ipParseResult.startIpNumeric
            mapping.ipRangeEnd = ipParseResult.endIpNumeric
        } else {
            // Clear IP fields if no IP address provided
            mapping.ipAddress = null
            mapping.ipRangeType = null
            mapping.ipRangeStart = null
            mapping.ipRangeEnd = null
        }

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
