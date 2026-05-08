package com.secman.repository

import com.secman.domain.CrowdStrikeCleanupRun
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface CrowdStrikeCleanupRunRepository : JpaRepository<CrowdStrikeCleanupRun, Long>
