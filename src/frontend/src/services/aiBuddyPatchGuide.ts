import { buddyEvidenceUrl, buddyLabel, type BuddyAction, type BuddyReport } from './aiBuddyService';

export function guideScope(report: BuddyReport): string {
  return report.scope.basis === 'RECORDED_HIGH_CRITICAL_ASSET_FINDINGS' ? 'recorded HIGH and CRITICAL findings' : 'all recorded findings';
}

export function guideStatus(action: BuddyAction): string {
  return action.evidenceState === 'VERIFIED' ? 'Ready to apply'
    : action.evidenceState === 'REQUIRES_APPLICABILITY_CHECK' ? 'Confirm before applying' : 'Needs investigation';
}

export function guideCheck(code: string): string {
  const instructions: Record<string, string> = {
    PRODUCT_VARIANT: 'Confirm the Windows edition and installation type match the Microsoft update.',
    OS_BUILD: 'Check the full Windows build number against the update requirements.',
    ARCHITECTURE: 'Confirm that the update package matches the system architecture.',
    INSTALLED_UPDATES: 'Check installed updates and supersedence so an already installed fix is not reapplied.',
    SERVICING_PREREQUISITES: 'Follow the servicing prerequisites in the Microsoft update article.',
    MATCH_RECORDED_OS_FAMILY: 'Confirm that the detected operating system matches this update.',
    FIREFOX_CHANNEL_AND_VERSION: 'Open Firefox → Help → About Firefox. Record its exact version and whether it is Release or ESR; refresh installed-product inventory.',
    FIREFOX_PLATFORM_SUPPORT: 'Confirm the target Firefox version supports this operating system and your deployment policy.',
    CONFIRM_CURRENT_INVENTORY: 'Confirm the installed version and channel still match the recorded inventory.',
    VERIFY_PROVIDER_REMEDIATION: 'Confirm the provider advice against the vendor instructions before applying it.'
  };
  return instructions[code] || buddyLabel(code);
}

export function guideSteps(action: BuddyAction): string[] {
  const checks = [...action.applicability, ...action.prerequisites]
    .filter(check => check.satisfied !== true).map(check => guideCheck(check.code));
  if (action.evidenceState === 'INSUFFICIENT_INFORMATION') return checks.length ? checks
    : ['Check the vendor guidance and refresh product/version inventory before selecting a fix.'];
  const target = action.patchIds.length ? action.patchIds.join(', ') : action.targetVersion;
  if (!target) return [...checks, 'Confirm the exact fix in the linked vendor evidence before installing anything.'];
  if (action.type === 'OS_UPDATE') return [...checks,
    `Once the checks above pass, deploy ${target} through your normal Windows patch-management tool, following the linked Microsoft instructions.`,
    action.reboot === 'REQUIRED' ? 'Restart Windows after installation as required by the update.'
      : 'Follow the update article’s restart requirements; unknown restart status does not mean no restart is needed.',
    `Confirm the update installed successfully${action.targetVersion ? ` and check the resulting build against ${action.targetVersion}` : ''}.`];
  if (action.targetProduct === 'mozilla firefox') return [...checks,
    `Once the checks above pass, close Firefox and deploy version ${target} on the confirmed channel using your normal software-management tool.`,
    `Reopen Firefox and confirm version ${target} in Help → About Firefox.`];
  return [...checks, `Apply ${target} using the linked vendor instructions.`, 'Confirm successful installation and any required restart.'];
}

export function remainingProducts(report: BuddyReport): { product: string; count: number }[] {
  const groups = new Map<string, number>();
  for (const finding of report.findings) {
    if (finding.disposition !== 'NO_VERIFIED_REMEDIATION') continue;
    const product = finding.product || 'Unidentified product';
    groups.set(product, (groups.get(product) || 0) + 1);
  }
  return [...groups].map(([product, count]) => ({ product, count })).sort((a, b) => b.count - a.count);
}

export function patchGuideText(report: BuddyReport): string {
  const lines = [`Patch guide — ${report.system.displayName}`, `Scope: ${guideScope(report)}; excepted findings are included.`,
    `Generated: ${report.generatedAt}. Expires: ${report.expiresAt}.`];
  if (report.analysis.limitations.includes('USER_SUPPLIED_INVENTORY_REQUIRES_CONFIRMATION')) lines.push('Uses temporary user-supplied inventory; confirm these facts on the affected system.');
  report.actions.forEach((action, index) => {
    lines.push('', `${index + 1}. ${action.targetProduct || 'Product'} — ${guideStatus(action)}`);
    lines.push(...guideSteps(action).map((step, i) => `   ${i + 1}) ${step}`));
    for (const evidence of report.evidence.filter(item => action.evidenceRefs.includes(item.ref))) {
      const url = buddyEvidenceUrl(evidence.url);
      if (url) lines.push(`   Vendor evidence: ${url}`);
    }
  });
  if (!report.actions.length) lines.push('', 'No installable fix could be established. Complete product/version inventory and review the vendor guidance.');
  lines.push('', 'Verify: refresh inventory and vulnerability findings; review remaining HIGH and CRITICAL findings.');
  lines.push(...remainingProducts(report).map(group => `${group.product}: ${group.count} findings still need vendor evidence.`));
  if (report.snapshot.stale || report.snapshot.sourceChanged) lines.push('Inventory or findings may be stale or changed; regenerate before applying these instructions.');
  return lines.join('\n');
}
