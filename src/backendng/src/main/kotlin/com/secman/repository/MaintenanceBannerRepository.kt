package com.secman.repository

import com.secman.domain.MaintenanceBanner
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * Repository for MaintenanceBanner entity operations.
 *
 * Provides queries for:
 * - Finding active banners (time-range query)
 * - Finding all banners (admin list view)
 * - Standard CRUD operations (inherited from JpaRepository)
 */
@Repository
interface MaintenanceBannerRepository : JpaRepository<MaintenanceBanner, Long> {

    /**
     * Find all maintenance banners that are active at the given time.
     *
     * A banner is active if: currentTime BETWEEN startTime AND endTime
     *
     * Results are ordered by creation time (newest first) to display
     * multiple concurrent banners in the correct order.
     *
     * @param currentTime The time to check for active banners (typically Instant.now())
     * @return List of active banners, ordered by createdAt DESC (may be empty)
     */
    @Query("""
        SELECT b FROM MaintenanceBanner b
        WHERE :currentTime BETWEEN b.startTime AND b.endTime
        ORDER BY b.createdAt DESC
    """)
    fun findActiveBanners(currentTime: Instant): List<MaintenanceBanner>

    /**
     * Find all maintenance banners ordered by creation time (newest first).
     *
     * Used for admin list view to show all banners (past, current, future).
     *
     * @return List of all banners ordered by createdAt DESC
     */
    @Query("""
        SELECT b FROM MaintenanceBanner b
        ORDER BY b.createdAt DESC
    """)
    fun findAllOrderByCreatedAtDesc(): List<MaintenanceBanner>
}
