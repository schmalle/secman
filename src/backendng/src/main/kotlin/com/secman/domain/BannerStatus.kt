package com.secman.domain

/**
 * Status of a maintenance banner based on current time vs. its scheduled time range.
 *
 * @property UPCOMING Banner's start time is in the future
 * @property ACTIVE Banner is currently active (current time is within start and end time)
 * @property EXPIRED Banner's end time has passed
 */
enum class BannerStatus {
    /**
     * Banner's start time is in the future - not yet active
     */
    UPCOMING,

    /**
     * Banner is currently active - startTime <= now <= endTime
     */
    ACTIVE,

    /**
     * Banner's end time has passed - no longer active
     */
    EXPIRED
}
