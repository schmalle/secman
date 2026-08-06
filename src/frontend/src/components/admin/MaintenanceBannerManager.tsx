import { useState } from 'react';
import MaintenanceBannerForm from './MaintenanceBannerForm';
import MaintenanceBannerList from './MaintenanceBannerList';
import type { MaintenanceBanner } from '../../services/maintenanceBannerService';

/**
 * Maintenance Banner Manager Component
 *
 * Combines form and list components for complete banner management.
 *
 * Features:
 * - Create new banners
 * - Edit existing banners
 * - Delete banners with confirmation
 * - View all banners (past, current, future)
 * - Responsive layout
 *
 * State management:
 * - editingBanner: null (create mode) or Banner (edit mode)
 * - refreshKey: Triggers list refresh after create/update/delete
 *
 * Used in: pages/admin/maintenance-banners.astro
 */
export default function MaintenanceBannerManager() {
  const [editingBanner, setEditingBanner] = useState<MaintenanceBanner | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  /**
   * Handle successful form submission (create or update).
   * Resets form to create mode and refreshes list.
   */
  const handleSuccess = () => {
    setEditingBanner(null);
    setRefreshKey(prev => prev + 1); // Trigger list refresh
  };

  /**
   * Handle cancel action in form.
   * Resets form to create mode.
   */
  const handleCancel = () => {
    setEditingBanner(null);
  };

  /**
   * Handle edit action from list.
   * Sets form to edit mode with selected banner.
   */
  const handleEdit = (banner: MaintenanceBanner) => {
    setEditingBanner(banner);
    // Scroll to form
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  /**
   * Handle delete action from list.
   * Refreshes list after deletion.
   */
  const handleRefresh = () => {
    setRefreshKey(prev => prev + 1); // Trigger list refresh
  };

  return (
    <div>
      <div className="row mt-4">
        <div className="col-lg-6">
          {/* Form Component - Create or Edit */}
          <MaintenanceBannerForm
            banner={editingBanner || undefined}
            onSuccess={handleSuccess}
            onCancel={editingBanner ? handleCancel : undefined}
          />

          {/* Instructions Card */}
          <div className="card mt-4">
            <div className="card-header">
              <h5 className="mb-0">Usage Instructions</h5>
            </div>
            <div className="card-body">
              <h6>Creating a Banner</h6>
              <ol>
                <li>Enter your maintenance message (plain text, 1-2000 characters)</li>
                <li>Set the start time when the banner should appear</li>
                <li>Set the end time when the banner should disappear</li>
                <li>Times are entered in your local timezone and automatically converted to UTC</li>
                <li>Click "Create Banner" to save</li>
              </ol>

              <h6 className="mt-3">Editing a Banner</h6>
              <ol>
                <li>Click the <i className="bi bi-pencil"></i> edit button in the list below</li>
                <li>Modify the message or time range as needed</li>
                <li>Click "Update Banner" to save changes</li>
                <li>Click "Cancel" to discard changes</li>
              </ol>

              <h6 className="mt-3">Important Notes</h6>
              <ul>
                <li>Banners are visible to ALL users (authenticated and unauthenticated)</li>
                <li>Multiple active banners will stack vertically (newest first)</li>
                <li>Banners activate automatically at the start time</li>
                <li>End time must be after start time</li>
                <li>Messages are plain text only (HTML/Markdown not supported)</li>
              </ul>

              <h6 className="mt-3">Examples</h6>
              <p className="mb-2"><strong>Scheduled Maintenance:</strong><br />
              <small>"System maintenance tonight 8-10 PM UTC. Some features may be temporarily unavailable."</small></p>

              <p className="mb-2"><strong>Ongoing Issue:</strong><br />
              <small>"We are experiencing intermittent connectivity issues. Our team is working on a resolution."</small></p>

              <p className="mb-0"><strong>Planned Downtime:</strong><br />
              <small>"Scheduled downtime on November 16th from 2-4 AM UTC for database upgrades."</small></p>
            </div>
          </div>
        </div>

        <div className="col-lg-6">
          {/* List Component - View All with Edit/Delete */}
          <MaintenanceBannerList
            key={refreshKey}
            onEdit={handleEdit}
            onRefresh={handleRefresh}
          />
        </div>
      </div>
    </div>
  );
}
