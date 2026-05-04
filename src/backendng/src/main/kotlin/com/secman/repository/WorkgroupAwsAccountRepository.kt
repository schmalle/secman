package com.secman.repository

import com.secman.domain.WorkgroupAwsAccount
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface WorkgroupAwsAccountRepository : JpaRepository<WorkgroupAwsAccount, Long> {

    @Query("SELECT DISTINCT waa.awsAccountId FROM WorkgroupAwsAccount waa WHERE EXISTS (SELECT 1 FROM waa.workgroup w JOIN w.users u WHERE u.id = :userId)")
    fun findDistinctAwsAccountIdsByUserId(userId: Long): List<String>

    // LEFT JOIN FETCH on createdBy: nullable since V206 (the creator may have
    // been deleted; see ON DELETE SET NULL on fk_wg_aws_created_by). An INNER
    // JOIN FETCH would silently hide assignments whose creator was removed.
    @Query("SELECT waa FROM WorkgroupAwsAccount waa LEFT JOIN FETCH waa.createdBy JOIN FETCH waa.workgroup WHERE waa.workgroup.id = :workgroupId")
    fun findByWorkgroupId(workgroupId: Long): List<WorkgroupAwsAccount>

    fun findByWorkgroupIdAndAwsAccountId(
        workgroupId: Long,
        awsAccountId: String
    ): Optional<WorkgroupAwsAccount>

    fun existsByWorkgroupIdAndAwsAccountId(
        workgroupId: Long,
        awsAccountId: String
    ): Boolean

    fun deleteByWorkgroupId(workgroupId: Long): Long

    fun countByWorkgroupId(workgroupId: Long): Long

    /**
     * Nullify the createdBy reference when a user is deleted.
     * Preserves the workgroup→AWS-account assignment (the access rule
     * still applies) while detaching the actor for audit history. The
     * V206 migration sets ON DELETE SET NULL at the DB level, but we
     * also nullify here from UserService.deleteUser to keep the JPA
     * persistence context consistent within the same transaction.
     */
    @Query("UPDATE WorkgroupAwsAccount waa SET waa.createdBy = NULL WHERE waa.createdBy.id = :userId")
    fun nullifyCreatedByForUser(userId: Long): Int
}
