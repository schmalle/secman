import React, { useState, useEffect } from 'react';
import type { FormEvent } from 'react'; // Use type-only import

interface ExternalProvider {
    id: number;
    name: string;
    type: string;
    buttonText: string;
    buttonColor: string;
}

const Login = () => {
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState<string | null>(null);
    const [isLoading, setIsLoading] = useState(false);
    const [externalProviders, setExternalProviders] = useState<ExternalProvider[]>([]);

    useEffect(() => {
        fetchExternalProviders();
    }, []);

    // Read OAuth error from URL parameter (redirected from backend on OAuth failure)
    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        const errorParam = params.get('error');
        if (errorParam) {
            setError(decodeURIComponent(errorParam));
            // Clean URL to prevent error persisting on refresh
            window.history.replaceState({}, '', '/login');
        }
    }, []);

    const fetchExternalProviders = async () => {
        try {
            console.log('[OAuth] Fetching enabled identity providers from /api/identity-providers/enabled');
            const response = await fetch('/api/identity-providers/enabled');
            if (response.ok) {
                const data = await response.json();
                console.log('[OAuth] Successfully fetched identity providers:', data);
                console.log('[OAuth] Number of providers:', data.length);
                setExternalProviders(data);
            } else {
                console.error('[OAuth] Failed to fetch providers, status:', response.status);
            }
        } catch (err) {
            console.error('[OAuth] Failed to load external providers:', err);
        }
    };

    const handleExternalLogin = (providerId: number) => {
        const provider = externalProviders.find(p => p.id === providerId);
        console.log('=== OAuth Login Flow START ===');
        console.log('[OAuth] Provider ID:', providerId);
        console.log('[OAuth] Provider details:', provider);
        console.log('[OAuth] Current window.location.origin:', window.location.origin);
        console.log('[OAuth] Current window.location.href:', window.location.href);

        // Clear any stale authentication data before starting fresh OAuth flow
        // This prevents issues with cached OAuth states in corporate AAD environments
        console.log('[OAuth] Clearing stale authentication data...');
        localStorage.removeItem('authToken'); // Legacy cleanup
        localStorage.removeItem('user');
        sessionStorage.clear(); // Clear any cached OAuth-related data

        // Delete legacy auth cookies to ensure fresh OAuth flow
        document.cookie = 'authToken=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT';

        // Generate a fresh login nonce to ensure state uniqueness
        const loginNonce = Date.now().toString(36) + Math.random().toString(36).substr(2);
        sessionStorage.setItem('oauth_login_nonce', loginNonce);
        console.log('[OAuth] Generated login nonce:', loginNonce);

        // Add cache-busting parameter to force fresh OAuth state generation
        const redirectUrl = `/oauth/authorize/${providerId}?_t=${loginNonce}`;
        console.log('[OAuth] Redirecting to:', redirectUrl);
        console.log('[OAuth] Full URL will be:', window.location.origin + redirectUrl);
        console.log('[OAuth] Browser will now redirect to backend OAuth endpoint');
        console.log('=== OAuth Login Flow - Browser Redirect ===');

        window.location.href = redirectUrl;
    };

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault();
        setError(null);
        setIsLoading(true);

        try {
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                credentials: 'include',
                body: JSON.stringify({ username, password }),
            });

            const data = await response.json();

            if (response.ok) {
                // Login successful - JWT is now stored in HttpOnly cookie by backend
                // Store user data for UI display (non-sensitive info only)
                const userData = {
                    id: data.id,
                    username: data.username,
                    email: data.email,
                    roles: data.roles
                };
                localStorage.setItem('user', JSON.stringify(userData));
                // Note: Token is NOT stored in localStorage for security (XSS protection)
                // Authentication is handled via HttpOnly cookie set by backend

                // Set global user state for Header component
                (window as any).currentUser = userData;
                window.dispatchEvent(new CustomEvent('userLoaded'));

                // Small delay to ensure localStorage is written before redirect
                setTimeout(() => {
                    window.location.href = '/'; // Or '/dashboard' if you have a dedicated page
                }, 100);
            } else {
                setError(data.error || 'Login failed. Please check your credentials.');
            }
        } catch (err) {
            console.error('Login request failed:', err);
            setError('An error occurred during login. Please try again.');
        } finally {
            setIsLoading(false);
        }
    };

    return (
        <div className="container mt-5">
            <div className="row justify-content-center">
                <div className="col-md-6 col-lg-4">
                    <div className="card">
                        <div className="card-body">
                            <div className="text-center mb-4">
                                <img src="/SecManLogo.png" alt="SecMan" height="90" className="mb-3" />
                                <h3 className="card-title">Login</h3>
                            </div>

                            {/* External Identity Providers */}
                            {externalProviders.length > 0 && (
                                <div className="mb-4">
                                    <div className="text-center mb-3">
                                        <small className="text-muted">Sign in with</small>
                                    </div>
                                    <div className="d-grid gap-2">
                                        {externalProviders.map(provider => (
                                            <button
                                                key={provider.id}
                                                type="button"
                                                className="btn"
                                                style={{
                                                    backgroundColor: provider.buttonColor,
                                                    color: '#fff',
                                                    border: 'none'
                                                }}
                                                onClick={() => handleExternalLogin(provider.id)}
                                                disabled={isLoading}
                                            >
                                                <i className="bi bi-box-arrow-in-right me-2"></i>
                                                {provider.buttonText}
                                            </button>
                                        ))}
                                    </div>
                                    <div className="text-center my-3">
                                        <small className="text-muted">or</small>
                                    </div>
                                </div>
                            )}

                            <form onSubmit={handleSubmit}>
                                <div className="mb-3">
                                    <label htmlFor="username" className="form-label">Username</label>
                                    <input
                                        type="text"
                                        className="form-control"
                                        id="username"
                                        value={username}
                                        onChange={(e) => setUsername(e.target.value)}
                                        required
                                        disabled={isLoading}
                                    />
                                </div>
                                <div className="mb-3">
                                    <label htmlFor="password" className="form-label">Password</label>
                                    <input
                                        type="password"
                                        className="form-control"
                                        id="password"
                                        value={password}
                                        onChange={(e) => setPassword(e.target.value)}
                                        required
                                        disabled={isLoading}
                                    />
                                </div>
                                {error && (
                                    <div className="alert alert-danger" role="alert">
                                        <strong>Login failed:</strong> {error}
                                        <div className="mt-2">
                                            <small className="text-muted">
                                                If this persists, try clearing your browser cache or contact IT support.
                                            </small>
                                        </div>
                                    </div>
                                )}
                                <div className="d-grid">
                                    <button type="submit" className="btn btn-primary" disabled={isLoading}>
                                        {isLoading ? 'Logging in...' : 'Login'}
                                    </button>
                                </div>
                            </form>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default Login;
