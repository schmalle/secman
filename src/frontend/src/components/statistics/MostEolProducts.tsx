/**
 * "Most Often EOL Products" table for the vulnerability statistics page.
 *
 * Ranks products by how many accessible assets run a version that is past
 * end-of-life. Deliberately coarser than the EOL page's own component list, which
 * groups by release cycle and so lists one product once per cycle — Universal
 * Forwarder appears there three times for 9.0, 9.2 and 8.1, which answers "which
 * version is EOL" rather than "which product is most often EOL".
 *
 * Honours the page's domain and AWS-hosted filters like every sibling card; a
 * filter that silently skipped one card would read as a bug.
 */

import React from 'react';
import { vulnerabilityStatisticsApi, type TopEolProductDto } from '../../services/api/vulnerabilityStatisticsApi';
import { ChartCardEmpty, ChartCardError, ChartCardLoading } from './ChartCardStates';
import { useChartData } from './useChartData';

interface MostEolProductsProps {
  /** Optional AD domain filter (null = all domains) */
  domain?: string | null;
  /** Optional AWS hosted filter (true = only cloud-hosted assets) */
  awsHosted?: boolean;
}

export default function MostEolProducts({ domain, awsHosted }: MostEolProductsProps = {}) {
  const { data: fetched, loading, error } = useChartData<TopEolProductDto[]>(
    () => vulnerabilityStatisticsApi.getTopEolProducts(domain, awsHosted),
    [domain, awsHosted],
    'Failed to load end-of-life product statistics. Please try again later.',
    'top EOL products',
  );
  const data = fetched ?? [];

  if (loading) return <ChartCardLoading label="Loading end-of-life statistics..." />;
  if (error) return <ChartCardError message={error} />;
  if (data.length === 0) {
    return (
      <ChartCardEmpty
        heading={{ icon: 'bi-hourglass-bottom', title: 'Top 10 Most Often EOL Products' }}
        message="No end-of-life products found in your accessible scope."
        hint="This could mean the EOL catalogue has not been synced yet, or no installed product matches a known end-of-life release."
      />
    );
  }

  return (
    <div className="card">
      <div className="card-header" style={{ backgroundColor: 'var(--scand-bg-header)', color: 'var(--scand-text-light)' }}>
        <h5 className="mb-0">
          <i className="bi bi-hourglass-bottom me-2"></i>
          Top 10 Most Often EOL Products
        </h5>
      </div>
      <div className="card-body p-0">
        <div className="table-responsive">
          <table className="table table-hover mb-0">
            <thead className="table-light">
              <tr>
                <th scope="col">#</th>
                <th scope="col">Product</th>
                <th scope="col">Affected Systems</th>
                <th scope="col">Approaching EOL</th>
                <th scope="col">EOL Versions</th>
              </tr>
            </thead>
            <tbody>
              {data.map((product, index) => (
                <tr
                  key={`${product.product}-${index}`}
                  onClick={() => {
                    window.location.href = `/vulnerabilities/eol/products/${encodeURIComponent(product.product)}`;
                  }}
                  style={{ cursor: 'pointer' }}
                  title={`Click to view systems affected by ${product.product}`}
                >
                  <td className="align-middle">{index + 1}</td>
                  <td className="align-middle">
                    <strong>{product.product}</strong>
                  </td>
                  <td className="align-middle">
                    <span className="badge scand-critical">
                      {product.affectedAssets.toLocaleString()}
                    </span>
                  </td>
                  <td className="align-middle">
                    {product.approachingAssets > 0 ? (
                      <span className="badge scand-high">
                        {product.approachingAssets.toLocaleString()}
                      </span>
                    ) : (
                      <span className="badge scand-neutral">0</span>
                    )}
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-light text-dark">
                      {product.eolVersions.toLocaleString()}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card-footer text-muted small">
        <i className="bi bi-info-circle me-1"></i>
        Showing top 10 products ranked by the number of systems running a version that is past
        end-of-life. A system counts once per product, however many of its versions are affected.
        Click any row for the affected systems.
      </div>
    </div>
  );
}
