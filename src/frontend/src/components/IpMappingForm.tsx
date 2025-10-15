import React, { useState } from 'react';
import type { CreateUserMappingRequest, UserMapping } from '../services/userMappingService';

interface IpMappingFormProps {
  onSubmit: (request: CreateUserMappingRequest) => Promise<void>;
  onCancel: () => void;
  initialData?: UserMapping;
  isEditing?: boolean;
}

/**
 * Form component for creating/editing IP address mappings
 * Feature: 020-i-want-to (IP Address Mapping)
 *
 * Supports:
 * - Single IP addresses (192.168.1.100)
 * - CIDR notation (10.0.0.0/24)
 * - Dash ranges (172.16.0.1-172.16.0.100)
 * - Combined AWS account + IP mapping
 */
export default function IpMappingForm({ onSubmit, onCancel, initialData, isEditing = false }: IpMappingFormProps) {
  const [email, setEmail] = useState(initialData?.email || '');
  const [awsAccountId, setAwsAccountId] = useState(initialData?.awsAccountId || '');
  const [domain, setDomain] = useState(initialData?.domain || '');
  const [ipAddress, setIpAddress] = useState(initialData?.ipAddress || '');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');

  const validateForm = (): boolean => {
    if (!email || !email.includes('@')) {
      setError('Valid email address is required');
      return false;
    }

    if (!awsAccountId && !ipAddress) {
      setError('At least one of AWS Account ID or IP Address must be provided');
      return false;
    }

    if (awsAccountId && awsAccountId.length !== 12) {
      setError('AWS Account ID must be exactly 12 digits');
      return false;
    }

    // Basic IP format validation (backend will do comprehensive validation)
    if (ipAddress) {
      const singleIpPattern = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/;
      const cidrPattern = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\/\d{1,2}$/;
      const dashRangePattern = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}-\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/;

      if (!singleIpPattern.test(ipAddress) && !cidrPattern.test(ipAddress) && !dashRangePattern.test(ipAddress)) {
        setError('IP address must be in format: 192.168.1.100, 10.0.0.0/24, or 172.16.0.1-172.16.0.100');
        return false;
      }
    }

    return true;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!validateForm()) {
      return;
    }

    setIsSubmitting(true);

    try {
      const request: CreateUserMappingRequest = {
        email: email.trim(),
        awsAccountId: awsAccountId.trim() || undefined,
        domain: domain.trim() || undefined,
        ipAddress: ipAddress.trim() || undefined,
      };

      await onSubmit(request);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save mapping');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="card">
      <div className="card-header">
        <h5 className="mb-0">{isEditing ? 'Edit' : 'Create'} IP Mapping</h5>
      </div>
      <div className="card-body">
        {error && (
          <div className="alert alert-danger" role="alert">
            {error}
          </div>
        )}

        <div className="mb-3">
          <label htmlFor="email" className="form-label">
            Email Address <span className="text-danger">*</span>
          </label>
          <input
            type="email"
            className="form-control"
            id="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            disabled={isEditing}
            placeholder="user@example.com"
          />
          <small className="form-text text-muted">
            User's email address for the mapping
          </small>
        </div>

        <div className="mb-3">
          <label htmlFor="ipAddress" className="form-label">
            IP Address / Range
          </label>
          <input
            type="text"
            className="form-control"
            id="ipAddress"
            value={ipAddress}
            onChange={(e) => setIpAddress(e.target.value)}
            placeholder="192.168.1.100 or 10.0.0.0/24 or 172.16.0.1-172.16.0.100"
          />
          <small className="form-text text-muted">
            Single IP (192.168.1.100), CIDR (10.0.0.0/24), or dash range (172.16.0.1-172.16.0.100)
          </small>
        </div>

        <div className="mb-3">
          <label htmlFor="awsAccountId" className="form-label">
            AWS Account ID
          </label>
          <input
            type="text"
            className="form-control"
            id="awsAccountId"
            value={awsAccountId}
            onChange={(e) => setAwsAccountId(e.target.value)}
            placeholder="123456789012"
            maxLength={12}
          />
          <small className="form-text text-muted">
            12-digit AWS account ID (optional if IP address provided)
          </small>
        </div>

        <div className="mb-3">
          <label htmlFor="domain" className="form-label">
            Domain
          </label>
          <input
            type="text"
            className="form-control"
            id="domain"
            value={domain}
            onChange={(e) => setDomain(e.target.value)}
            placeholder="example.com"
          />
          <small className="form-text text-muted">
            Organizational domain (optional)
          </small>
        </div>
      </div>

      <div className="card-footer">
        <button
          type="submit"
          className="btn btn-primary"
          disabled={isSubmitting}
        >
          {isSubmitting ? (
            <>
              <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
              Saving...
            </>
          ) : (
            isEditing ? 'Update Mapping' : 'Create Mapping'
          )}
        </button>
        <button
          type="button"
          className="btn btn-secondary ms-2"
          onClick={onCancel}
          disabled={isSubmitting}
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
