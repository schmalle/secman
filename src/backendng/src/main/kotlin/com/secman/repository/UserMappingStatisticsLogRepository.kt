package com.secman.repository

import com.secman.domain.ExecutionStatus
import com.secman.domain.UserMappingStatisticsLog
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for UserMappingStatisticsLog entity.
 * Feature: 085-cli-mappings-email
 */
@Repository
interface UserMappingStatisticsLogRepository : JpaRepository<UserMappingStatisticsLog, Long> {
    fun findByStatus(status: ExecutionStatus): List<UserMappingStatisticsLog>
}
