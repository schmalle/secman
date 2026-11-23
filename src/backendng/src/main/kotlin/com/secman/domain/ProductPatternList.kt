package com.secman.domain

class ProductPatternList(json: String) {
    private val entries = ProductSelector.labels(json)
    private val plain = entries.filterNot { '*' in it }
    private val globs = entries.filter { '*' in it }.map { ProductSelector(it, ProductSelector.Mode.GLOB) }

    fun matches(cve: String?, products: String?, labelsJson: String?): Boolean {
        if (plain.any { cve.equals(it, ignoreCase = true) || products?.contains(it, ignoreCase = true) == true }) return true
        if (globs.isEmpty()) return false
        // Before individual labels were stored, the importer joined product names with commas.
        val labels = if (labelsJson == null && products != null && ',' !in products) {
            listOf(products)
        } else ProductSelector.labels(labelsJson)
        return labels.any { label -> globs.any { it.matches(label) } }
    }
}
