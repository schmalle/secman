package com.secman.cli.model

/**
 * Result type for individual mapping operations (Feature 049)
 *
 * Indicates the outcome of a single user mapping operation
 */
enum class MappingOperation {
    /** New mapping created successfully */
    CREATED,

    /** Mapping already exists (duplicate detected) */
    SKIPPED_DUPLICATE,

    /** Validation error prevented creation */
    SKIPPED_INVALID,

    /** Mapping removed successfully */
    DELETED
}
