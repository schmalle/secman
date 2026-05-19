import React, { useEffect, useMemo, useState } from 'react';
import { getUser } from '../utils/auth';
import { isAdmin, isSecChampion } from '../utils/permissions';
import {
  createApplication,
  deleteApplication,
  getApplication,
  listApplications,
  replaceApplicationAssets,
  updateApplication,
  type ApplicationAssetSummary,
  type ApplicationRegisterDetail,
  type ApplicationRegisterRequest,
  type ApplicationRegisterSummary,
} from '../services/applicationRegisterService';
import { authenticatedGet } from '../utils/auth';

type TabKey = 'operation' | 'technical' | 'organization' | 'relations' | 'compliance' | 'dates' | 'reporting';

interface AssetOption {
  id: number;
  name: string;
  type: string;
  owner: string;
  ip?: string | null;
}

const emptyForm: ApplicationRegisterRequest = {
  carId: '',
  name: '',
  processCluster: '',
  processArea: '',
  criticality: '',
  operationalStatus: '',
  businessOwner: '',
  applicationChampion: '',
  applicationManager: '',
  applicationTechnology: '',
  applicationArchitecture: '',
  lastQualityCheck: '',
  informationClassification: '',
  processingOfPersonalData: '',
  icsRelevant: '',
  legalRegulatory: '',
  legalRegulatoryRationaleImpact: '',
  dataExportControlRelevant: '',
  applicationExportControlRelevant: '',
  operationModel: '',
  productionOperatingHours: '',
  serviceOperatingHours: '',
  sslCertificatesUsed: '',
  allMachineUsers: '',
  recoveryPlanUrl: '',
  authorizationConceptUrl: '',
  passwordStorageTool: '',
  availabilitySupportUrl: '',
  recurringTasksResponsibilitiesUrl: '',
  backupRecoveryUrl: '',
  monitoringEscalationUrl: '',
  toolsUsedForMonitoringUrl: '',
  licenseManagementUrl: '',
  communicationChannelsUrl: '',
  incidentAssignmentGroup: '',
  solverGroupC: '',
  changeApprovalGroup: '',
  cabApprovalGroup: '',
  changeFulfillmentGroup: '',
  runAndChange: '',
  managedServiceRun: '',
  managedServiceChange: '',
  extendedWorkbenchChange: '',
  extendedWorkbenchRun: '',
  managedInternally: '',
  notes: '',
  cmdbWorkspaceUrl: '',
};

const tabs: Array<{ key: TabKey; label: string; icon: string }> = [
  { key: 'operation', label: 'Operation', icon: 'bi-clock-history' },
  { key: 'technical', label: 'Technical Details', icon: 'bi-cpu' },
  { key: 'organization', label: 'Organization', icon: 'bi-diagram-3' },
  { key: 'relations', label: 'Relations', icon: 'bi-link-45deg' },
  { key: 'compliance', label: 'Compliance', icon: 'bi-shield-check' },
  { key: 'dates', label: 'Dates', icon: 'bi-calendar3' },
  { key: 'reporting', label: 'Reporting', icon: 'bi-file-text' },
];

const fieldGroups: Record<Exclude<TabKey, 'relations'>, Array<{ name: keyof ApplicationRegisterRequest; label: string; type?: string; textarea?: boolean }>> = {
  operation: [
    { name: 'operationModel', label: 'Operation model' },
    { name: 'productionOperatingHours', label: 'Production operating hours' },
    { name: 'serviceOperatingHours', label: 'Service operating hours' },
    { name: 'sslCertificatesUsed', label: 'SSL certificates used' },
    { name: 'allMachineUsers', label: 'All machine users' },
    { name: 'passwordStorageTool', label: 'Password storage tool' },
  ],
  technical: [
    { name: 'applicationTechnology', label: 'Application technology' },
    { name: 'applicationArchitecture', label: 'Application architecture' },
    { name: 'recoveryPlanUrl', label: 'Recovery plan URL' },
    { name: 'authorizationConceptUrl', label: 'Authorization concept URL' },
    { name: 'availabilitySupportUrl', label: 'Availability support URL' },
    { name: 'backupRecoveryUrl', label: 'Backup and recovery URL' },
    { name: 'monitoringEscalationUrl', label: 'Monitoring escalation URL' },
    { name: 'toolsUsedForMonitoringUrl', label: 'Tools used for monitoring URL' },
    { name: 'licenseManagementUrl', label: 'License management URL' },
    { name: 'communicationChannelsUrl', label: 'Communication channels URL' },
    { name: 'recurringTasksResponsibilitiesUrl', label: 'Recurring tasks URL' },
  ],
  organization: [
    { name: 'incidentAssignmentGroup', label: 'Incident assignment group' },
    { name: 'solverGroupC', label: 'Solver group C' },
    { name: 'changeApprovalGroup', label: 'Change approval group' },
    { name: 'cabApprovalGroup', label: 'CAB approval group' },
    { name: 'changeFulfillmentGroup', label: 'Change fulfillment group' },
    { name: 'runAndChange', label: 'Run and change' },
    { name: 'managedServiceRun', label: 'Managed service run' },
    { name: 'managedServiceChange', label: 'Managed service change' },
    { name: 'extendedWorkbenchChange', label: 'Extended workbench change' },
    { name: 'extendedWorkbenchRun', label: 'Extended workbench run' },
    { name: 'managedInternally', label: 'Managed internally' },
  ],
  compliance: [
    { name: 'informationClassification', label: 'Information classification' },
    { name: 'processingOfPersonalData', label: 'Processing of personal data' },
    { name: 'icsRelevant', label: 'ICS relevant' },
    { name: 'legalRegulatory', label: 'Legal regulatory' },
    { name: 'legalRegulatoryRationaleImpact', label: 'Legal regulatory rationale impact', textarea: true },
    { name: 'dataExportControlRelevant', label: 'Data export control relevant' },
    { name: 'applicationExportControlRelevant', label: 'Application export control relevant' },
  ],
  dates: [
    { name: 'lastQualityCheck', label: 'Last quality check', type: 'date' },
  ],
  reporting: [
    { name: 'notes', label: 'Notes', textarea: true },
    { name: 'cmdbWorkspaceUrl', label: 'CMDB workspace URL' },
  ],
};

