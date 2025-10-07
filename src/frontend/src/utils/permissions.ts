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
 * Check if user can delete a specific release
 * 
 * Rules:
 * - ADMIN can delete any release
 * - RELEASE_MANAGER can delete only releases they created
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

  // ADMIN can delete any release
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
