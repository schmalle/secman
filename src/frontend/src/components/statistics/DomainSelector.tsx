/**
 * React component for domain filter dropdown
 *
 * Displays a dropdown selector for filtering vulnerability statistics by AD domain.
 * Features:
 * - Fetches available domains from backend independently
 * - Shows loading state while fetching
 * - Shows error state with fallback to "All Domains"
 * - Persists selection in sessionStorage
 * - Shows asset count for selected domain
 *
 * Feature: 059-vuln-stats-domain-filter
 * Task: T011 [US1]
 * Spec reference: spec.md FR-001, FR-002, FR-003, FR-009, FR-010
 * User Story: US1 - Filter Statistics by Domain (P1)
 */

import React, { useEffect, useState, useCallback } from 'react';
import { vulnerabilityStatisticsApi, type AvailableDomainsDto } from '../../services/api/vulnerabilityStatisticsApi';

/** Session storage key for domain persistence (per spec) */
const STORAGE_KEY = 'secman.vuln-stats.selectedDomain';

interface DomainSelectorProps {
  /** Currently selected domain (null = All Domains) */
  selectedDomain: string | null;
  /** Callback when domain selection changes */
  onDomainChange: (domain: string | null) => void;
}

/**
 * DomainSelector component for filtering vulnerability statistics by AD domain
 *
 * Feature: 059-vuln-stats-domain-filter
 * Task: T011 [US1], T018-T020 [US2], T021-T022 [US3]
 */
export default function DomainSelector({ selectedDomain, onDomainChange }: DomainSelectorProps) {
  const [domainsData, setDomainsData] = useState<AvailableDomainsDto | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Fetch available domains on mount
  useEffect(() => {
    const fetchDomains = async () => {
      try {
        setLoading(true);
        setError(null);
        const result = await vulnerabilityStatisticsApi.getAvailableDomains();
        setDomainsData(result);
      } catch (err) {
        console.error('Error fetching available domains:', err);
        setError('Failed to load domains');
      } finally {
        setLoading(false);
      }
    };

    fetchDomains();
  }, []);

  // Read from sessionStorage on mount (US2: Persist Domain Selection)
  useEffect(() => {
    const storedDomain = sessionStorage.getItem(STORAGE_KEY);
    if (storedDomain && storedDomain !== selectedDomain) {
      onDomainChange(storedDomain);
    }
  }, []);

  // Handle domain selection change
  const handleChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    const domain = value === '' ? null : value;

    // Update parent state
    onDomainChange(domain);

    // Persist to sessionStorage (US2)
    if (domain) {
      sessionStorage.setItem(STORAGE_KEY, domain);
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
  }, [onDomainChange]);

  // Get asset count for selected domain
  const getAssetCountForDomain = (domain: string | null): number | null => {
    if (!domainsData) return null;
    if (domain === null) return domainsData.totalAssetCount;
    // For individual domain, we'd need per-domain counts from backend
    // For now, just return null for individual domains
    return null;
  };

  // Loading state (FR-009)
  if (loading) {
    return (
      <div className="d-flex align-items-center gap-2">
        <label className="form-label mb-0 text-muted">
          <i className="bi bi-building me-1"></i>
          Domain Filter:
        </label>
        <select className="form-select form-select-sm" style={{ width: 'auto', minWidth: '180px' }} disabled>
          <option>Loading domains...</option>
        </select>
        <div className="spinner-border spinner-border-sm text-muted" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  // Error state (FR-010) - still allow "All Domains" to work
  if (error) {
    return (
      <div className="d-flex align-items-center gap-2">
        <label className="form-label mb-0 text-muted">
          <i className="bi bi-building me-1"></i>
          Domain Filter:
        </label>
        <select
          className="form-select form-select-sm is-invalid"
          style={{ width: 'auto', minWidth: '180px' }}
          value=""
          onChange={handleChange}
        >
          <option value="">All Domains</option>
        </select>
        <small className="text-danger">
          <i className="bi bi-exclamation-triangle me-1"></i>
          {error}
        </small>
      </div>
    );
  }

  // No domains available
  if (!domainsData || domainsData.domains.length === 0) {
    return (
      <div className="d-flex align-items-center gap-2">
        <label className="form-label mb-0 text-muted">
          <i className="bi bi-building me-1"></i>
          Domain Filter:
        </label>
        <select className="form-select form-select-sm" style={{ width: 'auto', minWidth: '180px' }} disabled>
          <option>No domains available</option>
        </select>
      </div>
    );
  }

  const assetCount = getAssetCountForDomain(selectedDomain);

  return (
    <div className="d-flex align-items-center gap-2 flex-wrap">
      <label className="form-label mb-0 text-muted">
        <i className="bi bi-building me-1"></i>
        Domain Filter:
      </label>
      <select
        className="form-select form-select-sm"
        style={{ width: 'auto', minWidth: '180px' }}
        value={selectedDomain || ''}
        onChange={handleChange}
        aria-label="Select domain to filter statistics"
      >
        <option value="">All Domains</option>
        {domainsData.domains.map((domain) => (
          <option key={domain} value={domain}>
            {domain}
          </option>
        ))}
      </select>

      {/* Asset count display (US3: Display Domain Context) */}
      {assetCount !== null && (
        <span className="badge bg-secondary">
          {assetCount.toLocaleString()} assets
        </span>
      )}

      {/* Active filter indicator (US3: Display Domain Context) */}
      {selectedDomain && (
        <span className="badge bg-info text-dark">
          <i className="bi bi-funnel-fill me-1"></i>
          Filtered: {selectedDomain}
        </span>
      )}
    </div>
  );
}
