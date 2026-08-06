export function getVisibleProductSearchResults(
    searchTerm: string,
    products: string[],
    selectedProduct: string
): string[] {
    const normalizedSearch = searchTerm.trim().toLowerCase();

    if (!normalizedSearch || selectedProduct) {
        return [];
    }

    return products.filter(product => product.toLowerCase().includes(normalizedSearch));
}
