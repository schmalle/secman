package com.secman.repository

import com.secman.domain.UserCleanupRun
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable

@Repository
interface UserCleanupRunRepository : JpaRepository<UserCleanupRun, Long> {
    fun findAllOrderByStartedAtDesc(pageable: Pageable): Page<UserCleanupRun>
}
