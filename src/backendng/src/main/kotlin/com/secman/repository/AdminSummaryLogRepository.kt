package com.secman.repository

import com.secman.domain.AdminSummaryLog
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for AdminSummaryLog entity
 */
@Repository
interface AdminSummaryLogRepository : JpaRepository<AdminSummaryLog, Long>
