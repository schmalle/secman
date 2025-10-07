/**
 * Release Test Helpers
 *
 * Utility functions for E2E tests related to release management
 *
 * Related to: Feature 012-build-ui-for (Release Management UI Enhancement)
 */

import { Page, expect } from '@playwright/test';

export interface TestRelease {
    version: string;
    name: string;
    description?: string;
}

export interface TestUser {
    username: string;
    password: string;
    roles: string[];
}

/**
 * Default test users
 */
export const TEST_USERS = {
    admin: {
        username: 'admin',
        password: 'admin123',
        roles: ['ADMIN'],
    },
    releaseManager: {
        username: 'releasemanager',
        password: 'rm123',
        roles: ['RELEASE_MANAGER'],
    },
    user: {
        username: 'user',
        password: 'user123',
        roles: ['USER'],
    },
};

/**
 * Login as a specific user
 *
 * @param page Playwright page
 * @param user User credentials
 */
export async function login(page: Page, user: TestUser): Promise<void> {
    await page.goto('/login');
    await page.fill('input[name="username"]', user.username);
    await page.fill('input[name="password"]', user.password);
    await page.click('button[type="submit"]');
    
    // Wait for navigation to complete
    await page.waitForURL('/', { timeout: 5000 });
}

/**
 * Login as admin
 */
export async function loginAsAdmin(page: Page): Promise<void> {
    await login(page, TEST_USERS.admin);
}

/**
 * Login as release manager
 */
export async function loginAsReleaseManager(page: Page): Promise<void> {
    await login(page, TEST_USERS.releaseManager);
}

/**
 * Login as regular user
 */
export async function loginAsUser(page: Page): Promise<void> {
    await login(page, TEST_USERS.user);
}

/**
 * Logout current user
 */
export async function logout(page: Page): Promise<void> {
    await page.click('button[aria-label="Logout"]');
    await page.waitForURL('/login', { timeout: 5000 });
}

/**
 * Create a test release via API
 *
 * @param page Playwright page (for API context)
 * @param release Release data
 * @returns Created release ID
 */
export async function createTestRelease(
    page: Page,
    release: TestRelease
): Promise<number> {
    const token = await page.evaluate(() => localStorage.getItem('authToken'));
    
    const response = await page.request.post('/api/releases', {
        headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json',
        },
        data: release,
    });

    if (!response.ok()) {
        throw new Error(`Failed to create test release: ${response.status()}`);
    }

    const data = await response.json();
    return data.id;
}

/**
 * Delete a test release via API
 *
 * @param page Playwright page (for API context)
 * @param releaseId Release ID to delete
 */
export async function deleteTestRelease(page: Page, releaseId: number): Promise<void> {
    const token = await page.evaluate(() => localStorage.getItem('authToken'));
    
    await page.request.delete(`/api/releases/${releaseId}`, {
        headers: {
            'Authorization': `Bearer ${token}`,
        },
    });
}

/**
 * Delete all test releases (cleanup utility)
 *
 * @param page Playwright page (for API context)
 * @param versionPrefix Optional prefix to filter releases (e.g., 'test-')
 */
export async function deleteAllTestReleases(
    page: Page,
    versionPrefix: string = 'test-'
): Promise<void> {
    const token = await page.evaluate(() => localStorage.getItem('authToken'));
    
    // Get all releases
    const response = await page.request.get('/api/releases', {
        headers: {
            'Authorization': `Bearer ${token}`,
        },
    });

    if (!response.ok()) {
        return; // Silently fail for cleanup
    }

    const data = await response.json();
    const releases = data.data || data; // Handle both paginated and non-paginated responses

    // Delete releases matching prefix
    for (const release of releases) {
        if (release.version.startsWith(versionPrefix)) {
            try {
                await deleteTestRelease(page, release.id);
            } catch (error) {
                console.log(`Failed to delete test release ${release.id}:`, error);
            }
        }
    }
}

/**
 * Generate a unique test release version
 *
 * @returns Semantic version string with test prefix and timestamp
 */
export function generateTestVersion(): string {
    const timestamp = Date.now();
    const major = Math.floor(timestamp / 1000000) % 100;
    const minor = Math.floor(timestamp / 1000) % 1000;
    const patch = timestamp % 1000;
    return `${major}.${minor}.${patch}`;
}

/**
 * Wait for release to appear in list
 *
 * @param page Playwright page
 * @param version Release version to wait for
 * @param timeout Timeout in milliseconds
 */
export async function waitForReleaseInList(
    page: Page,
    version: string,
    timeout: number = 5000
): Promise<void> {
    await page.waitForSelector(`text=${version}`, { timeout });
}

/**
 * Navigate to releases page
 */
export async function navigateToReleases(page: Page): Promise<void> {
    await page.goto('/releases');
    await page.waitForLoadState('networkidle');
}

/**
 * Navigate to release detail page
 */
export async function navigateToReleaseDetail(page: Page, releaseId: number): Promise<void> {
    await page.goto(`/releases/${releaseId}`);
    await page.waitForLoadState('networkidle');
}

/**
 * Navigate to release comparison page
 */
export async function navigateToComparison(page: Page): Promise<void> {
    await page.goto('/releases/compare');
    await page.waitForLoadState('networkidle');
}

/**
 * Assert status badge color
 *
 * @param page Playwright page
 * @param status Expected status (DRAFT, PUBLISHED, ARCHIVED)
 */
export async function assertStatusBadge(page: Page, status: string): Promise<void> {
    const badgeLocator = page.locator(`.badge:has-text("${status}")`);
    await expect(badgeLocator).toBeVisible();
    
    // Verify color class
    const expectedClass = {
        'DRAFT': 'bg-warning',
        'PUBLISHED': 'bg-success',
        'ARCHIVED': 'bg-secondary',
    }[status];
    
    if (expectedClass) {
        await expect(badgeLocator).toHaveClass(new RegExp(expectedClass));
    }
}

/**
 * Assert toast notification appears
 *
 * @param page Playwright page
 * @param message Expected message substring
 * @param type Optional toast type (success, error, warning)
 */
export async function assertToast(
    page: Page,
    message: string,
    type?: 'success' | 'error' | 'warning'
): Promise<void> {
    const toastSelector = type ? `.toast.${type}:has-text("${message}")` : `.toast:has-text("${message}")`;
    await expect(page.locator(toastSelector)).toBeVisible({ timeout: 3000 });
}

/**
 * Wait for loading spinner to disappear
 */
export async function waitForLoadingComplete(page: Page): Promise<void> {
    await page.waitForSelector('.spinner-border', { state: 'hidden', timeout: 10000 }).catch(() => {
        // Spinner might not appear for fast operations
    });
}
