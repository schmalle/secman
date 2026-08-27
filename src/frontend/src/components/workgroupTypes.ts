/**
 * Shared shapes for the workgroup management screen and its extracted modals
 * (WorkgroupFormModal, WorkgroupAssignUsersModal, WorkgroupAssignAssetsModal).
 * Kept in a .ts module so the Node test tier can import them alongside the
 * pure logic in workgroupAssignLogic.ts.
 */

export type WorkgroupCriticality = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NA';

/** A workgroup row as the management table receives it from GET /api/workgroups. */
export interface Workgroup {
    id: number;
    name: string;
    description?: string;
    criticality: WorkgroupCriticality;
    userCount: number;
    assetCount: number;
    awsAccountsCount?: number;
    adDomainsCount?: number;
    createdAt: string;
    updatedAt: string;
    parentId?: number;
    parentName?: string;
    depth?: number;
    ancestors?: Array<{ id: number; name: string }>;
}

/** A candidate user for assignment; id is null for known-but-never-logged-in emails. */
export interface WorkgroupUser {
    id: number | null;
    username: string;
    email: string;
    isPending?: boolean;
}

/** A user picked for assignment: existing users by id, invited-by-email users by email only. */
export type UserRef = { id?: number; email: string };

/** The slim asset shape the assign-assets picker needs. */
export interface WorkgroupAsset {
    id: number;
    name: string;
    type: string;
}

/**
 * Shape returned by GET /api/workgroups/{id}/assets — includes owner/ip for context
 * rows in the "Currently assigned" panel. `type` is nullable on the backend Asset.
 */
export interface AssignedAsset {
    id: number;
    name: string;
    type: string | null;
    ip: string | null;
    owner: string | null;
}

/** A current member row from GET /api/workgroups/{id}/users. */
export interface AssignedUser {
    id: number;
    username: string;
    email: string;
}
