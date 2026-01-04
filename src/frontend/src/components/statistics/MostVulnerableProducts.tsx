/**
 * React component for displaying most vulnerable products table
 *
 * Displays top 10 products ranked by distinct vulnerability count.
 * Features:
 * - Bootstrap table with responsive design
 * - Critical/High severity counts with color coding
 * - Loading, error, and empty states
 *
 * Feature: 036-vuln-stats-lense
 */

import React, { useEffect, useState } from 'react';
import { vulnerabilityStatisticsApi, type MostVulnerableProductDto } from '../../services/api/vulnerabilityStatisticsApi';

/**
 * Props for MostVulnerableProducts component
 *
 * Feature: 059-vuln-stats-domain-filter
 * Task: T013 [US1]
 */
interface MostVulnerableProductsProps {
  /** Optional AD domain filter (null = all domains) */
  domain?: string | null;
}

export default function MostVulnerableProducts({ domain }: MostVulnerableProductsProps = {}) {
  const [data, setData] = useState<MostVulnerableProductDto[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);
        const result = await vulnerabilityStatisticsApi.getMostVulnerableProducts(domain);
        setData(result);
      } catch (err) {
        console.error('Error fetching most vulnerable products:', err);
        setError('Failed to load product statistics. Please try again later.');
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [domain]); // Re-fetch when domain changes

  // Loading state
  if (loading) {
    return (
      <div className="card">
        <div className="card-body text-center py-5">
          <div className="spinner-border text-primary" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
          <p className="mt-3 text-muted">Loading product statistics...</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="card">
        <div className="card-body">
          <div className="alert alert-danger" role="alert">
            <i className="bi bi-exclamation-triangle me-2"></i>
            {error}
          </div>
        </div>
      </div>
    );
  }

  // Empty state
  if (data.length === 0) {
    return (
      <div className="card">
        <div className="card-body text-center py-5">
          <i className="bi bi-inbox display-4 text-muted"></i>
          <p className="mt-3 text-muted">No product vulnerability data available.</p>
          <small className="text-muted">
            This could mean no vulnerabilities with product information have been imported yet.
          </small>
        </div>
      </div>
    );
  }

  // Data table
  return (
    <div className="card">
      <div className="card-header bg-info text-white">
        <h5 className="mb-0">
          <i className="bi bi-box-seam me-2"></i>
          Top 10 Most Vulnerable Products
        </h5>
      </div>
      <div className="card-body p-0">
        <div className="table-responsive">
          <table className="table table-hover mb-0">
            <thead className="table-light">
              <tr>
                <th scope="col">#</th>
                <th scope="col">Product</th>
                <th scope="col">Vulnerabilities</th>
                <th scope="col">Affected Assets</th>
                <th scope="col">Critical</th>
                <th scope="col">High</th>
              </tr>
            </thead>
            <tbody>
              {data.map((product, index) => (
                <tr key={`${product.product}-${index}`}>
                  <td className="align-middle">{index + 1}</td>
                  <td className="align-middle">
                    <strong>{product.product}</strong>
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-light text-dark">
                      {product.vulnerabilityCount.toLocaleString()}
                    </span>
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-light text-dark">
                      {product.affectedAssetCount.toLocaleString()}
                    </span>
                  </td>
                  <td className="align-middle">
                    {product.criticalCount > 0 ? (
                      <span className="badge bg-danger">
                        {product.criticalCount.toLocaleString()}
                      </span>
                    ) : (
                      <span className="badge bg-light text-muted">0</span>
                    )}
                  </td>
                  <td className="align-middle">
                    {product.highCount > 0 ? (
                      <span className="badge bg-warning text-dark">
                        {product.highCount.toLocaleString()}
                      </span>
                    ) : (
                      <span className="badge bg-light text-muted">0</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card-footer text-muted small">
        <i className="bi bi-info-circle me-1"></i>
        Showing top 10 products ranked by distinct vulnerability count.
      </div>
    </div>
  );
}
