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
}
