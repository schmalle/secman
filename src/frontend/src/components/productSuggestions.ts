/**
 * Suggestion filtering for ProductAutocomplete.
 *
 * Kept out of ProductAutocomplete.tsx so the unit test tier can import it: Node's
 * type stripping cannot parse JSX, so any logic that stays in a .tsx file is only
 * reachable by asserting against the source text.
 *
 * Related to: Feature 021-vulnerability-overdue-exception-logic (Phase 3)
 */

/**
 * Products matching what the user has typed so far.
 *
 * An empty or whitespace-only filter returns every known product — the dropdown
 * opens on focus and must show the full list, not nothing. Matching is
 * case-insensitive substring, and every match is returned: the visible list is
 * capped by CSS scrolling, never by truncating the result.
 */
export function getProductSuggestions(value: string, allProducts: string[]): string[] {
    if (!value.trim()) {
        return allProducts;
    }

    const normalizedValue = value.toLowerCase();
    return allProducts.filter(product =>
        product.toLowerCase().includes(normalizedValue)
    );
}
