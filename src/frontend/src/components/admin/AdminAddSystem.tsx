import React, { useState } from 'react';
import { isAdmin, authenticatedPost } from '../../utils/auth';

const AdminAddSystem: React.FC = () => {
    if (!isAdmin()) {
        return <div className="alert alert-danger">Access denied. Admin role required.</div>;
    }

    // Asset form state
    const [assetName, setAssetName] = useState('');
    const [assetType, setAssetType] = useState('SERVER');
    const [assetIp, setAssetIp] = useState('');
    const [assetOwner, setAssetOwner] = useState('');
    const [assetDescription, setAssetDescription] = useState('');
    const [assetCriticality, setAssetCriticality] = useState('');
    const [assetLoading, setAssetLoading] = useState(false);
    const [assetMessage, setAssetMessage] = useState<{ type: string; text: string } | null>(null);

    // Vulnerability form state
    const [vulnHostname, setVulnHostname] = useState('');
    const [vulnCve, setVulnCve] = useState('');
    const [vulnCriticality, setVulnCriticality] = useState('HIGH');
    const [vulnDaysOpen, setVulnDaysOpen] = useState(0);
    const [vulnLoading, setVulnLoading] = useState(false);
    const [vulnMessage, setVulnMessage] = useState<{ type: string; text: string } | null>(null);

    const handleAddAsset = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!assetName.trim() || !assetOwner.trim()) return;

        setAssetLoading(true);
        setAssetMessage(null);
        try {
            const body: any = {
                name: assetName.trim(),
                type: assetType.trim() || 'SERVER',
                owner: assetOwner.trim(),
            };
            if (assetIp.trim()) body.ip = assetIp.trim();
            if (assetDescription.trim()) body.description = assetDescription.trim();
            if (assetCriticality) body.criticality = assetCriticality;

            const response = await authenticatedPost('/api/assets', body);
            if (response.ok) {
                const data = await response.json();
                setAssetMessage({ type: 'success', text: `Asset "${data.name}" created successfully (ID: ${data.id}).` });
                setAssetName('');
                setAssetType('SERVER');
                setAssetIp('');
                setAssetOwner('');
                setAssetDescription('');
                setAssetCriticality('');
            } else {
                const text = await response.text();
                setAssetMessage({ type: 'danger', text: `Failed to create asset: ${text}` });
            }
        } catch (err: any) {
            setAssetMessage({ type: 'danger', text: `Error: ${err.message}` });
        } finally {
            setAssetLoading(false);
        }
    };

    const handleAddVulnerability = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!vulnHostname.trim() || !vulnCve.trim()) return;

        setVulnLoading(true);
        setVulnMessage(null);
        try {
            const body = {
                hostname: vulnHostname.trim(),
                cve: vulnCve.trim(),
                criticality: vulnCriticality,
                daysOpen: vulnDaysOpen,
            };

            const response = await authenticatedPost('/api/vulnerabilities/cli-add', body);
            if (response.ok) {
                const data = await response.json();
                setVulnMessage({
                    type: 'success',
                    text: `Vulnerability ${data.operation}: ${data.vulnerabilityId} on asset "${data.assetName}" (ID: ${data.assetId}).${data.assetCreated ? ' Asset was auto-created.' : ''}`,
                });
                setVulnHostname('');
                setVulnCve('');
                setVulnCriticality('HIGH');
                setVulnDaysOpen(0);
            } else {
                const text = await response.text();
                setVulnMessage({ type: 'danger', text: `Failed to add vulnerability: ${text}` });
            }
        } catch (err: any) {
            setVulnMessage({ type: 'danger', text: `Error: ${err.message}` });
        } finally {
            setVulnLoading(false);
        }
    };

    return (
        <div className="row">
            {/* Add Asset Card */}
            <div className="col-lg-6 mb-4">
                <div className="card">
                    <div className="card-header">
                        <h5 className="mb-0"><i className="bi bi-hdd-network me-2"></i>Add System (Asset)</h5>
                    </div>
                    <div className="card-body">
                        {assetMessage && (
                            <div className={`alert alert-${assetMessage.type} alert-dismissible fade show`} role="alert">
                                {assetMessage.text}
                                <button type="button" className="btn-close" onClick={() => setAssetMessage(null)}></button>
                            </div>
                        )}
                        <form onSubmit={handleAddAsset}>
                            <div className="mb-3">
                                <label htmlFor="asset-name" className="form-label">Name *</label>
                                <input
                                    type="text"
                                    className="form-control"
                                    id="asset-name"
                                    value={assetName}
                                    onChange={(e) => setAssetName(e.target.value)}
                                    required
                                    placeholder="e.g. WEBSERVER-01"
                                />
                            </div>
                            <div className="mb-3">
                                <label htmlFor="asset-type" className="form-label">Type</label>
                                <input
                                    type="text"
                                    className="form-control"
                                    id="asset-type"
                                    value={assetType}
                                    onChange={(e) => setAssetType(e.target.value)}
                                    placeholder="SERVER"
                                />
                            </div>
                            <div className="mb-3">
                                <label htmlFor="asset-ip" className="form-label">IP Address</label>
                                <input
                                    type="text"
                                    className="form-control"
                                    id="asset-ip"
                                    value={assetIp}
                                    onChange={(e) => setAssetIp(e.target.value)}
                                    placeholder="e.g. 192.168.1.100"
                                />
                            </div>
                            <div className="mb-3">
                                <label htmlFor="asset-owner" className="form-label">Owner *</label>
                                <input
                                    type="text"
                                    className="form-control"
                                    id="asset-owner"
                                    value={assetOwner}
                                    onChange={(e) => setAssetOwner(e.target.value)}
                                    required
                                    placeholder="e.g. secmanuser"
                                />
                            </div>
                            <div className="mb-3">
                                <label htmlFor="asset-description" className="form-label">Description</label>
                                <textarea
                                    className="form-control"
                                    id="asset-description"
                                    value={assetDescription}
                                    onChange={(e) => setAssetDescription(e.target.value)}
                                    rows={2}
                                    placeholder="Optional description"
                                />
                            </div>
                            <div className="mb-3">
                                <label htmlFor="asset-criticality" className="form-label">Criticality</label>
                                <select
                                    className="form-select"
                                    id="asset-criticality"
                                    value={assetCriticality}
                                    onChange={(e) => setAssetCriticality(e.target.value)}
                                >
                                    <option value="">-- None --</option>
                                    <option value="CRITICAL">Critical</option>
                                    <option value="HIGH">High</option>
                                    <option value="MEDIUM">Medium</option>
                                    <option value="LOW">Low</option>
                                    <option value="NA">N/A</option>
                                </select>
                            </div>
                            <button type="submit" className="btn btn-primary" id="asset-submit" disabled={assetLoading}>
                                {assetLoading ? (
                                    <><span className="spinner-border spinner-border-sm me-2"></span>Creating...</>
                                ) : (
                                    <><i className="bi bi-plus-circle me-2"></i>Add System</>
                                )}
                            </button>
                        </form>
                    </div>
                </div>
            </div>

            {/* Add Vulnerability Card */}
            <div className="col-lg-6 mb-4">
                <div className="card">
                    <div className="card-header">
                        <h5 className="mb-0"><i className="bi bi-shield-exclamation me-2"></i>Add Vulnerability</h5>
                    </div>
                    <div className="card-body">
                        {vulnMessage && (
                            <div className={`alert alert-${vulnMessage.type} alert-dismissible fade show`} role="alert">
                                {vulnMessage.text}
                                <button type="button" className="btn-close" onClick={() => setVulnMessage(null)}></button>
                            </div>
                        )}
                        <form onSubmit={handleAddVulnerability}>
                            <div className="mb-3">
                                <label htmlFor="vuln-hostname" className="form-label">Hostname (Asset Name) *</label>
                                <input
                                    type="text"
                                    className="form-control"
                                    id="vuln-hostname"
                                    value={vulnHostname}
                                    onChange={(e) => setVulnHostname(e.target.value)}
                                    required
                                    placeholder="e.g. WEBSERVER-01"
                                />
                                <div className="form-text">If the asset doesn't exist, it will be auto-created.</div>
                            </div>
                            <div className="mb-3">
                                <label htmlFor="vuln-cve" className="form-label">CVE *</label>
                                <input
                                    type="text"
                                    className="form-control"
                                    id="vuln-cve"
                                    value={vulnCve}
                                    onChange={(e) => setVulnCve(e.target.value)}
                                    required
                                    placeholder="e.g. CVE-2024-1234"
                                />
                            </div>
                            <div className="mb-3">
                                <label htmlFor="vuln-criticality" className="form-label">Criticality *</label>
                                <select
                                    className="form-select"
                                    id="vuln-criticality"
                                    value={vulnCriticality}
                                    onChange={(e) => setVulnCriticality(e.target.value)}
                                >
                                    <option value="CRITICAL">Critical</option>
                                    <option value="HIGH">High</option>
                                    <option value="MEDIUM">Medium</option>
                                    <option value="LOW">Low</option>
                                </select>
                            </div>
                            <div className="mb-3">
                                <label htmlFor="vuln-days-open" className="form-label">Days Open</label>
                                <input
                                    type="number"
                                    className="form-control"
                                    id="vuln-days-open"
                                    value={vulnDaysOpen}
                                    onChange={(e) => setVulnDaysOpen(parseInt(e.target.value) || 0)}
                                    min={0}
                                    placeholder="0"
                                />
                                <div className="form-text">Number of days since the vulnerability was discovered.</div>
                            </div>
                            <button type="submit" className="btn btn-warning" id="vuln-submit" disabled={vulnLoading}>
                                {vulnLoading ? (
                                    <><span className="spinner-border spinner-border-sm me-2"></span>Adding...</>
                                ) : (
                                    <><i className="bi bi-shield-plus me-2"></i>Add Vulnerability</>
                                )}
                            </button>
                        </form>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default AdminAddSystem;
