/**
 * Product Autocomplete Component
 *
 * Autocomplete input for selecting product names from existing vulnerabilities
 *
 * Features:
 * - Fetches unique products from API
 * - Filters as user types
 * - Highlights matching text
 * - Dropdown with suggestions
 * - Manual entry allowed
 *
 * Related to: Feature 021-vulnerability-overdue-exception-logic (Phase 3)
 */

import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';

interface ProductAutocompleteProps {
    value: string;
    onChange: (value: string) => void;
    placeholder?: string;
    disabled?: boolean;
}

const ProductAutocomplete: React.FC<ProductAutocompleteProps> = ({
    value,
    onChange,
    placeholder = 'e.g., Apache HTTP Server 2.4.41',
    disabled = false
}) => {
    const [suggestions, setSuggestions] = useState<string[]>([]);
    const [showSuggestions, setShowSuggestions] = useState(false);
    const [loading, setLoading] = useState(false);
    const [allProducts, setAllProducts] = useState<string[]>([]);
    const wrapperRef = useRef<HTMLDivElement>(null);

    // Fetch all products on mount
    useEffect(() => {
        fetchProducts();
    }, []);

    // Click outside to close suggestions
    useEffect(() => {
        const handleClickOutside = (event: MouseEvent) => {
            if (wrapperRef.current && !wrapperRef.current.contains(event.target as Node)) {
                setShowSuggestions(false);
            }
        };

        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    // Filter suggestions when value changes
    useEffect(() => {
        if (value.trim()) {
            const filtered = allProducts.filter(product =>
                product.toLowerCase().includes(value.toLowerCase())
            ).slice(0, 20); // Limit to 20 suggestions

            setSuggestions(filtered);
        } else {
            setSuggestions(allProducts.slice(0, 20)); // Show first 20 when empty
        }
    }, [value, allProducts]);

    const fetchProducts = async () => {
        try {
            setLoading(true);
            const response = await axios.get('/api/vulnerability-products', {
                params: {
                    limit: 100 // Fetch up to 100 products
                }
            });
            setAllProducts(response.data || []);
        } catch (error) {
            console.error('Failed to fetch products:', error);
            setAllProducts([]);
        } finally {
            setLoading(false);
        }
    };

    const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        const newValue = e.target.value;
        onChange(newValue);
        setShowSuggestions(true);
    };

    const handleSuggestionClick = (product: string) => {
        onChange(product);
        setShowSuggestions(false);
    };

    const handleFocus = () => {
        setShowSuggestions(true);
    };

    const highlightMatch = (text: string, query: string): React.ReactNode => {
        if (!query.trim()) return text;

        const parts = text.split(new RegExp(`(${query})`, 'gi'));
        return parts.map((part, index) =>
            part.toLowerCase() === query.toLowerCase() ? (
                <strong key={index} className="text-primary">{part}</strong>
            ) : (
                part
            )
        );
    };

    return (
        <div ref={wrapperRef} className="position-relative">
            <input
                type="text"
                className="form-control"
                value={value}
                onChange={handleInputChange}
                onFocus={handleFocus}
                placeholder={placeholder}
                disabled={disabled}
                autoComplete="off"
            />

            {showSuggestions && suggestions.length > 0 && !disabled && (
                <div
                    className="dropdown-menu show w-100 shadow-sm"
                    style={{
                        maxHeight: '300px',
                        overflowY: 'auto',
                        position: 'absolute',
                        top: '100%',
                        left: 0,
                        zIndex: 1000
                    }}
                >
                    {loading && (
                        <div className="dropdown-item text-center">
                            <span className="spinner-border spinner-border-sm me-2" role="status"></span>
                            Loading products...
                        </div>
                    )}
                    {!loading && suggestions.map((product, index) => (
                        <button
                            key={index}
                            type="button"
                            className="dropdown-item"
                            onClick={() => handleSuggestionClick(product)}
                            style={{ cursor: 'pointer' }}
                        >
                            <i className="bi bi-box-seam me-2 text-muted"></i>
                            {highlightMatch(product, value)}
                        </button>
                    ))}
                    {!loading && allProducts.length === 0 && (
                        <div className="dropdown-item text-muted">
                            <i className="bi bi-info-circle me-2"></i>
                            No products found in current vulnerabilities
                        </div>
                    )}
                    {!loading && suggestions.length === 0 && allProducts.length > 0 && (
                        <div className="dropdown-item text-muted">
                            <i className="bi bi-search me-2"></i>
                            No matches found. You can still enter manually.
                        </div>
                    )}
                </div>
            )}

            {allProducts.length > 0 && (
                <small className="form-text text-muted">
                    <i className="bi bi-lightbulb me-1"></i>
                    Start typing to filter from {allProducts.length} known products, or enter custom value
                </small>
            )}
        </div>
    );
};

export default ProductAutocomplete;
