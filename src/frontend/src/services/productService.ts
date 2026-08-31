/**
 * Product Service
 *
 * Handles API calls for products overview functionality
 */

import { authenticatedGet } from '../utils/auth';
import { downloadResponse } from '../utils/download';

export interface ProductListResponse {
    products: string[];
    totalCount: number;
}

export interface ProductSystemDto {
    assetId: number;
    name: string;
    ip: string | null;
    adDomain: string | null;
    cloudAccountId: string | null;
    cloudInstanceId: string | null;
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
    const data = await response.json();
    return {
        products: data.products ?? [],
        totalCount: data.totalCount ?? 0,
    };
}

/**
 * Get list of unique products from vulnerability data
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
    const data = await response.json();
    return {
        products: data.products ?? [],
        totalCount: data.totalCount ?? 0,
    };
}

/**
 * Get paginated list of systems running a specific product
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
    const data = await response.json();
    return {
        ...data,
        content: data.content ?? [],
        totalElements: data.totalElements ?? 0,
        totalPages: data.totalPages ?? 0,
    };
}

/**
 * Export systems running a specific product to Excel
 *
 * @param product - Product name (will be URL encoded)
 * @returns Promise<void> - Downloads the Excel file
 */
export async function exportProductSystems(product: string): Promise<void> {
    const encodedProduct = encodeURIComponent(product);
    const url = `/api/products/${encodedProduct}/export`;

    // Use fetch with credentials to include HttpOnly auth cookie
    const response = await fetch(url, {
        method: 'GET',
        credentials: 'include'
    });

    if (!response.ok) {
        // Try to get error message from response body
        let errorMessage = `Failed to export product systems: ${response.status}`;
        try {
            const errorBody = await response.json();
            if (errorBody.error) {
                errorMessage = errorBody.error;
            }
        } catch {
            // Ignore JSON parse error, use default message
        }
        throw new Error(errorMessage);
    }

    await downloadResponse(response, 'product_systems_export.xlsx');
}
