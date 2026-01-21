/**
 * Product Service
 *
 * Handles API calls for products overview functionality
 *
 * Feature: 054-products-overview
 */

import { authenticatedGet } from '../utils/auth';

export interface ProductListResponse {
    products: string[];
    totalCount: number;
}

export interface ProductSystemDto {
    assetId: number;
    name: string;
    ip: string | null;
    adDomain: string | null;
}

export interface PaginatedProductSystemsResponse {
    content: ProductSystemDto[];
    totalElements: number;
    totalPages: number;
    currentPage: number;
    pageSize: number;
    hasNext: boolean;
    hasPrevious: boolean;
    productName: string;
}

export interface TopProductDto {
    product: string;
    vulnerabilityCount: number;
}

export interface TopProductsResponse {
    products: TopProductDto[];
    totalCount: number;
}

/**
 * Get top products by vulnerability count
 *
 * @param limit Maximum number of products to return (default 15)
 * @returns Promise<TopProductsResponse>
 */
export async function getTopProducts(limit: number = 15): Promise<TopProductsResponse> {
    const params = new URLSearchParams();
    params.append('limit', limit.toString());

    const url = `/api/products/top?${params.toString()}`;
    const response = await authenticatedGet(url);

    if (!response.ok) {
        throw new Error(`Failed to fetch top products: ${response.status}`);
    }
    return response.json();
}

/**
 * Get list of unique products from vulnerability data
 * Task: T012, T022 (search support)
 *
 * @param search Optional search term for filtering products
 * @returns Promise<ProductListResponse>
 */
export async function getProducts(search?: string): Promise<ProductListResponse> {
    const params = new URLSearchParams();
    if (search && search.trim()) {
        params.append('search', search.trim());
    }

    const queryString = params.toString();
    const url = queryString ? `/api/products?${queryString}` : '/api/products';

    const response = await authenticatedGet(url);
    if (!response.ok) {
        throw new Error(`Failed to fetch products: ${response.status}`);
    }
    return response.json();
}

/**
 * Get paginated list of systems running a specific product
 * Task: T012
 *
 * @param product - Product name (will be URL encoded)
 * @param page - Page number (0-indexed)
 * @param size - Page size (default 50)
 * @returns Promise<PaginatedProductSystemsResponse>
 */
export async function getProductSystems(
    product: string,
    page: number = 0,
    size: number = 50
): Promise<PaginatedProductSystemsResponse> {
    const encodedProduct = encodeURIComponent(product);
    const params = new URLSearchParams();
    params.append('page', page.toString());
    params.append('size', size.toString());

    const url = `/api/products/${encodedProduct}/systems?${params.toString()}`;
    const response = await authenticatedGet(url);

    if (!response.ok) {
        throw new Error(`Failed to fetch product systems: ${response.status}`);
    }
    return response.json();
}

/**
 * Export systems running a specific product to Excel
 * Task: T028
 *
 * @param product - Product name (will be URL encoded)
 * @returns Promise<void> - Downloads the Excel file
 */
export async function exportProductSystems(product: string): Promise<void> {
    const encodedProduct = encodeURIComponent(product);
    const url = `/api/products/${encodedProduct}/export`;

    const token = localStorage.getItem('authToken');
    if (!token) {
        throw new Error('No authentication token found');
    }

    const response = await fetch(url, {
        method: 'GET',
        headers: {
            'Authorization': `Bearer ${token}`
        }
    });

    if (!response.ok) {
        throw new Error(`Failed to export product systems: ${response.status}`);
    }

    // Get filename from Content-Disposition header or use default
    const contentDisposition = response.headers.get('Content-Disposition');
    let filename = 'product_systems_export.xlsx';
    if (contentDisposition) {
        const match = contentDisposition.match(/filename="([^"]+)"/);
        if (match) {
            filename = match[1];
        }
    }

    // Create blob and trigger download
    const blob = await response.blob();
    const downloadUrl = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = downloadUrl;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(downloadUrl);
}
