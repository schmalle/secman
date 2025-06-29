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

    const fetchExternalProviders = async () => {
        try {
            const response = await fetch('/api/identity-providers/enabled');
            if (response.ok) {
                const data = await response.json();
                setExternalProviders(data);
            }
        } catch (err) {
            console.error('Failed to load external providers:', err);
        }
    };

    const handleExternalLogin = (providerId: number) => {
        window.location.href = `/oauth/authorize/${providerId}`;
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
                // Login successful, redirect to dashboard/root
                window.location.href = '/'; // Or '/dashboard' if you have a dedicated page
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
                                        {error}
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
