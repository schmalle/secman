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
    // SECURITY: Only allow known error codes to prevent reflected content injection
    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        const errorParam = params.get('error');
        if (errorParam) {
            const ERROR_MESSAGES: Record<string, string> = {
                'oauth_failed': 'OAuth authentication failed. Please try again.',
                'oauth_timeout': 'OAuth authentication timed out. Please try again.',
                'oauth_state_mismatch': 'Authentication state mismatch. Please try again.',
                'oauth_no_email': 'No email address was provided by the identity provider.',
                'oauth_user_not_found': 'No account found for this identity. Please contact your administrator.',
                'oauth_provider_error': 'The identity provider returned an error. Please try again.',
                'session_expired': 'Your session has expired. Please log in again.',
                'access_denied': 'Access denied. You do not have permission to access this resource.',
                'mfa_required': 'Multi-factor authentication is required.',
            };
            const safeMessage = ERROR_MESSAGES[errorParam] || 'An authentication error occurred. Please try again.';
            setError(safeMessage);
            // Clean URL to prevent error persisting on refresh
            window.history.replaceState({}, '', '/login');
        }
    }, []);

    const fetchExternalProviders = async () => {
        try {
            const response = await fetch('/api/identity-providers/enabled');
            if (response.ok) {
                const data = await response.json();
                setExternalProviders(data);
            }
        } catch {
            // Silently fail - external providers are optional
        }
    };

    const handleExternalLogin = async (providerId: number) => {
        // Clear any stale authentication data before starting fresh OAuth flow
        // This prevents issues with cached OAuth states in corporate AAD environments
        localStorage.removeItem('authToken'); // Legacy cleanup
        localStorage.removeItem('user');
        sessionStorage.clear(); // Clear any cached OAuth-related data

        // Delete legacy auth cookies to ensure fresh OAuth flow
        document.cookie = 'authToken=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT';

        // Clear the HttpOnly auth cookie via backend endpoint.
        // This is the primary defense against the stale-cookie login loop where an expired
        // secman_auth cookie causes Micronaut to reject OAuth requests before the controller runs.
        try {
            await fetch('/api/auth/clear-session', { method: 'POST', credentials: 'include' });
        } catch {
            // Non-fatal: CookieTokenReader path-skip is the primary fix
        }

        // Generate a cryptographically secure login nonce to ensure state uniqueness
        const nonceBytes = new Uint8Array(16);
        crypto.getRandomValues(nonceBytes);
        const loginNonce = Array.from(nonceBytes, b => b.toString(16).padStart(2, '0')).join('');
        sessionStorage.setItem('oauth_login_nonce', loginNonce);

        // Add cache-busting parameter to force fresh OAuth state generation
        const redirectUrl = `/oauth/authorize/${providerId}?_t=${loginNonce}`;
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

            let data: any;
            try {
                const text = await response.text();
                data = text ? JSON.parse(text) : {};
            } catch {
                data = {};
            }

            if (response.ok) {
                // Check if MFA is required before full authentication
                if (data.mfaRequired) {
                    // Password verified but MFA needed - initiate passkey authentication
                    try {
                        const optionsRes = await fetch('/api/passkey/login-options', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ username: data.username }),
                        });
                        if (!optionsRes.ok) {
                            setError('Failed to initiate MFA. Please try again.');
                            return;
                        }
                        const options = await optionsRes.json();

                        // Trigger browser passkey prompt via WebAuthn API
                        const credential = await navigator.credentials.get({
                            publicKey: {
                                ...options,
                                challenge: Uint8Array.from(atob(options.challenge), c => c.charCodeAt(0)),
                                allowCredentials: (options.allowCredentials || []).map((c: any) => ({
                                    ...c,
                                    id: Uint8Array.from(atob(c.id), ch => ch.charCodeAt(0)),
                                })),
                            },
                        }) as PublicKeyCredential;

                        const authResponse = credential.response as AuthenticatorAssertionResponse;
                        const authRes = await fetch('/api/passkey/authenticate', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            credentials: 'include',
                            body: JSON.stringify({
                                username: data.username,
                                credential: {
                                    id: credential.id,
                                    rawId: btoa(String.fromCharCode(...new Uint8Array(credential.rawId))),
                                    response: {
                                        authenticatorData: btoa(String.fromCharCode(...new Uint8Array(authResponse.authenticatorData))),
                                        clientDataJSON: btoa(String.fromCharCode(...new Uint8Array(authResponse.clientDataJSON))),
                                        signature: btoa(String.fromCharCode(...new Uint8Array(authResponse.signature))),
                                    },
                                    type: credential.type,
                                },
                            }),
                        });

                        if (!authRes.ok) {
                            setError('Passkey verification failed. Please try again.');
                            return;
                        }

                        const authData = await authRes.json();
                        const userData = {
                            id: authData.userId,
                            username: authData.username,
                            email: authData.email,
                            roles: authData.roles,
                            workgroupCount: 0,
                            awsAccountCount: 0,
                            domainCount: 0,
                        };
                        localStorage.setItem('user', JSON.stringify(userData));
                        (window as any).currentUser = userData;
                        window.dispatchEvent(new CustomEvent('userLoaded'));
                        setTimeout(() => { window.location.href = '/'; }, 100);
                    } catch (mfaErr) {
                        console.error('MFA authentication failed:', mfaErr);
                        setError('MFA authentication was cancelled or failed. Please try again.');
                    }
                    return;
                }

                // Login successful (no MFA) - JWT is now stored in HttpOnly cookie by backend
                // Store user data for UI display (non-sensitive info only)
                const userData = {
                    id: data.id,
                    username: data.username,
                    email: data.email,
                    roles: data.roles,
                    workgroupCount: data.workgroupCount || 0,
                    awsAccountCount: data.awsAccountCount || 0,
                    domainCount: data.domainCount || 0
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
                if (response.status === 403) {
                    setError('Login blocked by server security policy. Please contact IT support.');
                } else {
                    setError(data.error || 'Login failed. Please check your credentials.');
                }
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
