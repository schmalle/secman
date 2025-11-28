import React, { useState, useEffect } from 'react';
import passkeyService from '../services/passkeyService';
import type { PasskeyCredentialInfo } from '../services/passkeyService';

/**
 * Passkey Management Component
 * Feature: Passkey MFA Support
 *
 * Displays and manages user's registered passkeys:
 * - List all passkeys
 * - Register new passkey
 * - Delete existing passkeys
 */
export default function PasskeyManagement() {
  const [passkeys, setPasskeys] = useState<PasskeyCredentialInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [registering, setRegistering] = useState(false);
  const [registerError, setRegisterError] = useState<string | null>(null);
  const [showRegisterModal, setShowRegisterModal] = useState(false);
  const [newPasskeyName, setNewPasskeyName] = useState('');

  useEffect(() => {
    fetchPasskeys();
  }, []);

  const fetchPasskeys = async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await passkeyService.listPasskeys();
      setPasskeys(response.passkeys);
    } catch (err: any) {
      const errorMessage = err.response?.data?.error || 'Failed to load passkeys';
      setError(errorMessage);
    } finally {
      setLoading(false);
    }
  };

  const handleRegisterPasskey = async () => {
    if (!newPasskeyName.trim()) {
      setRegisterError('Please enter a name for your passkey');
      return;
    }

    try {
      setRegistering(true);
      setRegisterError(null);

      // Start WebAuthn registration flow
      await passkeyService.startRegistration(newPasskeyName.trim());

      // Success - refresh list and close modal
      await fetchPasskeys();
      setShowRegisterModal(false);
      setNewPasskeyName('');

    } catch (err: any) {
      const errorMessage = err.message || err.response?.data?.error || 'Failed to register passkey';
      setRegisterError(errorMessage);
    } finally {
      setRegistering(false);
    }
  };

  const handleDeletePasskey = async (id: number, name: string) => {
    if (!confirm(`Are you sure you want to delete the passkey "${name}"?`)) {
      return;
    }

    try {
      await passkeyService.deletePasskey(id);
      await fetchPasskeys();
    } catch (err: any) {
      const errorMessage = err.response?.data?.error || 'Failed to delete passkey';
      alert(errorMessage);
    }
  };

  const formatDate = (dateString: string | null): string => {
    if (!dateString) return 'Never';
    try {
      return new Date(dateString).toLocaleDateString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      });
    } catch {
      return 'Invalid date';
    }
  };

  // Check if WebAuthn is supported
  const isWebAuthnSupported = typeof window !== 'undefined' &&
    window.PublicKeyCredential !== undefined;

  if (!isWebAuthnSupported) {
    return (
      <div className="alert alert-warning" role="alert">
        <i className="bi bi-exclamation-triangle-fill me-2"></i>
        Your browser does not support passkeys (WebAuthn). Please use a modern browser like Chrome, Edge, Safari, or Firefox.
      </div>
    );
  }

  if (loading) {
    return (
      <div className="text-center py-3">
        <div className="spinner-border spinner-border-sm text-primary" role="status">
          <span className="visually-hidden">Loading passkeys...</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="alert alert-danger" role="alert">
        <i className="bi bi-exclamation-triangle-fill me-2"></i>
        {error}
        <button className="btn btn-sm btn-outline-danger ms-2" onClick={fetchPasskeys}>
          Retry
        </button>
      </div>
    );
  }

  return (
    <div>
      {/* Passkey List */}
      {passkeys.length === 0 ? (
        <div className="alert alert-info" role="alert">
          <i className="bi bi-info-circle-fill me-2"></i>
          No passkeys registered yet. Register a passkey to enable MFA.
        </div>
      ) : (
        <div className="list-group mb-3">
          {passkeys.map((passkey) => (
            <div key={passkey.id} className="list-group-item d-flex justify-content-between align-items-center">
              <div>
                <h6 className="mb-1">
                  <i className="bi bi-key-fill me-2 text-primary"></i>
                  {passkey.credentialName}
                </h6>
                <small className="text-muted">
                  Created: {formatDate(passkey.createdAt)}
                  {passkey.lastUsedAt && (
                    <span className="ms-3">
                      Last used: {formatDate(passkey.lastUsedAt)}
                    </span>
                  )}
                </small>
              </div>
              <button
                className="btn btn-sm btn-outline-danger"
                onClick={() => handleDeletePasskey(passkey.id, passkey.credentialName)}
                title="Delete passkey"
              >
                <i className="bi bi-trash"></i>
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Register New Passkey Button */}
      <button
        className="btn btn-primary"
        onClick={() => setShowRegisterModal(true)}
      >
        <i className="bi bi-plus-circle me-2"></i>
        Register New Passkey
      </button>

      {/* Register Modal */}
      {showRegisterModal && (
        <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">Register New Passkey</h5>
                <button
                  type="button"
                  className="btn-close"
                  onClick={() => {
                    setShowRegisterModal(false);
                    setNewPasskeyName('');
                    setRegisterError(null);
                  }}
                  disabled={registering}
                ></button>
              </div>
              <div className="modal-body">
                {registerError && (
                  <div className="alert alert-danger" role="alert">
                    <i className="bi bi-exclamation-triangle-fill me-2"></i>
                    {registerError}
                  </div>
                )}
                <div className="mb-3">
                  <label htmlFor="passkeyName" className="form-label">
                    Passkey Name
                  </label>
                  <input
                    type="text"
                    className="form-control"
                    id="passkeyName"
                    placeholder="e.g., iPhone, YubiKey, Windows Hello"
                    value={newPasskeyName}
                    onChange={(e) => setNewPasskeyName(e.target.value)}
                    disabled={registering}
                    autoFocus
                  />
                  <div className="form-text">
                    Choose a name to help you identify this passkey later
                  </div>
                </div>
              </div>
              <div className="modal-footer">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => {
                    setShowRegisterModal(false);
                    setNewPasskeyName('');
                    setRegisterError(null);
                  }}
                  disabled={registering}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={handleRegisterPasskey}
                  disabled={registering || !newPasskeyName.trim()}
                >
                  {registering ? (
                    <>
                      <span className="spinner-border spinner-border-sm me-2" role="status"></span>
                      Registering...
                    </>
                  ) : (
                    <>
                      <i className="bi bi-plus-circle me-2"></i>
                      Register
                    </>
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
