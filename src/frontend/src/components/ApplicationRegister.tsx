import React, { useEffect, useMemo, useRef, useState } from 'react';
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
  type ApplicationRegisterSaveRequest,
  type ApplicationRegisterSummary,
} from '../services/applicationRegisterService';
import { authenticatedGet } from '../utils/auth';

type TabKey = 'operation' | 'technical' | 'organization' | 'relations' | 'compliance' | 'dates';

interface AssetOption {
  id: number;
  name: string;
  type: string;
  owner: string;
  ip?: string | null;
}

interface UserOption {
  id: number | null;
  username: string;
  email: string;
}

interface WorkgroupOption {
  id: number;
  name: string;
}

interface ApplicationRegisterExportRecord extends ApplicationRegisterDetail {
  assetIds?: number[];
  assets?: ApplicationAssetSummary[];
}

interface ApplicationRegisterExportFile {
  version: 1;
  exportedAt: string;
  applications: ApplicationRegisterExportRecord[];
}

const emptyForm: ApplicationRegisterSaveRequest = {
  name: '',
  criticality: '',
  operationalStatus: '',
  businessOwner: '',
  applicationManager: '',
  applicationTechnology: '',
  applicationArchitecture: '',
  lastQualityCheck: '',
  informationClassification: '',
  processingOfPersonalData: '',
  icsRelevant: '',
  applicationExportControlRelevant: '',
  operationModel: '',
  productionOperatingHours: '',
  serviceOperatingHours: '',
  backupRecoveryUrl: '',
  incidentAssignmentGroup: '',
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
];

const fieldGroups: Record<Exclude<TabKey, 'relations'>, Array<{ name: keyof ApplicationRegisterSaveRequest; label: string; type?: string; textarea?: boolean; options?: string[] }>> = {
  operation: [],
  technical: [
    { name: 'applicationTechnology', label: 'Application technology', options: ['SAAS', 'Other'] },
    { name: 'applicationArchitecture', label: 'Application architecture', options: ['Legacy', 'Container'] },
    { name: 'backupRecoveryUrl', label: 'Backup and recovery URL' },
  ],
  organization: [
    { name: 'incidentAssignmentGroup', label: 'Incident management group' },
  ],
  compliance: [
    { name: 'informationClassification', label: 'Information classification', options: ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'STRICTLY CONFIDENTIAL'] },
    { name: 'processingOfPersonalData', label: 'Data Privacy', options: ['YES', 'NO'] },
    { name: 'icsRelevant', label: 'ICS relevant', options: ['YES', 'NO'] },
    { name: 'applicationExportControlRelevant', label: 'Application export control relevant', options: ['YES', 'NO'] },
  ],
  dates: [
    { name: 'lastQualityCheck', label: 'Last quality check', type: 'date' },
  ],
};

const toForm = (application: ApplicationRegisterDetail): ApplicationRegisterSaveRequest => {
  const form = { ...emptyForm };
  Object.keys(form).forEach((key) => {
    const typedKey = key as keyof ApplicationRegisterSaveRequest;
    form[typedKey] = (application[typedKey] ?? '') as never;
  });
  return form;
};

