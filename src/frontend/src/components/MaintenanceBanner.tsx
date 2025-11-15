import { useState, useEffect } from 'react';
import { getActiveBanners, type MaintenanceBanner as Banner } from '../services/maintenanceBannerService';

/**
 * Maintenance Banner Display Component
 *
 * Displays active maintenance notifications on the start/login page.
 *
 * Features:
 * - Fetches active banners on mount
 * - Polls every 60 seconds for updates
 * - Stacks multiple banners vertically (newest first)
 * - Bootstrap alert styling with icons
 * - Responsive design (mobile to 4K)
 * - No authentication required (public endpoint)
 *
 * Used in: index.astro (start/login page)
 */
export default function MaintenanceBanner() {
  const [banners, setBanners] = useState<Banner[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /**
   * Fetch active maintenance banners from the API.
   * Called on mount and every 60 seconds.
   */
  const fetchBanners = async () => {
    try {
      const activeBanners = await getActiveBanners();
      setBanners(activeBanners);
      setError(null);
    } catch (err: any) {
      console.error('Failed to fetch maintenance banners:', err);
      setError('Unable to load maintenance notifications');
    } finally {
      setLoading(false);
    }
  };

  /**
   * Initial fetch on component mount.
   * Sets up 60-second polling interval.
   */
  useEffect(() => {
    fetchBanners();

    // Poll every 60 seconds for banner updates
    const interval = setInterval(() => {
      fetchBanners();
    }, 60000); // 60 seconds

    // Cleanup interval on unmount
    return () => clearInterval(interval);
  }, []);

  // Don't render anything while loading initially
  if (loading) {
    return null;
  }

  // Don't render if there's an error or no banners
  if (error || banners.length === 0) {
    return null;
  }

  /**
   * Format ISO-8601 timestamp to local readable format.
   * Example: "Nov 15, 2025, 10:30 AM"
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

  return (
    <div className="maintenance-banners">
      {/* Render banners stacked vertically, newest first */}
      {banners.map((banner) => (
        <div
          key={banner.id}
          className="alert alert-warning"
          role="alert"
        >
          {/* Warning Icon */}
          <i className="bi bi-exclamation-triangle-fill"></i>

          {/* Banner Content */}
          <div className="banner-message">
            <strong className="d-block mb-1">Scheduled Maintenance</strong>
            <p className="mb-2">{banner.message}</p>
            <small className="text-muted">
              {formatTime(banner.startTime)} – {formatTime(banner.endTime)}
            </small>
          </div>
        </div>
      ))}
    </div>
  );
}