const toForm = (application: ApplicationRegisterDetail): ApplicationRegisterRequest => {
  const form = { ...emptyForm };
  Object.keys(form).forEach((key) => {
    const typedKey = key as keyof ApplicationRegisterRequest;
    form[typedKey] = (application[typedKey] ?? '') as never;
  });
  return form;
};

const ApplicationRegister: React.FC = () => {
  const [applications, setApplications] = useState<ApplicationRegisterSummary[]>([]);
  const [selected, setSelected] = useState<ApplicationRegisterDetail | null>(null);
  const [formData, setFormData] = useState<ApplicationRegisterRequest>(emptyForm);
  const [assetOptions, setAssetOptions] = useState<AssetOption[]>([]);
  const [selectedAssetIds, setSelectedAssetIds] = useState<number[]>([]);
  const [search, setSearch] = useState('');
  const [assetSearch, setAssetSearch] = useState('');
  const [activeTab, setActiveTab] = useState<TabKey>('operation');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const user = getUser();
  const canMutate = isAdmin(user?.roles) || isSecChampion(user?.roles);

  useEffect(() => {
    void loadApplications();
    void loadAssets();
  }, []);

  const filteredApplications = useMemo(() => applications, [applications]);
  const filteredAssets = useMemo(() => {
    const query = assetSearch.trim().toLowerCase();
    if (!query) return assetOptions.slice(0, 80);
    return assetOptions.filter((asset) =>
      [asset.name, asset.type, asset.owner, asset.ip || ''].some((value) => value.toLowerCase().includes(query))
    ).slice(0, 80);
  }, [assetOptions, assetSearch]);

  const loadApplications = async (nextSearch = search) => {
    setLoading(true);
    try {
      const data = await listApplications(nextSearch);
      setApplications(data);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load applications');
    } finally {
      setLoading(false);
    }
  };

  const loadAssets = async () => {
    try {
      const response = await authenticatedGet('/api/assets');
      if (response.ok) {
        setAssetOptions(await response.json());
      }
    } catch (err) {
      console.error('Failed to load assets for application relations', err);
    }
  };

  const selectApplication = async (id: number) => {
    try {
      const detail = await getApplication(id);
      setSelected(detail);
      setFormData(toForm(detail));
      setSelectedAssetIds(detail.assets.map((asset) => asset.id));
      setActiveTab('operation');
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load application');
    }
  };

  const startNew = () => {
    setSelected(null);
    setFormData({ ...emptyForm });
    setSelectedAssetIds([]);
    setActiveTab('operation');
    setError(null);
  };

  const updateField = (name: keyof ApplicationRegisterRequest, value: string) => {
    setFormData((current) => ({ ...current, [name]: value }));
  };

  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!canMutate) return;
    setSaving(true);
    try {
      const saved = selected
        ? await updateApplication(selected.id, formData)
        : await createApplication(formData);
      const withAssets = await replaceApplicationAssets(saved.id, selectedAssetIds);
      setSelected(withAssets);
      setFormData(toForm(withAssets));
      await loadApplications();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save application');
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!selected || !canMutate || !window.confirm(`Delete ${selected.name}?`)) return;
    try {
      await deleteApplication(selected.id);
      startNew();
      await loadApplications();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete application');
    }
  };

  const toggleAsset = (assetId: number) => {
    setSelectedAssetIds((current) =>
      current.includes(assetId) ? current.filter((id) => id !== assetId) : [...current, assetId]
    );
  };

  const renderField = (field: { name: keyof ApplicationRegisterRequest; label: string; type?: string; textarea?: boolean }) => (
    <div className="col-md-6" key={field.name}>
      <label className="form-label small fw-semibold text-secondary">{field.label}</label>
      {field.textarea ? (
        <textarea
          className="form-control form-control-sm"
          rows={field.name === 'notes' ? 5 : 3}
          value={(formData[field.name] as string) || ''}
          disabled={!canMutate}
          onChange={(event) => updateField(field.name, event.target.value)}
        />
      ) : (
        <input
          className="form-control form-control-sm"
          type={field.type || 'text'}
          value={(formData[field.name] as string) || ''}
          disabled={!canMutate}
          onChange={(event) => updateField(field.name, event.target.value)}
        />
      )}
    </div>
  );

  const selectedAssets: ApplicationAssetSummary[] = assetOptions
    .filter((asset) => selectedAssetIds.includes(asset.id))
    .map((asset) => ({ id: asset.id, name: asset.name, type: asset.type, owner: asset.owner, ip: asset.ip }));

  return (
    <div className="container-fluid py-3 application-register">
      <div className="d-flex align-items-center justify-content-between mb-3">
        <div>
          <h1 className="h3 mb-1">Application Register</h1>
          <div className="text-secondary small">Governance records linked to SecMan assets</div>
        </div>
        {canMutate && (
          <button className="btn btn-primary btn-sm" onClick={startNew}>
            <i className="bi bi-plus-lg me-1"></i>
            New Application
          </button>
        )}
      </div>

      {error && <div className="alert alert-danger py-2">{error}</div>}

      <div className="row g-3">
        <div className="col-xl-4">
          <div className="border rounded bg-white">
            <div className="p-3 border-bottom">
              <label className="form-label small fw-semibold text-secondary">Search register</label>
              <div className="input-group input-group-sm">
                <span className="input-group-text"><i className="bi bi-search"></i></span>
                <input
                  className="form-control"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') void loadApplications();
                  }}
                  placeholder="Name, CAR ID, owner, manager, status"
                />
                <button className="btn btn-outline-secondary" onClick={() => void loadApplications()}>
                  Filter
                </button>
              </div>
            </div>
            <div className="table-responsive application-register-list">
              <table className="table table-sm table-hover align-middle mb-0">
                <thead className="table-light">
                  <tr>
                    <th>CAR ID</th>
                    <th>Name</th>
                    <th>Status</th>
                    <th>Criticality</th>
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr><td colSpan={4} className="text-secondary p-3">Loading...</td></tr>
                  ) : filteredApplications.length === 0 ? (
                    <tr><td colSpan={4} className="text-secondary p-3">No application records found.</td></tr>
                  ) : filteredApplications.map((application) => (
                    <tr
                      key={application.id}
                      className={selected?.id === application.id ? 'table-primary' : ''}
                      onClick={() => void selectApplication(application.id)}
                      role="button"
                    >
                      <td className="fw-semibold">{application.carId}</td>
                      <td>
                        <div>{application.name}</div>
                        <div className="text-secondary small">{application.businessOwner} / {application.applicationManager}</div>
                      </td>
                      <td>{application.operationalStatus || '-'}</td>
                      <td>{application.criticality || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div className="col-xl-8">
          <form className="border rounded bg-white" onSubmit={save}>
            <div className="p-3 border-bottom">
              <div className="row g-2">
                <div className="col-md-3">
                  <label className="form-label small fw-semibold text-secondary">CAR ID</label>
                  <input className="form-control form-control-sm" value={formData.carId} required disabled={!canMutate} onChange={(event) => updateField('carId', event.target.value)} />
                </div>
                <div className="col-md-5">
                  <label className="form-label small fw-semibold text-secondary">Application name</label>
                  <input className="form-control form-control-sm" value={formData.name} required disabled={!canMutate} onChange={(event) => updateField('name', event.target.value)} />
                </div>
                <div className="col-md-2">
                  <label className="form-label small fw-semibold text-secondary">Status</label>
                  <input className="form-control form-control-sm" value={formData.operationalStatus || ''} disabled={!canMutate} onChange={(event) => updateField('operationalStatus', event.target.value)} />
                </div>
                <div className="col-md-2">
                  <label className="form-label small fw-semibold text-secondary">Criticality</label>
                  <input className="form-control form-control-sm" value={formData.criticality || ''} disabled={!canMutate} onChange={(event) => updateField('criticality', event.target.value)} />
                </div>
                <div className="col-md-3">
                  <label className="form-label small fw-semibold text-secondary">Business owner</label>
                  <input className="form-control form-control-sm" value={formData.businessOwner} required disabled={!canMutate} onChange={(event) => updateField('businessOwner', event.target.value)} />
                </div>
                <div className="col-md-3">
                  <label className="form-label small fw-semibold text-secondary">Application manager</label>
                  <input className="form-control form-control-sm" value={formData.applicationManager} required disabled={!canMutate} onChange={(event) => updateField('applicationManager', event.target.value)} />
                </div>
                <div className="col-md-3">
                  <label className="form-label small fw-semibold text-secondary">Application champion</label>
                  <input className="form-control form-control-sm" value={formData.applicationChampion || ''} disabled={!canMutate} onChange={(event) => updateField('applicationChampion', event.target.value)} />
                </div>
                <div className="col-md-3">
                  <label className="form-label small fw-semibold text-secondary">Process area</label>
                  <input className="form-control form-control-sm" value={formData.processArea || ''} disabled={!canMutate} onChange={(event) => updateField('processArea', event.target.value)} />
                </div>
                <div className="col-md-3">
                  <label className="form-label small fw-semibold text-secondary">Process cluster</label>
                  <input className="form-control form-control-sm" value={formData.processCluster || ''} disabled={!canMutate} onChange={(event) => updateField('processCluster', event.target.value)} />
                </div>
              </div>
            </div>

            <ul className="nav nav-tabs px-3 pt-2">
              {tabs.map((tab) => (
                <li className="nav-item" key={tab.key}>
                  <button type="button" className={`nav-link small ${activeTab === tab.key ? 'active' : ''}`} onClick={() => setActiveTab(tab.key)}>
                    <i className={`bi ${tab.icon} me-1`}></i>
                    {tab.label}
                  </button>
                </li>
              ))}
            </ul>

            <div className="p-3">
              {activeTab === 'relations' ? (
                <div className="row g-3">
                  <div className="col-md-5">
                    <label className="form-label small fw-semibold text-secondary">Find SecMan assets</label>
                    <input className="form-control form-control-sm mb-2" value={assetSearch} onChange={(event) => setAssetSearch(event.target.value)} placeholder="Asset name, type, owner, IP" />
                    <div className="application-register-assets border rounded">
                      {filteredAssets.map((asset) => (
                        <label className="d-flex gap-2 align-items-start p-2 border-bottom small" key={asset.id}>
                          <input type="checkbox" className="form-check-input mt-1" disabled={!canMutate} checked={selectedAssetIds.includes(asset.id)} onChange={() => toggleAsset(asset.id)} />
                          <span>
                            <span className="fw-semibold">{asset.name}</span>
                            <span className="text-secondary d-block">{asset.type} / {asset.owner}{asset.ip ? ` / ${asset.ip}` : ''}</span>
                          </span>
                        </label>
                      ))}
                    </div>
                  </div>
                  <div className="col-md-7">
                    <div className="small fw-semibold text-secondary mb-2">Linked assets</div>
                    <table className="table table-sm align-middle">
                      <thead className="table-light">
                        <tr><th>Name</th><th>Type</th><th>Owner</th><th>IP</th></tr>
                      </thead>
                      <tbody>
                        {selectedAssets.length === 0 ? (
                          <tr><td colSpan={4} className="text-secondary">No assets linked.</td></tr>
                        ) : selectedAssets.map((asset) => (
                          <tr key={asset.id}>
                            <td>{asset.name}</td>
                            <td>{asset.type}</td>
                            <td>{asset.owner}</td>
                            <td>{asset.ip || '-'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              ) : (
                <div className="row g-3">
                  {fieldGroups[activeTab].map(renderField)}
                </div>
              )}
            </div>

            <div className="d-flex justify-content-between align-items-center border-top p-3">
              <div className="text-secondary small">
                {selected ? `Created by ${selected.createdBy || '-'} / Updated by ${selected.updatedBy || '-'}` : 'New register record'}
              </div>
              {canMutate && (
                <div className="d-flex gap-2">
                  {selected && (
                    <button type="button" className="btn btn-outline-danger btn-sm" onClick={remove}>
                      <i className="bi bi-trash me-1"></i>
                      Delete
                    </button>
                  )}
                  <button type="submit" className="btn btn-primary btn-sm" disabled={saving}>
                    <i className="bi bi-save me-1"></i>
                    {saving ? 'Saving...' : 'Save'}
                  </button>
                </div>
              )}
            </div>
          </form>
        </div>
      </div>
    </div>
  );
};

export default ApplicationRegister;