const ApplicationRegister: React.FC = () => {
  const [applications, setApplications] = useState<ApplicationRegisterSummary[]>([]);
  const [selected, setSelected] = useState<ApplicationRegisterDetail | null>(null);
  const [formData, setFormData] = useState<ApplicationRegisterSaveRequest>(emptyForm);
  const [assetOptions, setAssetOptions] = useState<AssetOption[]>([]);
  const [userOptions, setUserOptions] = useState<UserOption[]>([]);
  const [workgroupOptions, setWorkgroupOptions] = useState<WorkgroupOption[]>([]);
  const [selectedAssetIds, setSelectedAssetIds] = useState<number[]>([]);
  const [search, setSearch] = useState('');
  const [assetSearch, setAssetSearch] = useState('');
  const [activeTab, setActiveTab] = useState<TabKey>('operation');
  const [showForm, setShowForm] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [exporting, setExporting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const importInputRef = useRef<HTMLInputElement | null>(null);

  const user = getUser();
  const canMutate = isAdmin(user?.roles) || isSecChampion(user?.roles);
  const canImportExport = isAdmin(user?.roles);

  useEffect(() => {
    void loadApplications();
    void loadAssets();
    void loadUsers();
    void loadWorkgroups();
  }, []);

  const filteredApplications = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return applications;

    return applications.filter((application) =>
      [
        application.carId,
        application.name,
        application.businessOwner,
        application.applicationManager,
        application.operationalStatus || '',
        application.criticality || '',
      ].some((value) => value.toLowerCase().includes(query))
    );
  }, [applications, search]);

  const searchPreview = useMemo(() => {
    if (!search.trim()) return [];
    return filteredApplications.slice(0, 5);
  }, [filteredApplications, search]);
  const filteredAssets = useMemo(() => {
    const query = assetSearch.trim().toLowerCase();
    if (!query) return assetOptions.slice(0, 80);
    return assetOptions.filter((asset) => asset.name.toLowerCase().includes(query)).slice(0, 80);
  }, [assetOptions, assetSearch]);

  const loadApplications = async () => {
    setLoading(true);
    try {
      const data = await listApplications('');
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

  const loadUsers = async () => {
    try {
      const response = await authenticatedGet('/api/users');
      if (response.ok) {
        const users: UserOption[] = await response.json();
        setUserOptions(users.filter((option) => !option.email || !option.email.endsWith('@pending.local')));
      }
    } catch (err) {
      console.error('Failed to load users for application register', err);
    }
  };

  const loadWorkgroups = async () => {
    try {
      const response = await authenticatedGet('/api/workgroups');
      if (response.ok) {
        const workgroups: WorkgroupOption[] = await response.json();
        setWorkgroupOptions(workgroups.sort((left, right) => left.name.localeCompare(right.name)));
      }
    } catch (err) {
      console.error('Failed to load workgroups for application register', err);
    }
  };

  const editApplication = async (id: number) => {
    try {
      const detail = await getApplication(id);
      setSelected(detail);
      setFormData(toForm(detail));
      setSelectedAssetIds((detail.assets ?? []).map((asset) => asset.id));
      setActiveTab('operation');
      setShowForm(true);
      setError(null);
      setSuccess(null);
      window.setTimeout(() => window.scrollTo({ top: 0, behavior: 'smooth' }), 0);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load application');
    }
  };

  const startNew = () => {
    setSelected(null);
    setFormData({ ...emptyForm });
    setSelectedAssetIds([]);
    setActiveTab('operation');
    setShowForm(true);
    setError(null);
    setSuccess(null);
  };

  const cancelForm = () => {
    setSelected(null);
    setFormData({ ...emptyForm });
    setSelectedAssetIds([]);
    setActiveTab('operation');
    setShowForm(false);
    setError(null);
    setSuccess(null);
  };

  const updateField = (name: keyof ApplicationRegisterSaveRequest, value: string) => {
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
      setShowForm(false);
      await loadApplications();
      setError(null);
      setSuccess(selected ? 'Application updated.' : 'Application created.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save application');
    } finally {
      setSaving(false);
    }
  };

  const deleteApplicationFromList = async (application: ApplicationRegisterSummary | ApplicationRegisterDetail) => {
    if (!canMutate || !window.confirm(`Delete ${application.name}?`)) return;
    setDeletingId(application.id);
    try {
      await deleteApplication(application.id);
      if (selected?.id === application.id) {
        cancelForm();
      }
      await loadApplications();
      setSuccess('Application deleted.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete application');
    } finally {
      setDeletingId(null);
    }
  };

  const exportApplications = async () => {
    if (!canImportExport) return;
    setExporting(true);
    setError(null);
    setSuccess(null);
    try {
      const allApplications = await listApplications('');
      const details = await Promise.all(allApplications.map((application) => getApplication(application.id)));
      const payload: ApplicationRegisterExportFile = {
        version: 1,
        exportedAt: new Date().toISOString(),
        applications: details.map((application) => ({
          ...application,
          assetIds: (application.assets ?? []).map((asset) => asset.id),
        })),
      };
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `secman-applications-${new Date().toISOString().slice(0, 10)}.json`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setSuccess(`Exported ${details.length} applications.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to export applications');
    } finally {
      setExporting(false);
    }
  };

  const toImportRecord = (value: unknown): ApplicationRegisterExportFile => {
    if (!value || typeof value !== 'object') {
      throw new Error('Import file must be a SecMan application export JSON file.');
    }
    const maybeFile = value as Partial<ApplicationRegisterExportFile>;
    if (!Array.isArray(maybeFile.applications)) {
      throw new Error('Import file does not contain an applications array.');
    }
    return {
      version: 1,
      exportedAt: typeof maybeFile.exportedAt === 'string' ? maybeFile.exportedAt : '',
      applications: maybeFile.applications,
    };
  };

  const importApplications = async (file: File) => {
    if (!canImportExport) return;
    setImporting(true);
    setError(null);
    setSuccess(null);
    try {
      const payload = toImportRecord(JSON.parse(await file.text()));
      const existingApplications = await listApplications('');
      const existingByCarId = new Map(existingApplications.map((application) => [application.carId.toLowerCase(), application]));
      let createdCount = 0;
      let updatedCount = 0;

      for (const application of payload.applications) {
        if (!application.carId || !application.name || !application.businessOwner || !application.applicationManager) {
          throw new Error('Each imported application must include carId, name, businessOwner, and applicationManager.');
        }
        const existing = existingByCarId.get(application.carId.toLowerCase());
        const assetIds = application.assetIds ?? application.assets?.map((asset) => asset.id) ?? [];
        const saved = existing
          ? await updateApplication(existing.id, toForm(application))
          : await createApplication(toForm(application));
        await replaceApplicationAssets(saved.id, assetIds);
        if (existing) {
          updatedCount += 1;
        } else {
          createdCount += 1;
          existingByCarId.set(application.carId.toLowerCase(), saved);
        }
      }

      cancelForm();
      await loadApplications();
      setSearch('');
      setSuccess(`Imported ${payload.applications.length} applications (${createdCount} created, ${updatedCount} updated).`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to import applications');
    } finally {
      setImporting(false);
      if (importInputRef.current) {
        importInputRef.current.value = '';
      }
    }
  };

  const toggleAsset = (assetId: number) => {
    setSelectedAssetIds((current) =>
      current.includes(assetId) ? current.filter((id) => id !== assetId) : [...current, assetId]
    );
  };

  const renderField = (field: { name: keyof ApplicationRegisterSaveRequest; label: string; type?: string; textarea?: boolean; options?: string[] }) => (
    <div className="col-md-6" key={field.name}>
      <label className="form-label small fw-semibold text-secondary">{field.label}</label>
      {field.options ? (
        <select
          className="form-select form-select-sm"
          value={(formData[field.name] as string) || ''}
          disabled={!canMutate}
          onChange={(event) => updateField(field.name, event.target.value)}
        >
          <option value="">Select {field.label.toLowerCase()}</option>
          {field.options.map((option) => (
            <option key={option} value={option}>{option}</option>
          ))}
        </select>
      ) : field.name === 'incidentAssignmentGroup' ? (
        <select
          className="form-select form-select-sm"
          value={(formData.incidentAssignmentGroup as string) || ''}
          disabled={!canMutate}
          onChange={(event) => updateField('incidentAssignmentGroup', event.target.value)}
        >
          <option value="">Select workgroup</option>
          {formData.incidentAssignmentGroup && !workgroupOptions.some((option) => option.name === formData.incidentAssignmentGroup) && (
            <option value={formData.incidentAssignmentGroup}>{formData.incidentAssignmentGroup}</option>
          )}
          {workgroupOptions.map((option) => (
            <option key={option.id} value={option.name}>{option.name}</option>
          ))}
        </select>
      ) : field.textarea ? (
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

  const renderOperationFields = () => (
    <>
      <div className="col-md-6">
        <label className="form-label small fw-semibold text-secondary">Operation model</label>
        <select
          className="form-select form-select-sm"
          value={formData.operationModel || ''}
          disabled={!canMutate}
          onChange={(event) => updateField('operationModel', event.target.value)}
        >
          <option value="">Select operation model</option>
          <option value="SAAS">SAAS</option>
          <option value="self-hosted">self-hosted</option>
        </select>
      </div>
      <div className="col-md-6">
        <label className="form-label small fw-semibold text-secondary">Production operating hours</label>
        <select
          className="form-select form-select-sm"
          value={formData.productionOperatingHours || ''}
          disabled={!canMutate}
          onChange={(event) => updateField('productionOperatingHours', event.target.value)}
        >
          <option value="">Select operating hours</option>
          <option value="N/A">N/A</option>
          <option value="5x8">5x8</option>
          <option value="24x7">24x7</option>
          <option value="OTHER">OTHER</option>
        </select>
      </div>
      <div className="col-md-6">
        <label className="form-label small fw-semibold text-secondary">Service operating hours</label>
        <select
          className="form-select form-select-sm"
          value={formData.serviceOperatingHours || ''}
          disabled={!canMutate}
          onChange={(event) => updateField('serviceOperatingHours', event.target.value)}
        >
          <option value="">Select operating hours</option>
          <option value="N/A">N/A</option>
          <option value="5x8">5x8</option>
          <option value="24x7">24x7</option>
          <option value="OTHER">OTHER</option>
        </select>
      </div>
      {fieldGroups.operation.map(renderField)}
    </>
  );

  const selectedAssets: ApplicationAssetSummary[] = assetOptions
    .filter((asset) => selectedAssetIds.includes(asset.id))
    .map((asset) => ({ id: asset.id, name: asset.name, type: asset.type, owner: asset.owner, ip: asset.ip }));

  return (
    <div className="container-fluid p-4 application-register">
      <div className="d-flex align-items-center justify-content-between mb-4">
        <h2>Application Register</h2>
        <div className="btn-group" role="group">
          {canMutate && (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                if (showForm) {
                  cancelForm();
                } else {
                  startNew();
                }
              }}
            >
              {showForm ? 'Cancel' : 'Add New Application'}
            </button>
          )}
          {canImportExport && (
            <>
              <button type="button" className="btn btn-success" onClick={() => void exportApplications()} disabled={exporting}>
                <i className="bi bi-download me-2"></i>
                {exporting ? 'Exporting...' : 'Export Applications'}
              </button>
              <button type="button" className="btn btn-outline-success" onClick={() => importInputRef.current?.click()} disabled={importing}>
                <i className="bi bi-upload me-2"></i>
                {importing ? 'Importing...' : 'Import Applications'}
              </button>
              <input
                ref={importInputRef}
                type="file"
                className="d-none"
                accept="application/json,.json"
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file) void importApplications(file);
                }}
              />
            </>
          )}
        </div>
      </div>

      {error && <div className="alert alert-danger py-2">{error}</div>}
      {success && <div className="alert alert-success py-2">{success}</div>}

      {showForm && (
        <div className="card mb-4">
          <div className="card-body">
            <h5 className="card-title">{selected ? 'Edit Application' : 'Add New Application'}</h5>
            <form onSubmit={save}>
              <div className="row g-3">
                <div className="col-md-3">
                  <label className="form-label">CAR ID</label>
                  <input className="form-control" value={selected?.carId || 'Allocated on save'} disabled />
                </div>
                <div className="col-md-5">
                  <label className="form-label">Application name *</label>
                  <input className="form-control" value={formData.name} required disabled={!canMutate} onChange={(event) => updateField('name', event.target.value)} />
                </div>
                <div className="col-md-2">
                  <label className="form-label">Status</label>
                  <select
                    className="form-select"
                    value={formData.operationalStatus || ''}
                    disabled={!canMutate}
                    onChange={(event) => updateField('operationalStatus', event.target.value)}
                  >
                    <option value="">Select Status</option>
                    <option value="Operation">Operation</option>
                    <option value="Decommissioned">Decommissioned</option>
                  </select>
                </div>
                <div className="col-md-2">
                  <label className="form-label">Criticality</label>
                  <select
                    className="form-select"
                    value={formData.criticality || ''}
                    disabled={!canMutate}
                    onChange={(event) => updateField('criticality', event.target.value)}
                  >
                    <option value="">Select Criticality</option>
                    <option value="LOW">LOW</option>
                    <option value="MEDIUM">MEDIUM</option>
                    <option value="HIGH">HIGH</option>
                  </select>
                </div>
                <div className="col-md-3">
                  <label className="form-label">Business owner *</label>
                  <select
                    className="form-select"
                    value={formData.businessOwner}
                    required
                    disabled={!canMutate}
                    onChange={(event) => updateField('businessOwner', event.target.value)}
                  >
                    <option value="">Select Business Owner</option>
                    {formData.businessOwner && !userOptions.some((option) => option.username === formData.businessOwner) && (
                      <option value={formData.businessOwner}>{formData.businessOwner}</option>
                    )}
                    {userOptions.map((option) => (
                      <option key={`owner-${option.id ?? option.email}`} value={option.username}>
                        {option.username}{option.email ? ` (${option.email})` : ''}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="col-md-3">
                  <label className="form-label">Application manager *</label>
                  <select
                    className="form-select"
                    value={formData.applicationManager}
                    required
                    disabled={!canMutate}
                    onChange={(event) => updateField('applicationManager', event.target.value)}
                  >
                    <option value="">Select Application Manager</option>
                    {formData.applicationManager && !userOptions.some((option) => option.username === formData.applicationManager) && (
                      <option value={formData.applicationManager}>{formData.applicationManager}</option>
                    )}
                    {userOptions.map((option) => (
                      <option key={`manager-${option.id ?? option.email}`} value={option.username}>
                        {option.username}{option.email ? ` (${option.email})` : ''}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              <ul className="nav nav-tabs mt-4">
                {tabs.map((tab) => (
                  <li className="nav-item" key={tab.key}>
                    <button type="button" className={`nav-link ${activeTab === tab.key ? 'active' : ''}`} onClick={() => setActiveTab(tab.key)}>
                      <i className={`bi ${tab.icon} me-1`}></i>
                      {tab.label}
                    </button>
                  </li>
                ))}
              </ul>

              <div className="border border-top-0 p-3 mb-3">
                {activeTab === 'relations' ? (
                  <div className="row g-3">
                    <div className="col-md-5">
                      <label className="form-label">Find SecMan assets</label>
                      <input className="form-control mb-2" value={assetSearch} onChange={(event) => setAssetSearch(event.target.value)} placeholder="Asset name" />
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
                      <div className="fw-semibold mb-2">Linked assets</div>
                      <table className="table table-sm align-middle">
                        <thead>
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
                    {activeTab === 'operation' ? renderOperationFields() : fieldGroups[activeTab].map(renderField)}
                  </div>
                )}
              </div>

              <div className="d-flex justify-content-between align-items-center">
                <div className="text-secondary small">
                  {selected ? `Created by ${selected.createdBy || '-'} / Updated by ${selected.updatedBy || '-'}` : 'New register record'}
                </div>
                {canMutate && (
                  <div className="d-flex gap-2">
                    {selected && (
                      <button type="button" className="btn btn-outline-danger" onClick={() => void deleteApplicationFromList(selected)} disabled={deletingId === selected.id}>
                        <i className="bi bi-trash me-1"></i>
                        {deletingId === selected.id ? 'Deleting...' : 'Delete'}
                      </button>
                    )}
                    <button type="submit" className="btn btn-success" disabled={saving}>
                      {saving ? 'Saving...' : selected ? 'Update' : 'Save'}
                    </button>
                    <button type="button" className="btn btn-secondary" onClick={cancelForm}>
                      Cancel
                    </button>
                  </div>
                )}
              </div>
            </form>
          </div>
        </div>
      )}

      <div className="card mb-4">
        <div className="card-body">
          <h6 className="card-title">Filters</h6>
          <div className="row">
            <div className="col-md-6">
              <label className="form-label">Search register</label>
              <div className="input-group">
                <span className="input-group-text"><i className="bi bi-search"></i></span>
                <input
                  className="form-control"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  placeholder="Name, CAR ID, owner, manager, status"
                />
                <button className="btn btn-outline-secondary" onClick={() => setSearch('')} disabled={!search}>
                  Clear
                </button>
              </div>
              {search.trim() && (
                <div className="border rounded mt-2 bg-white">
                  <div className="px-3 py-2 small text-secondary border-bottom">
                    {filteredApplications.length} potential {filteredApplications.length === 1 ? 'hit' : 'hits'}
                  </div>
                  {searchPreview.length === 0 ? (
                    <div className="px-3 py-2 text-secondary">No matches found.</div>
                  ) : (
                    searchPreview.map((application) => (
                      <button
                        type="button"
                        className="list-group-item list-group-item-action border-0 border-bottom text-start w-100"
                        key={`search-preview-${application.id}`}
                        onClick={() => void editApplication(application.id)}
                      >
                        <div className="fw-semibold">{application.name}</div>
                        <div className="small text-secondary">
                          CAR {application.carId} / {application.businessOwner} / {application.applicationManager}
                        </div>
                      </button>
                    ))
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="card">
        <div className="card-body">
          <div className="d-flex justify-content-between align-items-center mb-3">
            <h5 className="card-title mb-0">Applications ({filteredApplications.length})</h5>
            <span className="text-secondary small">Existing application records</span>
          </div>
          <div className="table-responsive application-register-list">
            <table className="table table-striped table-hover align-middle">
              <thead>
                <tr>
                  <th>CAR ID</th>
                  <th>Name</th>
                  <th>Ownership</th>
                  <th>Status</th>
                  <th>Criticality</th>
                  <th>Updated</th>
                  {canMutate && <th>Actions</th>}
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr><td colSpan={canMutate ? 7 : 6} className="text-secondary p-3">Loading...</td></tr>
                ) : filteredApplications.length === 0 ? (
                  <tr>
                    <td colSpan={canMutate ? 7 : 6} className="p-4">
                      <div className="d-flex align-items-center justify-content-between">
                        <span className="text-secondary">No application records found.</span>
                        {canMutate && (
                          <button type="button" className="btn btn-sm btn-primary" onClick={startNew}>
                            Add New Application
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ) : filteredApplications.map((application) => (
                  <tr key={application.id}>
                    <td className="fw-semibold">{application.carId}</td>
                    <td>
                      <div className="fw-semibold">{application.name}</div>
                      <div className="text-secondary small">Application register record</div>
                    </td>
                    <td>
                      <div>{application.businessOwner}</div>
                      <div className="text-secondary small">Manager: {application.applicationManager}</div>
                    </td>
                    <td>
                      {application.operationalStatus ? (
                        <span className="badge bg-secondary">{application.operationalStatus}</span>
                      ) : (
                        <span className="text-muted">-</span>
                      )}
                    </td>
                    <td>
                      {application.criticality ? (
                        <span className="badge bg-light text-dark border">{application.criticality}</span>
                      ) : (
                        <span className="text-muted">-</span>
                      )}
                    </td>
                    <td>{application.updatedAt ? new Date(application.updatedAt).toLocaleDateString() : '-'}</td>
                    {canMutate && (
                      <td>
                        <div className="btn-group" role="group">
                          <button
                            type="button"
                            className="btn btn-sm btn-outline-primary"
                            onClick={() => void editApplication(application.id)}
                          >
                            <i className="bi bi-pencil"></i> Edit
                          </button>
                          <button
                            type="button"
                            className="btn btn-sm btn-outline-danger"
                            onClick={() => void deleteApplicationFromList(application)}
                            disabled={deletingId === application.id}
                          >
                            <i className="bi bi-trash"></i> {deletingId === application.id ? 'Deleting...' : 'Delete'}
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ApplicationRegister;
