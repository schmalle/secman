package com.secman.service

import com.secman.domain.User
import com.secman.domain.WorkgroupAwsAccount
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.repository.WorkgroupRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Service for managing AWS account assignments on workgroups.
 * Spec: docs/superpowers/specs/2026-04-28-workgroup-aws-account-assignment-design.md
 *
 * All mutations require ADMIN role — enforced by the controller layer.
 * This service trusts callers to have already authorized the operation.
 */
@Singleton
open class WorkgroupAwsAccountService(
    private val workgroupAwsAccountRepository: WorkgroupAwsAccountRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(WorkgroupAwsAccountService::class.java)
    private val accountIdPattern = Regex("^\\d{12}$")

    /**
     * List all AWS accounts assigned to the given workgroup.
     */
    fun list(workgroupId: Long): List<WorkgroupAwsAccount> {
        require(workgroupRepository.findById(workgroupId).isPresent) {
            "Workgroup not found: $workgroupId"
        }
        return workgroupAwsAccountRepository.findByWorkgroupId(workgroupId)
    }

    /**
     * Assign an AWS account to a workgroup. Throws on duplicate or invalid input.
     *
     * @throws IllegalArgumentException if the workgroup or actor user is not found,
     *         or if awsAccountId is not exactly 12 numeric digits.
     * @throws DuplicateAccountException if the (workgroup, account) pair already exists.
     */
    @Transactional
    open fun add(workgroupId: Long, awsAccountId: String, actorUsername: String): WorkgroupAwsAccount {
        require(accountIdPattern.matches(awsAccountId)) {
            "AWS Account ID must be exactly 12 numeric digits (got '$awsAccountId')"
        }

        val workgroup = workgroupRepository.findById(workgroupId).orElseThrow {
            IllegalArgumentException("Workgroup not found: $workgroupId")
        }
        val actor: User = userRepository.findByUsername(actorUsername).orElseThrow {
            IllegalArgumentException("Actor user not found: $actorUsername")
        }

        if (workgroupAwsAccountRepository.existsByWorkgroupIdAndAwsAccountId(workgroupId, awsAccountId)) {
            throw DuplicateAccountException(
                "AWS account $awsAccountId is already assigned to workgroup $workgroupId"
            )
        }

        val entity = WorkgroupAwsAccount(
            workgroup = workgroup,
            awsAccountId = awsAccountId,
            createdBy = actor
        )
        val saved = workgroupAwsAccountRepository.save(entity)
        logger.info(
            "Assigned AWS account {} to workgroup {} (actor={}, id={})",
            awsAccountId, workgroupId, actorUsername, saved.id
        )
        return saved
    }

    /**
     * Remove an AWS account assignment from a workgroup. No-op if not present.
     *
     * @return true if a row was deleted, false otherwise.
     */
    @Transactional
    open fun remove(workgroupId: Long, awsAccountId: String): Boolean {
        val existing = workgroupAwsAccountRepository
            .findByWorkgroupIdAndAwsAccountId(workgroupId, awsAccountId)
        return if (existing.isPresent) {
            workgroupAwsAccountRepository.delete(existing.get())
            logger.info("Removed AWS account {} from workgroup {}", awsAccountId, workgroupId)
            true
        } else {
            false
        }
    }
}

/**
 * Thrown when attempting to assign an AWS account that's already on the workgroup.
 * Mapped to HTTP 409 by the controller.
 */
class DuplicateAccountException(message: String) : RuntimeException(message)
