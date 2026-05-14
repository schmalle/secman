package com.secman.repository

import com.secman.domain.CrowdStrikeSeverityHistory
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for the severity-history table used by the CrowdStrike reconcile
 * sweep. The natural ID is the severity string itself (already uppercased).
 *
 * Callers should use `findAll()` to read the full historical union; writes go
 * through the standard JPA `save`/`update` flow handled by the import service.
 */
@Repository
interface CrowdStrikeSeverityHistoryRepository : JpaRepository<CrowdStrikeSeverityHistory, String>
