package com.secman.service.mcp

import com.secman.domain.McpApiKey
import io.lettuce.core.api.StatefulRedisConnection
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Service for MCP API rate limiting using token bucket algorithm.
 *
 * Implements:
 * - Token bucket algorithm with Redis backing
 * - Per-API-key rate limits
 * - Configurable tiers (STANDARD, HIGH, UNLIMITED)
 * - Token refill mechanism
 *
 * Rate limits (from planning):
 * - STANDARD: 1000 req/min, 50K req/hour
 * - HIGH: 5000 req/min, 100K req/hour
 * - UNLIMITED: No limits
 *
 * Feature 009: T023
 */
@Singleton
class McpRateLimitService {

    private val logger = LoggerFactory.getLogger(McpRateLimitService::class.java)

    @Inject
    lateinit var redisConnection: StatefulRedisConnection<String, String>

    // Rate limit tiers (requests per window)
    enum class RateLimitTier(
        val minuteLimit: Long,
        val hourLimit: Long
    ) {
        STANDARD(1000, 50_000),
        HIGH(5000, 100_000),
        UNLIMITED(Long.MAX_VALUE, Long.MAX_VALUE)
    }

    companion object {
        private const val REDIS_KEY_PREFIX = "mcp:ratelimit"
        private const val MINUTE_WINDOW_SECONDS = 60
        private const val HOUR_WINDOW_SECONDS = 3600
    }

    /**
     * Check if a request is allowed for the given API key.
     * Uses token bucket algorithm with two buckets (minute, hour).
     *
     * @param apiKey The API key making the request
     * @return True if request is allowed, false if rate limit exceeded
     */
    fun checkRateLimit(apiKey: McpApiKey): Boolean {
        // Get tier limits
        val tier = getRateLimitTier(apiKey)

        if (tier == RateLimitTier.UNLIMITED) {
            logger.debug("Rate limit check passed (UNLIMITED): keyId={}", apiKey.keyId)
            return true
        }

        // Check both minute and hour windows
        val minuteAllowed = checkWindow(
            apiKey.keyId,
            "minute",
            tier.minuteLimit,
            MINUTE_WINDOW_SECONDS
        )

        if (!minuteAllowed) {
            logger.warn(
                "Rate limit exceeded (minute): keyId={}, limit={}",
                apiKey.keyId,
                tier.minuteLimit
            )
            return false
        }

        val hourAllowed = checkWindow(
            apiKey.keyId,
            "hour",
            tier.hourLimit,
            HOUR_WINDOW_SECONDS
        )

        if (!hourAllowed) {
            logger.warn(
                "Rate limit exceeded (hour): keyId={}, limit={}",
                apiKey.keyId,
                tier.hourLimit
            )
            return false
        }

        logger.debug(
            "Rate limit check passed: keyId={}, tier={}",
            apiKey.keyId,
            tier
        )
        return true
    }

    /**
     * Consume a token from the bucket (increment counter).
     * Call this after successful request processing.
     *
     * @param apiKey The API key that made the request
     */
    fun consumeToken(apiKey: McpApiKey) {
        val tier = getRateLimitTier(apiKey)

        if (tier == RateLimitTier.UNLIMITED) {
            return // No need to track unlimited tier
        }

        try {
            val commands = redisConnection.sync()

            // Increment both windows
            val minuteKey = buildRedisKey(apiKey.keyId, "minute")
            val hourKey = buildRedisKey(apiKey.keyId, "hour")

            commands.incr(minuteKey)
            commands.expire(minuteKey, MINUTE_WINDOW_SECONDS.toLong())

            commands.incr(hourKey)
            commands.expire(hourKey, HOUR_WINDOW_SECONDS.toLong())

            logger.debug("Token consumed: keyId={}", apiKey.keyId)
        } catch (e: Exception) {
            logger.error("Error consuming token: keyId={}", apiKey.keyId, e)
            // Don't throw - rate limiting failure shouldn't break request
        }
    }

