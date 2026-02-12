import axios from 'axios';

/**
 * Configure axios to automatically include CSRF tokens and credentials in all requests.
 * This should be called once when the application starts.
 *
 * Authentication is handled via the HttpOnly secman_auth cookie, which is sent
 * automatically with withCredentials: true. No manual Authorization header needed.
 */
export function setupCSRFProtection() {
  // Enable withCredentials globally so the HttpOnly secman_auth cookie
  // is sent with every axios request automatically
  axios.defaults.withCredentials = true;

  // Get the CSRF token from meta tag
  const getCSRFToken = () => {
    const token = document.querySelector('meta[name="csrf-token"]')?.getAttribute('content');
    if (token) {
      return token;
    }

    // Fallback: Get from cookies
    const csrfCookie = document.cookie.split(';')
      .find(cookie => cookie.trim().startsWith('PLAY_CSRF_TOKEN='));
    if (csrfCookie) {
      return csrfCookie.split('=')[1];
    }

    return '';
  };

  // Add CSRF token to all axios requests
  axios.interceptors.request.use(
    config => {
      const token = getCSRFToken();
      // Ensure headers object exists
      config.headers = config.headers ?? {};
      if (token) {
        // Only use the header name that matches the backend configuration
        config.headers['Csrf-Token'] = token;
      }

      return config;
    },
    error => Promise.reject(error)
  );
}

/**
 * Makes a POST request with CSRF protection and optional file upload.
 * Authentication is handled via HttpOnly cookie (withCredentials is set globally).
 *
 * @param url The URL to send the request to
 * @param data The data to send (can be a FormData object for file uploads)
 * @param config Optional axios config overrides
 * @returns Promise with the axios response
 */
export async function csrfPost(url: string, data: any, config: any = {}) {
  const token = document.querySelector('meta[name="csrf-token"]')?.getAttribute('content') || '';

  return axios.post(url, data, {
    headers: {
      // Use only the header name that matches the backend configuration
      'Csrf-Token': token,
      ...(config.headers || {})
    },
    ...config
  });
}

/**
 * Makes a DELETE request with CSRF protection.
 * Authentication is handled via HttpOnly cookie (withCredentials is set globally).
 *
 * @param url The URL to send the request to
 * @param config Optional axios config overrides
 * @returns Promise with the axios response
 */
export async function csrfDelete(url: string, config: any = {}) {
  const token = document.querySelector('meta[name="csrf-token"]')?.getAttribute('content') || '';

  return axios.delete(url, {
    headers: {
      // Use only the header name that matches the backend configuration
      'Csrf-Token': token,
      ...(config.headers || {})
    },
    ...config
  });
}
