package com.secman.repository

import com.secman.domain.AdminSummaryLog
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for AdminSummaryLog entity
 * Feature: 070-admin-summary-email
 */
@Repository
interface AdminSummaryLogRepository : JpaRepository<AdminSummaryLog, Long>
