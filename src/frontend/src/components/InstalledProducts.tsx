import React, { useEffect, useState } from 'react';
import { getInstalledProducts, type InstalledProductResponse } from '../services/installedProductService';

const InstalledProducts: React.FC = () => {
  const [products, setProducts] = useState<InstalledProductResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [totalSystems, setTotalSystems] = useState(0);

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      setLoading(true);
      getInstalledProducts(search)
        .then((response) => {
          setProducts(response.products ?? []);
          setTotalSystems(response.totalSystems);
          setError(null);
        })
        .catch((err) => setError(err instanceof Error ? err.message : 'Failed to load installed products'))
        .finally(() => setLoading(false));
    }, 250);

    return () => window.clearTimeout(timeout);
  }, [search]);

  return (
    <div className="container-fluid py-4">
      <div className="d-flex justify-content-between align-items-center mb-4">
        <div>
          <h1 className="h3 mb-1">Installed products</h1>
          <p className="text-muted mb-0">Products imported from CrowdStrike Discover for systems known in Secman.</p>
        </div>
        <div className="text-end">
          <div className="fw-semibold">{products.length}</div>
          <div className="text-muted small">rows shown across {totalSystems} systems</div>
        </div>
      </div>

      <div className="card mb-3">
        <div className="card-body">
          <label className="form-label" htmlFor="installed-product-search">Search products, vendors, versions, or systems</label>
          <input
            id="installed-product-search"
            className="form-control"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="e.g. Chrome, Microsoft, server01"
          />
        </div>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}

      <div className="card">
        <div className="table-responsive">
          <table className="table table-hover align-middle mb-0">
            <thead className="table-light">
              <tr>
                <th>Product</th>
                <th>Vendor</th>
                <th>Version</th>
                <th>System</th>
                <th>Category</th>
                <th>Last imported</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={6} className="text-center py-4">Loading installed products...</td></tr>
              ) : products.length === 0 ? (
                <tr><td colSpan={6} className="text-center py-4 text-muted">No installed products found.</td></tr>
              ) : products.map((product) => (
                <tr key={product.id}>
                  <td className="fw-semibold">{product.name}</td>
                  <td>{product.vendor || '—'}</td>
                  <td><code>{product.version || '—'}</code></td>
                  <td><a href={`/assets/${product.assetId}`}>{product.hostname}</a></td>
                  <td>{product.category || '—'}</td>
                  <td>{new Date(product.importedAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default InstalledProducts;
