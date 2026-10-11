import type { BuddyReport } from './aiBuddyService';

/** Validate the wire shape before API-driven guide controls and renderers consume nested fields. */
export function parseBuddyReport(value: unknown): BuddyReport {
  const fail = () => { throw new Error('The patch guide response is incomplete or invalid. Please create a new guide.'); };
  const object = (item: unknown): item is Record<string, unknown> => typeof item === 'object' && item !== null && !Array.isArray(item);
  const strings = (item: unknown) => Array.isArray(item) && item.every(entry => typeof entry === 'string');
  const nullableString = (item: unknown) => item === null || typeof item === 'string';
  const fields = (item: Record<string, unknown>, names: string[]) => names.every(name => typeof item[name] === 'string');
  if (!object(value) || value.schemaVersion !== 1 || !fields(value, ['jobId', 'generatedAt', 'expiresAt'])) return fail();
  if (!object(value.system) || !fields(value.system, ['displayName']) ||
    !['osNameVersion', 'build', 'architecture'].every(key => nullableString(value.system && (value.system as Record<string, unknown>)[key]))) return fail();
  if (!object(value.snapshot) || !fields(value.snapshot, ['capturedAt']) || !strings(value.snapshot.staleReasons) ||
    typeof value.snapshot.stale !== 'boolean' || typeof value.snapshot.sourceChanged !== 'boolean' ||
    !['assetLastSeen', 'sensorLastSeen', 'vulnerabilityImportFrom', 'vulnerabilityImportTo'].every(key => nullableString((value.snapshot as Record<string, unknown>)[key]))) return fail();
  if (!object(value.scope) || !fields(value.scope, ['basis']) ||
    !['retainedFindingCount', 'consideredFindingCount', 'uniqueCveCount', 'nonCveFindingCount', 'exceptedFindingCount', 'excludedFindingCount', 'duplicateRowCount']
      .every(key => Number.isInteger((value.scope as Record<string, unknown>)[key]) && Number((value.scope as Record<string, unknown>)[key]) >= 0)) return fail();
  if (!object(value.analysis) || !fields(value.analysis, ['completeness']) || !nullableString(value.analysis.model) || !strings(value.analysis.limitations) ||
    !['verifiedCoveredFindingCount', 'conditionalFindingCount', 'noVerifiedRemediationCount'].every(key => Number.isInteger((value.analysis as Record<string, unknown>)[key]))) return fail();
  if (!Array.isArray(value.actions) || !Array.isArray(value.findings) || !Array.isArray(value.evidence)) return fail();
  for (const action of value.actions) {
    if (!object(action) || !fields(action, ['id', 'type', 'evidenceState', 'reboot']) || !Number.isInteger(action.order) ||
      !['VERIFIED', 'REQUIRES_APPLICABILITY_CHECK', 'INSUFFICIENT_INFORMATION'].includes(String(action.evidenceState)) ||
      !nullableString(action.targetProduct) || !nullableString(action.targetVersion) ||
      !['patchIds', 'coveredCves', 'coveredFindingRefs', 'evidenceRefs'].every(key => strings(action[key])) ||
      !Array.isArray(action.applicability) || !Array.isArray(action.prerequisites)) return fail();
    if (!(action.patchIds as string[]).every(patch => /^KB[0-9]{6,8}$/.test(patch)) ||
      (action.targetVersion !== null && (String(action.targetVersion).length > 60 || !/^[0-9]+(?:\.[0-9]+){0,5}(?:[-+][a-zA-Z0-9.]+)?$/.test(String(action.targetVersion))))) return fail();
    for (const check of [...action.applicability, ...action.prerequisites]) {
      if (!object(check) || !fields(check, ['code']) || !nullableString(check.value) ||
        (check.satisfied !== null && typeof check.satisfied !== 'boolean')) return fail();
    }
  }
  for (const finding of value.findings) {
    if (!object(finding) || !fields(finding, ['ref', 'source', 'disposition']) || typeof finding.excepted !== 'boolean' ||
      !nullableString(finding.identifier) || !nullableString(finding.product) || !strings(finding.actionRefs)) return fail();
  }
  for (const evidence of value.evidence) {
    if (!object(evidence) || !fields(evidence, ['ref', 'title', 'source', 'retrievedAt']) || !nullableString(evidence.url) ||
      (evidence.providerAdvice !== undefined && !nullableString(evidence.providerAdvice))) return fail();
  }
  return value as unknown as BuddyReport;
}
