/**
 * Cache Utilities
 *
 * Utilities for calculating and displaying cache age for CrowdStrike vulnerability queries
 *
 * Feature: 041-falcon-instance-lookup
 * Task: T037 [US3-Impl]
 */

/**
 * Calculate cache age in minutes
 *
 * @param queriedAt ISO 8601 timestamp from backend
 * @returns Age in minutes (rounded down)
 */
export function calculateCacheAgeMinutes(queriedAt: string): number {
    const queriedDate = new Date(queriedAt);
    const now = new Date();
    const diffMs = now.getTime() - queriedDate.getTime();
    const diffMinutes = Math.floor(diffMs / 60000); // Convert to minutes and round down
    return diffMinutes;
}

/**
 * Determine if data is considered "live" (less than 1 minute old)
 *
 * @param queriedAt ISO 8601 timestamp from backend
 * @returns true if data is less than 1 minute old
 */
export function isLiveData(queriedAt: string): boolean {
    const ageMinutes = calculateCacheAgeMinutes(queriedAt);
    return ageMinutes < 1;
}

/**
 * Format cache age for display
 *
 * Examples:
 * - "Live data" (< 1 minute)
 * - "Cached (1 min ago)"
 * - "Cached (5 min ago)"
 * - "Cached (15 min ago)"
 *
 * @param queriedAt ISO 8601 timestamp from backend
 * @returns Formatted cache age string
 */
export function formatCacheAge(queriedAt: string): string {
    const ageMinutes = calculateCacheAgeMinutes(queriedAt);

    if (ageMinutes < 1) {
        return 'Live data';
    }

    return `Cached (${ageMinutes} min ago)`;
}

/**
 * Get cache freshness badge variant (Bootstrap color class)
 *
 * - success: Live data (< 1 minute)
 * - info: Fresh cache (1-5 minutes)
 * - warning: Aging cache (5-10 minutes)
 * - secondary: Stale cache (10-15 minutes)
 *
 * @param queriedAt ISO 8601 timestamp from backend
 * @returns Bootstrap badge color class
 */
export function getCacheBadgeVariant(queriedAt: string): string {
    const ageMinutes = calculateCacheAgeMinutes(queriedAt);

    if (ageMinutes < 1) {
        return 'success'; // Live data - green
    } else if (ageMinutes < 5) {
        return 'info'; // Fresh cache - blue
    } else if (ageMinutes < 10) {
        return 'warning'; // Aging cache - yellow/orange
    } else {
        return 'secondary'; // Stale cache - gray
    }
}

/**
 * Get cache freshness icon
 *
 * @param queriedAt ISO 8601 timestamp from backend
 * @returns Bootstrap icon class
 */
export function getCacheIcon(queriedAt: string): string {
    const isLive = isLiveData(queriedAt);
    return isLive ? 'bi-lightning-charge-fill' : 'bi-clock-history';
}
