/**
 * Pure logic for the workgroup assign-users / assign-assets modals, extracted
 * from WorkgroupManagement.tsx so the Node test tier (which cannot parse JSX)
 * can exercise it.
 */

import type { AssignedUser, WorkgroupAsset } from './workgroupTypes';

/**
 * Members remaining after the pending removals are applied — drives the
 * count-mismatch warning in the assign-users dialog. Only removals that
 * actually target an assigned member count; never below zero.
 */
export function getEffectiveAssignedUserCount(
    assignedUsers: AssignedUser[],
    userIdsToRemove: number[]
): number {
    const assignedIds = new Set(assignedUsers.map(user => user.id));
    const pendingRemovals = userIdsToRemove.filter(id => assignedIds.has(id)).length;
    return Math.max(0, assignedUsers.length - pendingRemovals);
}

/**
 * Filter assets by a search term with wildcard support:
 * partial match, `*` for any characters, `?` for a single character,
 * case-insensitive against name and type. An invalid resulting regex
 * falls back to a plain substring match.
 */
export function filterAssetsByWildcard(assets: WorkgroupAsset[], searchTerm: string): WorkgroupAsset[] {
    if (!searchTerm.trim()) {
        return assets;
    }

    const term = searchTerm.toLowerCase().trim();

    const regexPattern = term
        .replace(/[.+^${}()|[\]\\]/g, '\\$&') // Escape regex special chars except * and ?
        .replace(/\*/g, '.*')
        .replace(/\?/g, '.');

    try {
        const regex = new RegExp(regexPattern);
        return assets.filter(asset =>
            regex.test(asset.name.toLowerCase()) ||
            regex.test(asset.type.toLowerCase())
        );
    } catch {
        return assets.filter(asset =>
            asset.name.toLowerCase().includes(term) ||
            asset.type.toLowerCase().includes(term)
        );
    }
}
