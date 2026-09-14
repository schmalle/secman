export const MCP_PERMISSION_GROUPS = [
  { title: 'Requirements', keys: ['REQUIREMENTS_READ', 'REQUIREMENTS_WRITE', 'REQUIREMENTS_DELETE'] },
  { title: 'Assessments', keys: ['ASSESSMENTS_READ', 'ASSESSMENTS_EXECUTE', 'ASSESSMENTS_WRITE'] },
  { title: 'Assets & Scans', keys: ['ASSETS_READ', 'ASSETS_WRITE', 'SCANS_READ', 'VULNERABILITIES_READ'] },
  { title: 'Integrations', keys: ['INTEGRATIONS_READ', 'INTEGRATIONS_WRITE'] },
  { title: 'Files & Tags', keys: ['FILES_READ', 'TAGS_READ', 'TRANSLATION_USE'] },
  { title: 'Admin Only', keys: ['SYSTEM_INFO', 'USER_ACTIVITY', 'AUDIT_READ', 'WORKGROUPS_WRITE', 'NOTIFICATIONS_SEND'] },
] as const;

export const MCP_PERMISSION_KEYS = MCP_PERMISSION_GROUPS.flatMap(group => group.keys);

export const selectAllMcpPermissions = (checked: boolean): string[] =>
  checked ? [...MCP_PERMISSION_KEYS] : [];

export const hasAllMcpPermissions = (permissions: string[]): boolean =>
  MCP_PERMISSION_KEYS.every(permission => permissions.includes(permission));
