package com.secman.service

import com.secman.domain.User
import com.secman.domain.WorkgroupAdDomain
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupAdDomainRepository
import com.secman.repository.WorkgroupRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

@Singleton
open class WorkgroupAdDomainService(
    private val workgroupAdDomainRepository: WorkgroupAdDomainRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val userRepository: UserRepository,
    private val cacheInvalidator: McpAccessibleAssetsCacheInvalidator
) {
    private val logger = LoggerFactory.getLogger(WorkgroupAdDomainService::class.java)
    private val domainPattern = Regex("^[a-zA-Z0-9.-]+$")

    fun list(workgroupId: Long): List<WorkgroupAdDomain> {
        require(workgroupRepository.findById(workgroupId).isPresent) {
            "Workgroup not found: $workgroupId"
        }
        return workgroupAdDomainRepository.findByWorkgroupId(workgroupId)
    }

    @Transactional
    open fun add(workgroupId: Long, adDomain: String, actorId: Long): WorkgroupAdDomain {
        val normalized = normalize(adDomain)
        require(domainPattern.matches(normalized)) {
            "AD domain must contain only letters, numbers, dots, and hyphens (got '$adDomain')"
        }

        if (workgroupAdDomainRepository.existsByWorkgroupIdAndAdDomain(workgroupId, normalized)) {
            throw DuplicateAdDomainException("AD domain $normalized is already assigned to workgroup $workgroupId")
        }

        val workgroup = workgroupRepository.findById(workgroupId).orElseThrow {
            IllegalArgumentException("Workgroup not found: $workgroupId")
        }
        val actor: User = userRepository.findById(actorId).orElseThrow {
            IllegalArgumentException("Actor user not found: $actorId")
        }

        val entity = WorkgroupAdDomain(
            workgroup = workgroup,
            adDomain = normalized,
            createdBy = actor
        )
        val saved = workgroupAdDomainRepository.save(entity)
        cacheInvalidator.invalidate()
        logger.info(
            "Assigned AD domain {} to workgroup {} (actor={}, id={})",
            normalized, workgroupId, actor.username, saved.id
        )
        return saved
    }

    @Transactional
    open fun remove(workgroupId: Long, adDomain: String): Boolean {
        val normalized = normalize(adDomain)
        val existing = workgroupAdDomainRepository.findByWorkgroupIdAndAdDomain(workgroupId, normalized)
        return if (existing.isPresent) {
            workgroupAdDomainRepository.delete(existing.get())
            cacheInvalidator.invalidate()
            logger.info("Removed AD domain {} from workgroup {}", normalized, workgroupId)
            true
        } else {
            false
        }
    }

    private fun normalize(value: String): String {
        val normalized = value.trim().lowercase()
        require(normalized.isNotBlank()) { "AD domain is required" }
        return normalized
    }
}

class DuplicateAdDomainException(message: String) : RuntimeException(message)
