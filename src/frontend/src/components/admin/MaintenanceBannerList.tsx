import { useState, useEffect } from 'react';
import {
  getAllBanners,
  deleteBanner,
  type MaintenanceBanner
} from '../../services/maintenanceBannerService';

interface MaintenanceBannerListProps {
  onEdit?: (banner: MaintenanceBanner) => void;
  onRefresh?: () => void;
}

/**
 * Maintenance Banner List Component
 *
 * Displays all maintenance banners (past, current, future) with management actions.
 *
 * Features:
 * - Fetches all banners on mount
 * - Displays status badge (active/upcoming/expired)
 * - Shows local timezone-converted times
 * - Edit/Delete actions for each banner
 * - Delete confirmation dialog
 * - Responsive table design
 *
 * Used in: admin/maintenance-banners.astro
 */
export default function MaintenanceBannerList({ onEdit, onRefresh }: MaintenanceBannerListProps) {
  const [banners, setBanners] = useState<MaintenanceBanner[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<number | null>(null);
  const [deleting, setDeleting] = useState(false);

  /**
   * Fetch all maintenance banners from the API.
   */
  const fetchBanners = async () => {
    try {
      setLoading(true);
      const allBanners = await getAllBanners();
      setBanners(allBanners);
      setError(null);
    } catch (err: any) {
      console.error('Failed to fetch maintenance banners:', err);
      setError(err.response?.data?.message || 'Failed to load banners');
    } finally {
      setLoading(false);
    }
  };

  /**
   * Handle banner deletion with confirmation.
   */
  const handleDelete = async (id: number) => {
    try {
      setDeleting(true);
      await deleteBanner(id);

      // Refresh list
      await fetchBanners();

      // Close confirmation dialog
      setDeleteConfirm(null);

      // Trigger parent refresh if needed
      if (onRefresh) {
        onRefresh();
      }
    } catch (err: any) {
      console.error('Failed to delete banner:', err);
      setError(err.response?.data?.message || 'Failed to delete banner');
    } finally {
      setDeleting(false);
    }
  };

  /**
   * Initial fetch on component mount.
   */
  useEffect(() => {
    fetchBanners();
  }, []);

  /**
   * Format ISO-8601 timestamp to local readable format.
   */
  const formatTime = (isoString: string): string => {
    const date = new Date(isoString);
    return date.toLocaleString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  };

  /**
   * Get status badge for a banner based on current time.
   */
  const getStatusBadge = (banner: MaintenanceBanner) => {
    if (banner.status === 'ACTIVE') {
      return <span className="badge bg-success">Active</span>;
    } else if (banner.status === 'UPCOMING') {
      return <span className="badge bg-primary">Upcoming</span>;
    } else {
      return <span className="badge bg-secondary">Expired</span>;
    }
  };

  if (loading) {
    return (
      <div className="text-center p-4">
        <div className="spinner-border text-primary" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  return (
    <div className="card">
      <div className="card-header d-flex justify-content-between align-items-center">
        <h5 className="mb-0">All Maintenance Banners</h5>
        <button
          className="btn btn-sm btn-outline-secondary"
          onClick={fetchBanners}
          disabled={loading}
        >
          <i className="bi bi-arrow-clockwise me-1"></i>
          Refresh
        </button>
      </div>
      <div className="card-body">
        {/* Error Alert */}
        {error && (
          <div className="alert alert-danger alert-dismissible fade show" role="alert">
            {error}
            <button
              type="button"
              className="btn-close"
              onClick={() => setError(null)}
              aria-label="Close"
            ></button>
          </div>
        )}

        {/* Empty State */}
        {banners.length === 0 ? (
          <div className="text-center text-muted p-4">
            <i className="bi bi-inbox fs-1 d-block mb-2"></i>
            <p>No maintenance banners found</p>
          </div>
        ) : (
          /* Banners Table */
          <div className="table-responsive">
            <table className="table table-hover">
              <thead>
                <tr>
                  <th>Status</th>
                  <th>Message</th>
                  <th>Start Time</th>
                  <th>End Time</th>
                  <th>Created By</th>
                  <th className="text-end">Actions</th>
                </tr>
              </thead>
              <tbody>
                {banners.map((banner) => (
                  <tr key={banner.id}>
                    <td>{getStatusBadge(banner)}</td>
                    <td>
                      <div className="text-truncate" style={{ maxWidth: '300px' }}>
                        {banner.message}
                      </div>
                    </td>
                    <td>
                      <small>{formatTime(banner.startTime)}</small>
                    </td>
                    <td>
                      <small>{formatTime(banner.endTime)}</small>
                    </td>
                    <td>
                      <small className="text-muted">{banner.createdByUsername}</small>
                    </td>
                    <td className="text-end">
                      <div className="btn-group btn-group-sm">
                        {/* Edit Button */}
                        <button
                          className="btn btn-outline-primary"
                          onClick={() => onEdit && onEdit(banner)}
                          title="Edit banner"
                        >
                          <i className="bi bi-pencil"></i>
                        </button>

                        {/* Delete Button */}
                        <button
                          className="btn btn-outline-danger"
                          onClick={() => setDeleteConfirm(banner.id)}
                          title="Delete banner"
                        >
                          <i className="bi bi-trash"></i>
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Delete Confirmation Modal */}
      {deleteConfirm !== null && (
        <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">Confirm Delete</h5>
                <button
                  type="button"
                  className="btn-close"
                  onClick={() => setDeleteConfirm(null)}
                  disabled={deleting}
                  aria-label="Close"
                ></button>
              </div>
              <div className="modal-body">
                <p>Are you sure you want to delete this maintenance banner?</p>
                <p className="text-muted mb-0">
                  <small>This action cannot be undone.</small>
                </p>
              </div>
              <div className="modal-footer">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setDeleteConfirm(null)}
                  disabled={deleting}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="btn btn-danger"
                  onClick={() => handleDelete(deleteConfirm)}
                  disabled={deleting}
                >
                  {deleting ? (
                    <>
                      <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                      Deleting...
                    </>
                  ) : (
                    <>
                      <i className="bi bi-trash me-2"></i>
                      Delete Banner
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
