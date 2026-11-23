package com.secman.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProductSelectorTest {
    @Test
    fun formListNormalizesDuplicatesAndRejectsEmptyAndAllWildcardEntries() {
        val patterns = ProductSelector.normalizePatterns(listOf("*net*", " *Oracle* ", "*oracle*"))
        assertEquals(listOf("*net*", "*oracle*"), patterns)
        val rule = ProductPatternList(ProductSelector.encodePatterns(patterns))
        assertTrue(rule.matches(null, null, """["Oracle Client"]"""))
        assertTrue(rule.matches(null, "Oracle Client", null))
        assertFalse(rule.matches(null, "Oracle Client, Another Product", null))
        assertFalse(rule.matches(null, ".NET Core 6.0", """["PostgreSQL"]"""))
        assertFalse(rule.matches(null, ".NET Core 6.0", "[]"))
        for (bad in listOf("", "***", " ".repeat(2))) {
            assertThrows(IllegalArgumentException::class.java) { ProductSelector.normalizePatterns(listOf("*net*", bad)) }
        }
    }

    @Test
    fun exactUsesWholeLabelsAndPreservesPunctuation() {
        val selector = ProductSelector(".NET Framework 4.8", ProductSelector.Mode.EXACT)
        assertTrue(selector.matches(" .net framework 4.8 "))
        assertFalse(selector.matches(".NET Framework 4.8.1"))
        assertFalse(selector.matches(null))
        assertTrue(ProductSelector("C++*", ProductSelector.Mode.EXACT).matches("c++*"))
    }

    @Test
    fun wildcardTreatsOtherMetacharactersLiterally() {
        val selector = ProductSelector("*SQL_**202*[%]?", ProductSelector.Mode.GLOB)
        assertTrue(selector.matches("SQL_202[%]?"))
        assertTrue(selector.matches("MS SQL_Server 2022[%]?"))
        assertFalse(selector.matches("MS SQL Server 2022[%]?"))
        assertFalse(selector.matches("SQL_202[%]? extra"))
        assertThrows(IllegalArgumentException::class.java) { ProductSelector("***", ProductSelector.Mode.GLOB) }
    }
}
