/*
 * The ADMIN rail's contents, as data.
 *
 * Twenty-plus links do not fit on a screen, so the rail filters and folds
 * (design 1b): a filter field at the top, groups shut except the one holding
 * the page you are on. Rendering from a list rather than from hand-written
 * markup is what makes both possible — a filter cannot search JSX, and it also
 * lets the matching live here in a `.ts` module the node:test tier can import.
 */

export interface AdminNavItem {
    label: string;
    href: string;
    /** Bootstrap Icons class, without the leading `bi ` */
    icon: string;
    /** Optional `title` attribute — the hover explanation for a non-obvious entry */
    title?: string;
}

export interface AdminNavGroup {
    key: string;
    /** `null` for the ungrouped entries that sit above the first heading */
    label: string | null;
    items: AdminNavItem[];
}

export const ADMIN_NAV: AdminNavGroup[] = [
    {
        key: 'overview',
        label: null,
        items: [
            { label: 'Dashboard', href: '/admin', icon: 'bi-speedometer' },
        ],
    },
    {
        key: 'users',
        label: 'Users & Access',
        items: [
            { label: 'User Management', href: '/admin/user-management', icon: 'bi-people-fill' },
            { label: 'Workgroups', href: '/workgroups', icon: 'bi-diagram-2' },
            { label: 'User Mappings', href: '/admin/user-mappings', icon: 'bi-diagram-3-fill' },
            { label: 'Identity Providers', href: '/admin/identity-providers', icon: 'bi-shield-lock' },
        ],
    },
    {
        key: 'system',
        label: 'System Configuration',
        items: [
            { label: 'Email Settings', href: '/admin/email-config', icon: 'bi-envelope-gear' },
            { label: 'Vulnerability Settings', href: '/admin/vulnerability-config', icon: 'bi-shield-exclamation' },
            { label: 'Notification Settings', href: '/admin/notification-settings', icon: 'bi-bell-fill' },
            {
                label: 'Chat Configuration',
                href: '/admin/chat-config',
                icon: 'bi-chat-dots',
                title: 'Workspace Slack and Telegram bot credentials for chat notifications',
            },
            { label: 'CrowdStrike Falcon', href: '/admin/falcon-config', icon: 'bi-shield-lock' },
            { label: 'GitHub App', href: '/admin/github-config', icon: 'bi-github' },
            { label: 'LLM Config', href: '/admin/translation-config', icon: 'bi-translate' },
            { label: 'MCP API Keys', href: '/admin/mcp-api-keys', icon: 'bi-key-fill' },
            {
                label: 'Export Templates',
                href: '/admin/requirement-export-templates',
                icon: 'bi-file-earmark-word',
                title: 'Company Word templates used by requirement exports',
            },
            { label: 'Classification Rules', href: '/admin/classification-rules', icon: 'bi-funnel-fill' },
        ],
    },
    {
        key: 'content',
        label: 'Content & Data',
        items: [
            { label: 'Requirements Mgmt', href: '/admin/requirements', icon: 'bi-list-task' },
            { label: 'Scans', href: '/scans', icon: 'bi-radar' },
            { label: 'Add System', href: '/admin/add-system', icon: 'bi-plus-circle' },
        ],
    },
    {
        key: 'io',
        label: 'I/O',
        items: [
            { label: 'Import', href: '/import', icon: 'bi-cloud-upload' },
            /*
             * Export was a nested dropdown. A nested toggle inside a folded group
             * inside a filtered list is three levels of state to reason about, and
             * its children could never be reached by the filter — so the two
             * destinations are listed flat, under names that say where they go.
             */
            { label: 'Export Requirements', href: '/export', icon: 'bi-file-earmark-excel' },
            { label: 'Export Assets', href: '/export?type=assets', icon: 'bi-hdd-rack' },
            { label: 'Configuration Bundle', href: '/admin/config-bundle', icon: 'bi-box-arrow-in-down' },
        ],
    },
    {
        key: 'monitoring',
        label: 'Monitoring',
        items: [
            { label: 'Notifications', href: '/notification-preferences', icon: 'bi-bell' },
            { label: 'Notification Logs', href: '/notification-logs', icon: 'bi-envelope-paper' },
            { label: 'EC2 Compliance', href: '/admin/ec2-compliance', icon: 'bi-shield-check' },
        ],
    },
];

/**
 * Groups whose label or items match `query`, with non-matching items removed.
 * An empty or blank query returns the list unchanged. A group whose *label*
 * matches keeps all of its items, so typing "i/o" shows the whole section.
 */
export function filterAdminNav(groups: AdminNavGroup[], query: string): AdminNavGroup[] {
    const q = query.trim().toLowerCase();
    if (!q) return groups;

    const result: AdminNavGroup[] = [];
    for (const group of groups) {
        if (group.label && group.label.toLowerCase().includes(q)) {
            result.push(group);
            continue;
        }
        const items = group.items.filter((item) => item.label.toLowerCase().includes(q));
        if (items.length > 0) result.push({ ...group, items });
    }
    return result;
}

/**
 * The group holding `path`, or `null` when the path is outside the admin rail.
 *
 * Longest matching href wins, the same rule the rail's active-link highlight
 * uses, so `/admin/user-management` picks User Management rather than the
 * `/admin` Dashboard entry.
 */
export function groupKeyForPath(groups: AdminNavGroup[], path: string): string | null {
    const match = activeHrefForPath(groups, path);
    if (!match) return null;
    return groups.find((g) => g.items.some((i) => i.href === match))?.key ?? null;
}

/** The href of the entry `path` is currently on, or `null`. */
export function activeHrefForPath(groups: AdminNavGroup[], path: string): string | null {
    const current = stripTrailingSlash(path);
    let best: string | null = null;
    let bestLength = -1;

    for (const group of groups) {
        for (const item of group.items) {
            // Query strings never take part: /export?type=assets and /export are
            // the same page as far as "where am I" is concerned, so the first of
            // the two listed wins the tie.
            const href = stripTrailingSlash(item.href.split('?')[0]);
            const matches = current === href || (href !== '/' && current.startsWith(href + '/'));
            if (matches && href.length > bestLength) {
                best = item.href;
                bestLength = href.length;
            }
        }
    }
    return best;
}

function stripTrailingSlash(path: string): string {
    return path.replace(/\/+$/, '') || '/';
}
