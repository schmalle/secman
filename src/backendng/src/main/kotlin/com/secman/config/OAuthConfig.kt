package com.secman.config

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.serde.annotation.Serdeable

/**
 * OAuth robustness configuration
 *
 * Provides configurable retry parameters for OAuth state lookup and token exchange
 * to handle race conditions and transient network failures.
 */
@ConfigurationProperties("secman.oauth")
@Serdeable
data class OAuthConfig(
    val stateRetry: StateRetryConfig = StateRetryConfig(),
    val tokenExchange: TokenExchangeConfig = TokenExchangeConfig()
)

/**
 * Configuration for OAuth state lookup retry logic
 *
 * Handles race conditions where Microsoft Azure callbacks arrive before
 * the state-save transaction is fully visible (100-500ms with cached SSO).
 */
@ConfigurationProperties("state-retry")
@Serdeable
data class StateRetryConfig(
    /** Maximum number of retry attempts */
    val maxAttempts: Int = 5,
    /** Initial delay between retries in milliseconds */
    val initialDelayMs: Long = 100,
    /** Maximum delay between retries in milliseconds */
    val maxDelayMs: Long = 500,
    /** Backoff multiplier for exponential backoff */
    val backoffMultiplier: Double = 1.5
)

/**
 * Configuration for OAuth token exchange retry logic
 *
 * Handles transient 5xx errors and timeouts during token exchange.
 * 4xx errors are not retried as they indicate permanent failures.
 */
@ConfigurationProperties("token-exchange")
@Serdeable
data class TokenExchangeConfig(
    /** Maximum number of retry attempts for token exchange */
    val maxRetries: Int = 2,
    /** Delay between retries in milliseconds */
    val retryDelayMs: Long = 500
)
