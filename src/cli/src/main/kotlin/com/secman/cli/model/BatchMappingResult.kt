package com.secman.cli.model

/**
 * Result object for batch import operations (Feature 049)
 *
 * Tracks the outcome of processing multiple user mappings from CSV/JSON files
 * Supports partial success mode - continues processing valid entries even when some fail
 */
data class BatchMappingResult(
    /** Total number of lines/entries processed from the file */
    val totalProcessed: Int,

    /** Number of mappings successfully created */
    val created: Int,

    /** Number of mappings skipped (duplicates) */
    val skipped: Int,

    /** Detailed error messages with line/entry numbers */
    val errors: List<String> = emptyList(),

    /** Warning messages (e.g., pending user mappings) */
    val warnings: List<String> = emptyList()
) {
    /** True if any errors occurred during processing */
    val hasErrors: Boolean get() = errors.isNotEmpty()

    /** Success rate as percentage (created / totalProcessed) */
    val successRate: Double get() = if (totalProcessed > 0) created.toDouble() / totalProcessed else 0.0

    /**
     * Formats a summary string for console output
     */
    fun toSummaryString(): String = buildString {
        appendLine("Total processed: $totalProcessed")
        appendLine("Created: $created")
        appendLine("Skipped: $skipped duplicates")
        appendLine("Errors: ${errors.size} validation failures")
        if (warnings.isNotEmpty()) {
            appendLine("Warnings: ${warnings.size}")
        }
    }
}
