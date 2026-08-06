import { useState } from 'react';
import { createBanner, updateBanner, type MaintenanceBanner, type MaintenanceBannerRequest } from '../../services/maintenanceBannerService';

interface MaintenanceBannerFormProps {
  banner?: MaintenanceBanner;  // If editing, pass existing banner
  onSuccess?: () => void;       // Callback after successful save
  onCancel?: () => void;        // Callback for cancel action
}

/**
 * Form for creating or editing maintenance banners.
 *
 * Features:
 * - Create new banner or edit existing
 * - Timezone conversion (admin enters local time, stored as UTC)
 * - Validation (message 1-2000 chars, end after start)
 * - Bootstrap 5.3 styling
 *
 * @param props Form properties (banner, onSuccess, onCancel)
 */
export default function MaintenanceBannerForm({ banner, onSuccess, onCancel }: MaintenanceBannerFormProps) {
  const [message, setMessage] = useState(banner?.message || '');
  const [startTime, setStartTime] = useState(
    banner?.startTime ? formatDatetimeLocal(banner.startTime) : ''
  );
  const [endTime, setEndTime] = useState(
    banner?.endTime ? formatDatetimeLocal(banner.endTime) : ''
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isEditing = !!banner;

  /**
   * Handle form submission.
   * Converts local datetime to UTC ISO-8601 for backend.
   */
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    // Validation
    if (message.length < 1 || message.length > 2000) {
      setError('Message must be between 1 and 2000 characters');
      return;
    }

    if (!startTime || !endTime) {
      setError('Both start and end times are required');
      return;
    }

    // Convert local datetime to UTC ISO-8601
    const startUTC = new Date(startTime).toISOString();
    const endUTC = new Date(endTime).toISOString();

    // Validate end after start
    if (new Date(endUTC) <= new Date(startUTC)) {
      setError('End time must be after start time');
      return;
    }

    const request: MaintenanceBannerRequest = {
      message,
      startTime: startUTC,
      endTime: endUTC
    };

    try {
      setLoading(true);

      if (isEditing && banner) {
        await updateBanner(banner.id, request);
      } else {
        await createBanner(request);
      }

      // Reset form
      setMessage('');
      setStartTime('');
      setEndTime('');

      // Callback
      if (onSuccess) {
        onSuccess();
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to save banner');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="card mb-4">
      <div className="card-header">
        <h5 className="mb-0">
          {isEditing ? 'Edit Maintenance Banner' : 'Create Maintenance Banner'}
        </h5>
      </div>
      <div className="card-body">
        <form onSubmit={handleSubmit}>
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

          {/* Message Field */}
          <div className="mb-3">
            <label htmlFor="message" className="form-label">
              Message <span className="text-danger">*</span>
            </label>
            <textarea
              id="message"
              className="form-control"
              rows={3}
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              required
              minLength={1}
              maxLength={2000}
              placeholder="Enter maintenance message (plain text)"
            ></textarea>
            <div className="form-text">
              {message.length} / 2000 characters
            </div>
          </div>

          {/* Start Time Field */}
          <div className="mb-3">
            <label htmlFor="startTime" className="form-label">
              Start Time (Local) <span className="text-danger">*</span>
            </label>
            <input
              type="datetime-local"
              id="startTime"
              className="form-control"
              value={startTime}
              onChange={(e) => setStartTime(e.target.value)}
              required
            />
            <div className="form-text">
              Enter time in your local timezone
            </div>
          </div>

          {/* End Time Field */}
          <div className="mb-3">
            <label htmlFor="endTime" className="form-label">
              End Time (Local) <span className="text-danger">*</span>
            </label>
            <input
              type="datetime-local"
              id="endTime"
              className="form-control"
              value={endTime}
              onChange={(e) => setEndTime(e.target.value)}
              required
            />
            <div className="form-text">
              Enter time in your local timezone
            </div>
          </div>

          {/* Action Buttons */}
          <div className="d-flex gap-2">
            <button
              type="submit"
              className="btn btn-primary"
              disabled={loading}
            >
              {loading ? (
                <>
                  <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                  {isEditing ? 'Updating...' : 'Creating...'}
                </>
              ) : (
                <>{isEditing ? 'Update Banner' : 'Create Banner'}</>
              )}
            </button>

            {onCancel && (
              <button
                type="button"
                className="btn btn-secondary"
                onClick={onCancel}
                disabled={loading}
              >
                Cancel
              </button>
            )}
          </div>
        </form>
      </div>
    </div>
  );
}

/**
 * Format ISO-8601 timestamp to datetime-local input format.
 * Converts UTC to local timezone.
 *
 * @param isoString ISO-8601 timestamp string
 * @returns Formatted string for datetime-local input (YYYY-MM-DDTHH:MM)
 */
function formatDatetimeLocal(isoString: string): string {
  const date = new Date(isoString);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');
  return `${year}-${month}-${day}T${hours}:${minutes}`;
}
