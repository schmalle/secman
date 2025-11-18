/**
 * API configuration
 *
 * Centralized API base URL configuration for the application.
 * Uses environment variable PUBLIC_API_URL or defaults to localhost for development.
 * In production (when served from a non-localhost domain), uses relative URLs to avoid CORS issues.
 */

export const API_BASE_URL = import.meta.env.PUBLIC_API_URL ||
  (typeof window !== 'undefined' && window.location.hostname !== 'localhost'
    ? '' // Use relative URLs in production
    : 'http://localhost:8080'); // Use localhost in development
