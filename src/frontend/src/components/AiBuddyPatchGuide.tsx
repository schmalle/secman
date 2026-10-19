import React, { useState } from 'react';
import { buddyEvidenceUrl, buddyLabel, type BuddyReport } from '../services/aiBuddyService';
import { guideScope, guideStatus, guideSteps, patchGuideText, remainingProducts } from '../services/aiBuddyPatchGuide';

export default function AiBuddyPatchGuide({ report }: { report: BuddyReport }) {
  const [copyMessage, setCopyMessage] = useState('');
  const remaining = remainingProducts(report);
  const needsInventory = report.actions.some(action => [...action.applicability, ...action.prerequisites].some(check => check.satisfied !== true));
  const copy = async () => {
    try { await navigator.clipboard.writeText(patchGuideText(report)); setCopyMessage('Patch guide copied.'); }
    catch { setCopyMessage('Copy was unavailable. Select and copy the guide text below.'); }
  };
  return <section aria-label="Step-by-step patch guide">
    <h4 className="h5">Your patch guide</h4>
    <p className="small text-muted">Scope: {guideScope(report)}.</p>
    <p>{report.system.osNameVersion || 'Operating system not recorded'} · Build: {report.system.build || 'not recorded'} · Architecture: {report.system.architecture || 'not recorded'}</p>
    {report.analysis.limitations.includes('USER_SUPPLIED_INVENTORY_REQUIRES_CONFIRMATION') && <div className="alert alert-info">{buddyLabel('USER_SUPPLIED_INVENTORY_REQUIRES_CONFIRMATION')}</div>}
    <p>{report.actions.filter(action => action.evidenceState !== 'INSUFFICIENT_INFORMATION').length} update actions · {report.analysis.noVerifiedRemediationCount} findings need more evidence. Excepted findings are included; review their exceptions before applying changes.</p>
    {(report.snapshot.stale || report.snapshot.sourceChanged) && <div className="alert alert-warning">Inventory or findings changed or may be stale. Refresh them and create a new guide before applying updates.</div>}
    {!report.actions.length && <div className="alert alert-info">No trustworthy installation target could be established. Complete the inventory checks below and retry; the recorded findings still need attention.</div>}
    {report.actions.map((action, index) => <article key={action.id} className="border rounded p-3 mb-3">
      <h5>{index + 1}. {action.targetProduct === 'mozilla firefox' ? 'Mozilla Firefox' : action.targetProduct || 'Product investigation'}</h5>
      <p><strong>{guideStatus(action)}</strong>{action.patchIds.length > 0 && ` · ${action.patchIds.join(', ')}`}{action.targetVersion && ` · Target: ${action.targetVersion}`}</p>
      {action.applicability.filter(check => check.satisfied === true && check.value).map(check => <small className="d-block text-muted" key={check.code}>{check.code === 'FIREFOX_CHANNEL' ? 'Channel used for guide' : check.code === 'INSTALLED_VERSION' ? 'Version used for guide' : check.code}: {check.value}</small>)}
      <ol className="mt-2">{guideSteps(action).map((step, i) => <li key={i}>{step}</li>)}</ol>
      <p className="small mb-1">{action.coveredFindingRefs.length} associated findings · {action.evidenceState === 'VERIFIED' ? 'verified applicability' : 'coverage remains conditional or unverified'}</p>
      {report.evidence.filter(item => action.evidenceRefs.includes(item.ref)).map(item => {
        const url = buddyEvidenceUrl(item.url);
        return url && <a key={item.ref} className="d-block" href={url} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{item.title}</a>;
      })}
    </article>)}
    {(remaining.length > 0 || needsInventory || !report.actions.length) && <details className="mb-3" open={!report.actions.length}>
      <summary>Complete the guide: confirm inventory and vendor requirements</summary>
      <ul>{remaining.map(group => <li key={group.product}>{group.product}: {group.count} findings. Confirm exact installed product/version and consult the vendor’s security advisories.</li>)}</ul>
      <p>For Windows, collect edition, full build, architecture and installed servicing updates. For Firefox, collect version and Release/ESR channel in Help → About Firefox. Refresh the existing SecMan inventory import, then create a new guide.</p>
      <details><summary>Read-only Windows inventory commands (run on the affected system)</summary><pre className="bg-light p-2">{'Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,BuildNumber,OSArchitecture\nGet-HotFix | Select-Object HotFixID,InstalledOn\nGet-WindowsPackage -Online | Select-Object PackageName,PackageState'}</pre><p className="small">The package query requires an elevated PowerShell session. Get-HotFix alone is not a complete servicing inventory. These commands collect information; they do not install anything. Do not paste full machine output into the AI provider.</p></details>
    </details>}
    <h5>Finally: verify the result</h5>
    <p>Confirm installation and required restarts, refresh inventory and vulnerability findings, then review remaining HIGH and CRITICAL findings. This guide does not mark findings resolved or prove the system is secure.</p>
    <button type="button" className="btn btn-outline-primary" onClick={() => void copy()}>Copy patch guide</button>
    <span role="status" className="ms-2">{copyMessage}</span>
    {copyMessage.startsWith('Copy was') && <pre className="mt-2">{patchGuideText(report)}</pre>}
  </section>;
}