    /**
     * Get current usage stats for an API key.
     *
     * @param apiKey The API key to check
     * @return Map with minuteUsed, minuteLimit, hourUsed, hourLimit
     */
    fun getUsageStats(apiKey: McpApiKey): Map<String, Long> {
        val tier = getRateLimitTier(apiKey)

        if (tier == RateLimitTier.UNLIMITED) {
            return mapOf(
                "minuteUsed" to 0,
                "minuteLimit" to Long.MAX_VALUE,
                "hourUsed" to 0,
                "hourLimit" to Long.MAX_VALUE,
                "tier" to 2 // UNLIMITED
            )
        }

        try {
            val commands = redisConnection.sync()

            val minuteKey = buildRedisKey(apiKey.keyId, "minute")
            val hourKey = buildRedisKey(apiKey.keyId, "hour")

            val minuteUsed = commands.get(minuteKey)?.toLongOrNull() ?: 0
            val hourUsed = commands.get(hourKey)?.toLongOrNull() ?: 0

            return mapOf(
                "minuteUsed" to minuteUsed,
                "minuteLimit" to tier.minuteLimit,
                "hourUsed" to hourUsed,
                "hourLimit" to tier.hourLimit,
                "tier" to tier.ordinal.toLong()
            )
        } catch (e: Exception) {
            logger.error("Error getting usage stats: keyId={}", apiKey.keyId, e)
            return mapOf(
                "minuteUsed" to 0,
                "minuteLimit" to tier.minuteLimit,
                "hourUsed" to 0,
                "hourLimit" to tier.hourLimit,
                "tier" to tier.ordinal.toLong()
            )
        }
    }

    /**
     * Reset rate limits for an API key (clear all buckets).
     * Used for testing or administrative purposes.
     *
     * @param apiKey The API key to reset
     */
    fun resetLimits(apiKey: McpApiKey) {
        try {
            val commands = redisConnection.sync()

            val minuteKey = buildRedisKey(apiKey.keyId, "minute")
            val hourKey = buildRedisKey(apiKey.keyId, "hour")

            commands.del(minuteKey, hourKey)

            logger.info("Rate limits reset: keyId={}", apiKey.keyId)
        } catch (e: Exception) {
            logger.error("Error resetting rate limits: keyId={}", apiKey.keyId, e)
        }
    }

    /**
     * Get remaining requests for an API key.
     *
     * @param apiKey The API key to check
     * @return Map with minuteRemaining and hourRemaining
     */
    fun getRemainingRequests(apiKey: McpApiKey): Map<String, Long> {
        val stats = getUsageStats(apiKey)

        val minuteRemaining = maxOf(0, stats["minuteLimit"]!! - stats["minuteUsed"]!!)
        val hourRemaining = maxOf(0, stats["hourLimit"]!! - stats["hourUsed"]!!)

        return mapOf(
            "minuteRemaining" to minuteRemaining,
            "hourRemaining" to hourRemaining
        )
    }

    // ===== PRIVATE HELPERS =====

    /**
     * Check if a request is allowed within a specific time window.
     */
    private fun checkWindow(
        keyId: String,
        windowName: String,
        limit: Long,
        windowSeconds: Int
    ): Boolean {
        try {
            val commands = redisConnection.sync()
            val redisKey = buildRedisKey(keyId, windowName)

            val current = commands.get(redisKey)?.toLongOrNull() ?: 0

            return current < limit
        } catch (e: Exception) {
            logger.error(
                "Error checking rate limit window: keyId={}, window={}",
                keyId,
                windowName,
                e
            )
            // On error, allow request (fail open for availability)
            return true
        }
    }

    /**
     * Build Redis key for rate limiting.
     */
    private fun buildRedisKey(keyId: String, window: String): String {
        return "$REDIS_KEY_PREFIX:$keyId:$window"
    }

    /**
     * Determine rate limit tier for an API key.
     * Currently returns STANDARD for all keys.
     * TODO: Implement tier detection based on API key properties when rateLimitTier field is added.
     */
    private fun getRateLimitTier(apiKey: McpApiKey): RateLimitTier {
        // TODO: When McpApiKey entity has rateLimitTier field, use it
        // For now, return STANDARD for all keys
        return RateLimitTier.STANDARD
    }
}
