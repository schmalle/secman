import React, { useEffect, useState } from 'react';
import { getUser, hasRole } from '../utils/auth';
import HomeStatisticsDashboard from './HomeStatisticsDashboard';
import UserTodoDashboard from './UserTodoDashboard';

/**
 * Role-based home dashboard switch.
 *
 * - ADMIN or SECCHAMPION → platform-wide statistics dashboard (unchanged)
 * - every other authenticated user → personal todo dashboard
 * - not logged in → statistics dashboard (its cards degrade to "—",
 *   preserving the pre-existing behavior for unauthenticated visits)
 *
 * The role check reads sessionStorage, which does not exist during Astro's
 * pre-render, so it runs after mount to avoid a hydration mismatch.
 */
const HomeDashboard: React.FC = () => {
  const [showTodoDashboard, setShowTodoDashboard] = useState<boolean | null>(null);

  useEffect(() => {
    setShowTodoDashboard(getUser() != null && !hasRole(['ADMIN', 'SECCHAMPION']));
  }, []);

  if (showTodoDashboard == null) {
    return null;
  }
  return showTodoDashboard ? <UserTodoDashboard /> : <HomeStatisticsDashboard />;
};

export default HomeDashboard;
