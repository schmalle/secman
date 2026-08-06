import React, { useEffect, useState } from 'react';
import { authenticatedGet } from '../utils/auth';

interface AssetIdRedirectProps {
  assetId: number;
}

interface AssetResponse {
  name: string;
}

const AssetIdRedirect: React.FC<AssetIdRedirectProps> = ({ assetId }) => {
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    const redirect = async () => {
      try {
        const response = await authenticatedGet(`/api/assets/${assetId}`);
        if (!response.ok) {
          throw new Error(response.status === 404 ? 'Asset not found' : `Failed to load asset: ${response.status}`);
        }

        const asset = await response.json() as AssetResponse;
        if (!asset.name) {
          throw new Error('Asset response did not include a name');
        }

        window.location.replace(`/vulnerabilities/system?hostname=${encodeURIComponent(asset.name)}`);
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to open asset');
        }
      }
    };

    // eslint-disable-next-line react-hooks/set-state-in-effect
    redirect();

    return () => {
      cancelled = true;
    };
  }, [assetId]);

  return (
    <div className="container-fluid py-4">
      <div className="card">
        <div className="card-body">
          <h1 className="h4 mb-3">Opening asset</h1>
          {error ? (
            <div className="alert alert-danger mb-3" role="alert">
              <i className="bi bi-exclamation-triangle me-2"></i>
              {error}
            </div>
          ) : (
            <div className="d-flex align-items-center text-muted">
              <span className="spinner-border spinner-border-sm me-2" role="status"></span>
              Loading asset...
            </div>
          )}
          <a href="/assets" className="btn btn-outline-secondary mt-3">
            Back to assets
          </a>
        </div>
      </div>
    </div>
  );
};

export default AssetIdRedirect;
