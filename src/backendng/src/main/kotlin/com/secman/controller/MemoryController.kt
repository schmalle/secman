package com.secman.controller

import io.micronaut.management.endpoint.annotation.Endpoint
import io.micronaut.management.endpoint.annotation.Read

/**
 * Management endpoint for JVM memory metrics
 * Feature: 073-memory-optimization
 *
 * Exposes heap memory statistics for monitoring and validation
 * of memory optimization targets:
 * - SC-001: Query memory <50MB above baseline
 * - SC-002: Export memory <100MB above baseline
 *
 * Access: Anonymous (management endpoint)
 * Path: GET /memory
 */
@Endpoint(id = "memory")
class MemoryController {

    /**
     * Get current JVM heap memory metrics
     *
     * @return Map containing:
     *   - heap.used: Used heap memory in MB
     *   - heap.max: Maximum heap memory in MB
     *   - heap.free: Free heap memory in MB
     *   - heap.total: Total allocated heap in MB
     *   - timestamp: Unix timestamp in milliseconds
     */
    @Read
    fun memory(): Map<String, Any> {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()

        return mapOf(
            "heap" to mapOf(
                "used" to usedMemory / 1024 / 1024,
                "max" to runtime.maxMemory() / 1024 / 1024,
                "free" to runtime.freeMemory() / 1024 / 1024,
                "total" to runtime.totalMemory() / 1024 / 1024
            ),
            "timestamp" to System.currentTimeMillis()
        )
    }
}
