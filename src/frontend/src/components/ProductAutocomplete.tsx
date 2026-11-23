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
 */

import React, { useState, useEffect, useRef } from 'react';
import { getDistinctProducts } from '../services/vulnerabilityManagementService';

import { productFields, quoteProduct } from './productPatternInput';

interface ProductAutocompleteProps {
    value: string;
    onChange: (value: string) => void;
    placeholder?: string;
    disabled?: boolean;
}

const PAGE_SIZE = 100;

function ProductSuggestions({ search, onSelect }: { search: string; onSelect: (product: string) => void }) {
    const [products, setProducts] = useState<string[]>([]);
    const [offset, setOffset] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(true);
    const [failed, setFailed] = useState(false);
    const [retry, setRetry] = useState(0);

    useEffect(() => {
        const controller = new AbortController();
        const timeout = window.setTimeout(async () => {
            try {
                const next = await getDistinctProducts(search, PAGE_SIZE, offset, controller.signal);
                if (controller.signal.aborted) return;
                setProducts(previous => offset === 0 ? next : [...previous, ...next]);
                setHasMore(next.length === PAGE_SIZE);
            } catch {
                if (!controller.signal.aborted) setFailed(true);
            } finally {
                if (!controller.signal.aborted) setLoading(false);
            }
        }, search && offset === 0 ? 250 : 0);

        return () => {
            window.clearTimeout(timeout);
            controller.abort();
        };
    }, [search, offset, retry]);

    const loadMore = () => {
        setLoading(true);
        setOffset(products.length);
    };

    const handleScroll = (event: React.UIEvent<HTMLDivElement>) => {
        const { scrollHeight, scrollTop, clientHeight } = event.currentTarget;
        if (hasMore && !loading && !failed && scrollHeight - scrollTop - clientHeight <= 48) {
            loadMore();
        }
    };

    // Product names can contain punctuation that has special meaning in regular expressions.
    const highlight = search ? new RegExp(`(${search.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi') : null;

    return (
        <div
            className="dropdown-menu show w-100 shadow-sm"
            style={{ maxHeight: '300px', overflowY: 'auto', top: '100%', left: 0, zIndex: 1000 }}
            onScroll={handleScroll}
            aria-busy={loading}
        >
            {products.map((product, index) => (
                <button key={index} type="button" className="dropdown-item" onClick={() => onSelect(product)}>
                    <i className="bi bi-box-seam me-2 text-muted"></i>
                    {highlight ? product.split(highlight).map((part, partIndex) =>
                        partIndex % 2 === 1
                            ? <strong key={partIndex} className="text-primary">{part}</strong>
                            : part
                    ) : product}
                </button>
            ))}
            {loading && <div className="dropdown-item-text text-muted" role="status">Loading products…</div>}
            {failed && (
                <>
                    <div className="dropdown-item-text text-muted" role="status">Could not load products.</div>
                    <button type="button" className="dropdown-item" onClick={() => {
                        setFailed(false);
                        setLoading(true);
                        setRetry(attempt => attempt + 1);
                    }}>Retry</button>
                </>
            )}
            {!loading && !failed && hasMore && (
                <button type="button" className="dropdown-item" onClick={loadMore}>Load more products</button>
            )}
            {!loading && !failed && products.length === 0 && (
                <div className="dropdown-item-text text-muted" role="status">
                    No matching products. You can still enter a custom value.
                </div>
            )}
        </div>
    );
}

const ProductAutocomplete: React.FC<ProductAutocompleteProps> = ({
    value,
    onChange,
    placeholder = 'e.g., Apache HTTP Server 2.4.41',
    disabled = false
}) => {
    const [showSuggestions, setShowSuggestions] = useState(false);
    const wrapperRef = useRef<HTMLDivElement>(null);
    const [caret, setCaret] = useState(value.length);
    const fields = productFields(value);
    const active = fields.find(field => field.end >= caret) ?? fields[fields.length - 1];
    const search = active.value.replace(/^\*+|\*+$/g, '');

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

    const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        const newValue = e.target.value;
        onChange(newValue);
        setShowSuggestions(true);
    };

    const handleSuggestionClick = (product: string) => {
        onChange(value.slice(0, active.start) + quoteProduct(product) + value.slice(active.end));
        setShowSuggestions(false);
    };

    const handleFocus = () => {
        setShowSuggestions(true);
    };

    return (
        <div ref={wrapperRef} className="position-relative">
            <input
                type="text"
                className="form-control"
                value={value}
                onChange={handleInputChange}
                onFocus={handleFocus}
                onSelect={event => setCaret(event.currentTarget.selectionStart ?? value.length)}
                aria-label="Product names or wildcard patterns"
                placeholder={placeholder}
                disabled={disabled}
                autoComplete="off"
            />

            {showSuggestions && !disabled && (
                <ProductSuggestions key={search} search={search} onSelect={handleSuggestionClick} />
            )}

            <small className="form-text text-muted">
                <i className="bi bi-lightbulb me-1"></i>
                Separate products with commas. Use * for a wildcard, or quote a name containing a comma.
            </small>
        </div>
    );
};

export default ProductAutocomplete;
