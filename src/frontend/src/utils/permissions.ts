/**
 * Permission utility functions for RBAC enforcement
 * 
 * These functions determine what actions a user can perform
 * based on their roles and the resource ownership
 */

export interface User {
  username: string;
  email?: string;
  roles?: string[];
}

export interface Release {
  id: number;
  version: string;
  name?: string;
  createdBy?: string;
  status?: string;
}

/**
 * Check if user has ADMIN role
 */
export function isAdmin(roles: string[] | undefined): boolean {
  if (!roles || !Array.isArray(roles)) return false;
  return roles.includes('ADMIN');
}

/**
 * Check if user has RELEASE_MANAGER role
 */
export function isReleaseManager(roles: string[] | undefined): boolean {
  if (!roles || !Array.isArray(roles)) return false;
  return roles.includes('RELEASE_MANAGER');
}

/**
 * Check if user has CHAMPION role (DEPRECATED)
 * @deprecated Use isSecChampion() instead - CHAMPION renamed to SECCHAMPION
 */
export function isChampion(roles: string[] | undefined): boolean {
  if (!roles || !Array.isArray(roles)) return false;
  return roles.includes('CHAMPION');
}

/**
 * Check if user has SECCHAMPION role
 * Feature: 025-role-based-access-control
 */
export function isSecChampion(roles: string[] | undefined): boolean {
  if (!roles || !Array.isArray(roles)) return false;
  return roles.includes('SECCHAMPION');
}

/**
 * Check if user has REQ role
 */
export function isReq(roles: string[] | undefined): boolean {
  if (!roles || !Array.isArray(roles)) return false;
  return roles.includes('REQ');
}

/**
 * Check if user has RISK role
 * Feature: 025-role-based-access-control
 */
export function isRisk(roles: string[] | undefined): boolean {
  if (!roles || !Array.isArray(roles)) return false;
  return roles.includes('RISK');
}

/**
 * Check if user has access to Risk Management
 * Feature: 025-role-based-access-control
 *
 * Rules:
 * - ADMIN can access
 * - RISK can access
 * - SECCHAMPION can access
 */
export function hasRiskAccess(roles: string[] | undefined): boolean {
  return isAdmin(roles) || isRisk(roles) || isSecChampion(roles);
}

/**
 * Check if user has access to Requirements
 * Feature: 025-role-based-access-control
 *
 * Rules:
 * - ADMIN can access
 * - REQ can access
 * - SECCHAMPION can access
 */
export function hasReqAccess(roles: string[] | undefined): boolean {
  return isAdmin(roles) || isReq(roles) || isSecChampion(roles);
}

/**
 * Check if user can access Norm Management
 *
 * Rules:
 * - ADMIN can access
 * - SECCHAMPION can access (updated from CHAMPION)
 * - REQ can access
 */
export function canAccessNormManagement(roles: string[] | undefined): boolean {
  return isAdmin(roles) || isSecChampion(roles) || isReq(roles);
}

/**
 * Check if user can access Standard Management
 *
 * Rules:
 * - ADMIN can access
 * - SECCHAMPION can access (updated from CHAMPION)
 * - REQ can access
 */
export function canAccessStandardManagement(roles: string[] | undefined): boolean {
  return isAdmin(roles) || isSecChampion(roles) || isReq(roles);
}

/**
 * Check if user can access UseCase Management
 *
 * Rules:
 * - ADMIN can access
 * - SECCHAMPION can access (updated from CHAMPION)
 * - REQ can access
 */
export function canAccessUseCaseManagement(roles: string[] | undefined): boolean {
  return isAdmin(roles) || isSecChampion(roles) || isReq(roles);
}

/**
 * Check if user can access Releases
 * Feature: 067-requirement-releases
 *
 * Rules:
 * - All users with requirements access can view releases (FR-011)
 * - ADMIN, REQ, and SECCHAMPION can access
 */
export function canAccessReleases(roles: string[] | undefined): boolean {
  return hasReqAccess(roles);
}

/**
 * Check if user can access Compare Releases
 * Feature: 067-requirement-releases
 *
 * Rules:
 * - All users with requirements access can view/compare releases (FR-011)
 * - ADMIN, REQ, and SECCHAMPION can access
 */
export function canAccessCompareReleases(roles: string[] | undefined): boolean {
  return hasReqAccess(roles);
}

/**
 * Check if user can delete a specific release
 *
 * Rules:
 * - ACTIVE releases cannot be deleted (must set another release as active first)
 * - ADMIN can delete any non-ACTIVE release
 * - RELEASE_MANAGER can delete only releases they created (if not ACTIVE)
 * - USER cannot delete releases
 *
 * @param release - The release to check permissions for
 * @param currentUser - The current user object
 * @param currentUserRoles - The current user's roles array
 * @returns true if user can delete the release, false otherwise
 */
export function canDeleteRelease(
  release: Release | null | undefined,
  currentUser: User | null | undefined,
  currentUserRoles: string[] | undefined
): boolean {
  if (!release || !currentUser) return false;

  // ACTIVE releases cannot be deleted
  if (release.status === 'ACTIVE') {
    return false;
  }

  // ADMIN can delete any non-ACTIVE release
  if (isAdmin(currentUserRoles)) {
    return true;
  }

  // RELEASE_MANAGER can delete only their own releases
  if (isReleaseManager(currentUserRoles)) {
    return release.createdBy === currentUser.username;
  }

  // USER (or no role) cannot delete
  return false;
}

/**
 * Check if user can create a release
 * 
 * Rules:
 * - ADMIN can create
 * - RELEASE_MANAGER can create
 * - USER cannot create
 */
export function canCreateRelease(roles: string[] | undefined): boolean {
  return isAdmin(roles) || isReleaseManager(roles);
}

/**
 * Check if user can update release status (publish/archive)
 * 
 * Rules:
 * - ADMIN can update any release
 * - RELEASE_MANAGER can update only their own releases
 * - USER cannot update
 */
export function canUpdateReleaseStatus(
  release: Release | null | undefined,
  currentUser: User | null | undefined,
  currentUserRoles: string[] | undefined
): boolean {
  if (!release || !currentUser) return false;

  // ADMIN can update any release
  if (isAdmin(currentUserRoles)) {
    return true;
  }

  // RELEASE_MANAGER can update only their own releases
  if (isReleaseManager(currentUserRoles)) {
    return release.createdBy === currentUser.username;
  }

  return false;
}

/**
 * Check if user can view releases
 * 
 * All authenticated users can view releases
 */
export function canViewReleases(roles: string[] | undefined): boolean {
  return true; // All authenticated users can view
}

/**
 * Get user-friendly permission error message
 */
export function getPermissionErrorMessage(action: string): string {
  const messages: Record<string, string> = {
    delete: 'You do not have permission to delete this release.',
    create: 'You do not have permission to create releases.',
    update: 'You do not have permission to update this release.',
    publish: 'You do not have permission to publish this release.',
    archive: 'You do not have permission to archive this release.',
  };

  return messages[action] || 'You do not have permission to perform this action.';
}
