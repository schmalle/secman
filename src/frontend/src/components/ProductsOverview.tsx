/**
 * ProductsOverview Component
 *
 * Displays products from vulnerability data with systems list
 * Features:
 * - Product dropdown selector with search filter
 * - Paginated systems table
 * - Empty state handling
 *
 * Feature: 054-products-overview
 * Task: T013-T015, T023-T024 (search)
 * User Story: US1 - View Systems Running a Specific Product (P1)
 * User Story: US2 - Search and Filter Products (P2)
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
    getProducts,
    getProductSystems,
    exportProductSystems,
    getTopProducts,
    type ProductListResponse,
    type PaginatedProductSystemsResponse,
    type ProductSystemDto,
    type TopProductDto
} from '../services/productService';

const ProductsOverview: React.FC = () => {
    // Product list state
    const [products, setProducts] = useState<string[]>([]);
    const [selectedProduct, setSelectedProduct] = useState<string>('');
    const [loadingProducts, setLoadingProducts] = useState<boolean>(true);

    // Top products state
    const [topProducts, setTopProducts] = useState<TopProductDto[]>([]);
    const [loadingTopProducts, setLoadingTopProducts] = useState<boolean>(true);

    // Search state
    const [searchTerm, setSearchTerm] = useState<string>('');
    const [debouncedSearch, setDebouncedSearch] = useState<string>('');
    const searchTimeoutRef = useRef<NodeJS.Timeout | null>(null);

    // Systems state
    const [systems, setSystems] = useState<ProductSystemDto[]>([]);
    const [loadingSystems, setLoadingSystems] = useState<boolean>(false);

    // Pagination state
    const [currentPage, setCurrentPage] = useState<number>(0);
    const [pageSize, setPageSize] = useState<number>(50);
    const [totalPages, setTotalPages] = useState<number>(0);
    const [totalElements, setTotalElements] = useState<number>(0);

    // Error state
    const [error, setError] = useState<string | null>(null);

    // Export state
    const [exporting, setExporting] = useState<boolean>(false);

    /**
     * Debounce search input
     */
    useEffect(() => {
        if (searchTimeoutRef.current) {
            clearTimeout(searchTimeoutRef.current);
        }

        searchTimeoutRef.current = setTimeout(() => {
            setDebouncedSearch(searchTerm);
        }, 300);

        return () => {
            if (searchTimeoutRef.current) {
                clearTimeout(searchTimeoutRef.current);
            }
        };
    }, [searchTerm]);

    /**
     * Fetch products when debounced search changes
     */
    useEffect(() => {
        fetchProducts(debouncedSearch);
    }, [debouncedSearch]);

    /**
     * Fetch top products on mount
     */
    useEffect(() => {
        fetchTopProducts();
    }, []);

    /**
     * Fetch systems when product changes
     */
    useEffect(() => {
        if (selectedProduct) {
            fetchSystems();
        } else {
            setSystems([]);
            setTotalElements(0);
            setTotalPages(0);
        }
    }, [selectedProduct, currentPage, pageSize]);

    /**
     * Fetch products list with optional search
     */
    const fetchProducts = async (search?: string) => {
        setLoadingProducts(true);
        setError(null);

        try {
            const response: ProductListResponse = await getProducts(search);
            setProducts(response.products);
            // Clear selection if the selected product is no longer in the filtered list
            if (selectedProduct && !response.products.includes(selectedProduct)) {
                setSelectedProduct('');
            }
        } catch (err: any) {
            console.error('Failed to fetch products:', err);
            setError('Failed to load products. Please try again.');
        } finally {
            setLoadingProducts(false);
        }
    };

    /**
     * Fetch top products by vulnerability count
     */
    const fetchTopProducts = async () => {
        setLoadingTopProducts(true);

        try {
            const response = await getTopProducts(15);
            setTopProducts(response.products);
        } catch (err: any) {
            console.error('Failed to fetch top products:', err);
            // Don't set error for top products - just log it
        } finally {
            setLoadingTopProducts(false);
        }
    };

    /**
     * Fetch systems for selected product
     */
    const fetchSystems = async () => {
        if (!selectedProduct) return;

        setLoadingSystems(true);
        setError(null);

        try {
            const response: PaginatedProductSystemsResponse = await getProductSystems(
                selectedProduct,
                currentPage,
                pageSize
            );
            setSystems(response.content);
            setTotalElements(response.totalElements);
            setTotalPages(response.totalPages);
        } catch (err: any) {
            console.error('Failed to fetch systems:', err);
            setError('Failed to load systems. Please try again.');
        } finally {
            setLoadingSystems(false);
        }
    };

    /**
     * Handle product selection from dropdown
     */
    const handleProductChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
        setSelectedProduct(e.target.value);
        setCurrentPage(0); // Reset to first page when product changes
    };

    /**
     * Handle product selection from top products list
     */
    const handleTopProductClick = (productName: string) => {
        setSelectedProduct(productName);
        setSearchTerm('');
        setCurrentPage(0);
    };

    /**
     * Handle page change
     */
    const handlePageChange = (newPage: number) => {
        setCurrentPage(newPage);
    };

    /**
     * Handle page size change
     */
    const handlePageSizeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
        setPageSize(parseInt(e.target.value, 10));
        setCurrentPage(0); // Reset to first page
    };

    /**
     * Handle export to Excel
     */
    const handleExport = async () => {
        if (!selectedProduct) return;

        setExporting(true);
        setError(null);

        try {
            await exportProductSystems(selectedProduct);
        } catch (err: any) {
            console.error('Failed to export systems:', err);
            setError(err.message || 'Failed to export systems. Please try again.');
        } finally {
            setExporting(false);
        }
    };

    /**
     * Generate page numbers for pagination
     */
    const getPageNumbers = (): (number | string)[] => {
        const pages: (number | string)[] = [];
        const maxVisiblePages = 7;

        if (totalPages <= maxVisiblePages) {
            for (let i = 0; i < totalPages; i++) {
                pages.push(i);
            }
        } else {
            // Always show first page
            pages.push(0);

            if (currentPage > 2) {
                pages.push('...');
            }

            // Show pages around current page
            const start = Math.max(1, currentPage - 1);
            const end = Math.min(totalPages - 2, currentPage + 1);

            for (let i = start; i <= end; i++) {
                if (!pages.includes(i)) {
                    pages.push(i);
                }
            }

            if (currentPage < totalPages - 3) {
                pages.push('...');
            }

            // Always show last page
            if (!pages.includes(totalPages - 1)) {
                pages.push(totalPages - 1);
            }
        }

        return pages;
    };

    // Loading state for products
    if (loadingProducts) {
        return (
            <div className="container-fluid py-4">
                <div className="d-flex justify-content-center p-5">
                    <div className="spinner-border text-primary" role="status">
                        <span className="visually-hidden">Loading products...</span>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div className="container-fluid py-4">
            <h1 className="h3 mb-4">
                <i className="bi bi-box-seam me-2"></i>
                Products Overview
            </h1>

            {/* Error Alert */}
            {error && (
                <div className="alert alert-danger alert-dismissible fade show" role="alert">
                    <i className="bi bi-exclamation-triangle me-2"></i>
                    {error}
                    <button
                        type="button"
                        className="btn-close"
                        onClick={() => setError(null)}
                        aria-label="Close"
                    ></button>
                </div>
            )}

            {/* Product selector with search */}
            <div className="card mb-4">
                <div className="card-body">
                    <div className="row align-items-end">
                        <div className="col-md-4">
                            <label htmlFor="productSearch" className="form-label">
                                Search Products
                            </label>
                            <div className="input-group">
                                <span className="input-group-text">
                                    <i className="bi bi-search"></i>
                                </span>
                                <input
                                    type="text"
                                    id="productSearch"
                                    className="form-control"
                                    placeholder="Type to filter products..."
                                    value={searchTerm}
                                    onChange={(e) => setSearchTerm(e.target.value)}
                                />
                                {searchTerm && (
                                    <button
                                        className="btn btn-outline-secondary"
                                        type="button"
                                        onClick={() => setSearchTerm('')}
                                        title="Clear search"
                                    >
                                        <i className="bi bi-x-lg"></i>
                                    </button>
                                )}
                            </div>
                        </div>
                        <div className="col-md-5 mt-3 mt-md-0">
                            <label htmlFor="productSelect" className="form-label">
                                Select Product
                            </label>
                            <select
                                id="productSelect"
                                className="form-select"
                                value={selectedProduct}
                                onChange={handleProductChange}
                                disabled={loadingProducts || products.length === 0}
                            >
                                <option value="">-- Select a product --</option>
                                {products.map((product, index) => (
                                    <option key={index} value={product}>
                                        {product}
                                    </option>
                                ))}
                            </select>
                        </div>
                        <div className="col-md-3 text-md-end mt-3 mt-md-0">
                            <span className="text-muted">
                                {loadingProducts ? (
                                    <span className="spinner-border spinner-border-sm me-1" role="status"></span>
                                ) : (
                                    <>
                                        {products.length} product{products.length !== 1 ? 's' : ''}
                                        {searchTerm && ' found'}
                                    </>
                                )}
                            </span>
                        </div>
                    </div>
                </div>
            </div>

            {/* Top 15 Products Statistics - only show when no product is selected */}
            {!selectedProduct && !loadingTopProducts && topProducts.length > 0 && (
                <div className="card mb-4">
                    <div className="card-header">
                        <i className="bi bi-bar-chart-fill me-2"></i>
                        Top 15 Products by Vulnerability Count
                    </div>
                    <div className="card-body">
                        <div className="row">
                            {topProducts.map((product, index) => {
                                const maxCount = topProducts[0]?.vulnerabilityCount || 1;
                                const percentage = (product.vulnerabilityCount / maxCount) * 100;
                                return (
                                    <div key={index} className="col-12 mb-2">
                                        <div className="d-flex align-items-center">
                                            <div className="text-muted me-2" style={{ width: '25px', textAlign: 'right' }}>
                                                {index + 1}.
                                            </div>
                                            <div className="flex-grow-1">
                                                <div className="d-flex justify-content-between mb-1">
                                                    <span
                                                        className="text-truncate"
                                                        style={{ maxWidth: 'calc(100% - 80px)', cursor: 'pointer' }}
                                                        title={`Click to view systems running ${product.product}`}
                                                        onClick={() => handleTopProductClick(product.product)}
                                                    >
                                                        <a href="#" onClick={(e) => e.preventDefault()} className="text-decoration-none">
                                                            {product.product}
                                                        </a>
                                                    </span>
                                                    <span className="badge bg-primary ms-2">
                                                        {product.vulnerabilityCount.toLocaleString()}
                                                    </span>
                                                </div>
                                                <div className="progress" style={{ height: '8px' }}>
                                                    <div
                                                        className="progress-bar bg-primary"
                                                        role="progressbar"
                                                        style={{ width: `${percentage}%` }}
                                                        aria-valuenow={percentage}
                                                        aria-valuemin={0}
                                                        aria-valuemax={100}
                                                    ></div>
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                </div>
            )}

            {!selectedProduct && loadingTopProducts && (
                <div className="card mb-4">
                    <div className="card-header">
                        <i className="bi bi-bar-chart-fill me-2"></i>
                        Top 15 Products by Vulnerability Count
                    </div>
                    <div className="card-body">
                        <div className="d-flex justify-content-center p-3">
                            <div className="spinner-border spinner-border-sm text-primary" role="status">
                                <span className="visually-hidden">Loading top products...</span>
                            </div>
                            <span className="ms-2 text-muted">Loading statistics...</span>
                        </div>
                    </div>
                </div>
            )}

            {/* Systems table - shown when a product is selected */}
            {selectedProduct && (
                <div className="card">
                    <div className="card-header d-flex justify-content-between align-items-center">
                        <span>
                            <i className="bi bi-pc-display me-2"></i>
                            Systems running: <strong>{selectedProduct}</strong>
                        </span>
                        <div className="d-flex align-items-center gap-2">
                            <span className="badge bg-primary">
                                {totalElements} system{totalElements !== 1 ? 's' : ''}
                            </span>
                            <button
                                className="btn btn-sm btn-outline-success"
                                onClick={handleExport}
                                disabled={exporting || loadingSystems || systems.length === 0}
                                title="Export to Excel"
                            >
                                {exporting ? (
                                    <>
                                        <span className="spinner-border spinner-border-sm me-1" role="status"></span>
                                        Exporting...
                                    </>
                                ) : (
                                    <>
                                        <i className="bi bi-file-earmark-excel me-1"></i>
                                        Export
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                    <div className="card-body">
                        {loadingSystems ? (
                            <div className="d-flex justify-content-center p-5">
                                <div className="spinner-border text-primary" role="status">
                                    <span className="visually-hidden">Loading systems...</span>
                                </div>
                            </div>
                        ) : systems.length === 0 ? (
                            <div className="alert alert-info mb-0" role="alert">
                                <i className="bi bi-info-circle me-2"></i>
                                No systems found running this product.
                            </div>
                        ) : (
                            <>
                                {/* Systems Table */}
                                <div className="table-responsive">
                                    <table className="table table-striped table-hover">
                                        <thead className="table-light">
                                            <tr>
                                                <th>System Name</th>
                                                <th>IP Address</th>
                                                <th>Domain</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {systems.map((system) => (
                                                <tr key={system.assetId}>
                                                    <td>{system.name}</td>
                                                    <td>{system.ip || '-'}</td>
                                                    <td>{system.adDomain || '-'}</td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>

                                {/* Pagination */}
                                {totalPages > 1 && (
                                    <div className="d-flex justify-content-between align-items-center mt-3">
                                        <div className="d-flex align-items-center">
                                            <span className="me-2">Show:</span>
                                            <select
                                                className="form-select form-select-sm"
                                                style={{ width: 'auto' }}
                                                value={pageSize}
                                                onChange={handlePageSizeChange}
                                            >
                                                <option value="20">20</option>
                                                <option value="50">50</option>
                                                <option value="100">100</option>
                                            </select>
                                            <span className="ms-2 text-muted">
                                                Showing {currentPage * pageSize + 1} to{' '}
                                                {Math.min((currentPage + 1) * pageSize, totalElements)} of{' '}
                                                {totalElements}
                                            </span>
                                        </div>
                                        <nav aria-label="Systems pagination">
                                            <ul className="pagination mb-0">
                                                <li className={`page-item ${currentPage === 0 ? 'disabled' : ''}`}>
                                                    <button
                                                        className="page-link"
                                                        onClick={() => handlePageChange(currentPage - 1)}
                                                        disabled={currentPage === 0}
                                                    >
                                                        Previous
                                                    </button>
                                                </li>
                                                {getPageNumbers().map((page, index) => (
                                                    <li
                                                        key={index}
                                                        className={`page-item ${
                                                            page === currentPage ? 'active' : ''
                                                        } ${page === '...' ? 'disabled' : ''}`}
                                                    >
                                                        {page === '...' ? (
                                                            <span className="page-link">...</span>
                                                        ) : (
                                                            <button
                                                                className="page-link"
                                                                onClick={() => handlePageChange(page as number)}
                                                            >
                                                                {(page as number) + 1}
                                                            </button>
                                                        )}
                                                    </li>
                                                ))}
                                                <li
                                                    className={`page-item ${
                                                        currentPage >= totalPages - 1 ? 'disabled' : ''
                                                    }`}
                                                >
                                                    <button
                                                        className="page-link"
                                                        onClick={() => handlePageChange(currentPage + 1)}
                                                        disabled={currentPage >= totalPages - 1}
                                                    >
                                                        Next
                                                    </button>
                                                </li>
                                            </ul>
                                        </nav>
                                    </div>
                                )}
                            </>
                        )}
                    </div>
                </div>
            )}

            {/* Empty state - no products */}
            {!selectedProduct && !loadingProducts && products.length === 0 && !searchTerm && (
                <div className="alert alert-info" role="alert">
                    <i className="bi bi-info-circle me-2"></i>
                    No products available. Products are discovered automatically from CrowdStrike vulnerability imports.
                </div>
            )}

            {/* Empty state - no search results */}
            {!selectedProduct && !loadingProducts && products.length === 0 && searchTerm && (
                <div className="alert alert-warning" role="alert">
                    <i className="bi bi-search me-2"></i>
                    No products found matching "{searchTerm}". Try a different search term.
                </div>
            )}
        </div>
    );
};

export default ProductsOverview;
