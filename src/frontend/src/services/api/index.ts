/**
 * API configuration
 *
 * Centralized API base URL configuration for the application.
 * Uses environment variable PUBLIC_API_URL or defaults to localhost for development.
 */

export const API_BASE_URL = import.meta.env.PUBLIC_API_URL || 'http://localhost:8080';
