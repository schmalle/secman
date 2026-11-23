package com.secman.domain

/** Applies only to individual product labels supplied by a source. */
class ProductSelector(value: String, private val mode: Mode) {
    enum class Mode { EXACT, GLOB }

    val pattern = normalize(value)

    init {
        require(value.length <= 512 && pattern.isNotBlank()) { "Enter a product selector of 1–512 characters" }
        require(mode != Mode.GLOB || pattern.any { it != '*' }) { "A wildcard must include a literal character" }
    }

    fun matches(label: String?): Boolean {
        val product = label?.let(::normalize) ?: return false
        if (product.isEmpty()) return false
        if (mode == Mode.EXACT) return product == pattern

        var token = 0
        var position = 0
        var star = -1
        var consumed = 0
        while (position < product.length) {
            when {
                token < pattern.length && pattern[token] == '*' -> {
                    star = token++
                    consumed = position
                }
                token < pattern.length && pattern[token] == product[position] -> {
                    token++
                    position++
                }
                star >= 0 -> {
                    token = star + 1
                    position = ++consumed
                }
                else -> return false
            }
        }
        return (token until pattern.length).all { pattern[it] == '*' }
    }

    companion object {
        private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

        fun normalizePatterns(values: List<String>): List<String> {
            require(values.size in 1..100) { "Enter between 1 and 100 product patterns" }
            val patterns = values.mapIndexed { index, value ->
                try {
                    ProductSelector(value.trim(), Mode.GLOB).pattern
                } catch (invalid: IllegalArgumentException) {
                    throw IllegalArgumentException("Product ${index + 1}: ${invalid.message}", invalid)
                }
            }.distinct()
            require(displayPatterns(patterns).length <= 2048) { "Product list exceeds 2048 characters" }
            return patterns
        }

        fun displayPatterns(patterns: List<String>) = patterns.joinToString(", ") {
            if (',' in it || '"' in it) "\"" + it.replace("\"", "\"\"") + "\"" else it
        }

        fun encodePatterns(patterns: List<String>): String = mapper.writeValueAsString(patterns)

        fun labels(json: String?): List<String> =
            json?.let { mapper.readTree(it).filter { label -> label.isTextual }.map { label -> label.textValue() } }.orEmpty()

        fun matchesJson(pattern: String, mode: String, labels: String?): Boolean {
            if (labels == null) return false
            val selector = ProductSelector(pattern, Mode.valueOf(mode))
            return labels(labels).any(selector::matches)
        }
    }

    private fun normalize(value: String): String = buildString {
        for (character in value.trim(' ')) {
            append(if (character in 'A'..'Z') character.lowercaseChar() else character)
        }
    }
}
