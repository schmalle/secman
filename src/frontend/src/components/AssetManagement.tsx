import React, { useState, useEffect, useMemo } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete, getUser, hasVulnAccess } from '../utils/auth';
import { isAdmin, isSecChampion } from '../utils/permissions';
import { useClientRoles } from '../utils/useClientAuth';
import PortHistory from './PortHistory';
import VulnerabilityHistory from './VulnerabilityHistory';
import { BulkDeleteConfirmModal } from './BulkDeleteConfirmModal';
import { bulkDeleteAssets, type BulkDeleteResult } from '../services/assetService';
import { exportVulnerabilitiesServerSide, cancelExportJob, getDistinctAdDomains, type ExportJob } from '../services/vulnerabilityManagementService';
import { scrollContainerStyle, stickyHeaderCellStyle } from './scrollableTableStyles';

interface WorkgroupSummary {
  id: number;
  name: string;
}

interface Workgroup {
  id: number;
  name: string;
  description?: string;
}

interface OwnerCandidate {
  value: string;
  label: string;
}

interface Asset {
  id?: number;
  name: string;
  crowdStrikeHostname?: string;
  nameOverridden?: boolean;
  nameOverriddenAt?: string;
  nameOverriddenBy?: string;
  type: string;
  ip?: string;
  ipAddresses?: string[];
  uri?: string;
  owner: string;
  description?: string;
  groups?: string;
  cloudAccountId?: string;
  cloudInstanceId?: string;
  osVersion?: string;
  adDomain?: string;
  criticality?: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NA' | null;
  createdAt?: string;
  updatedAt?: string;
  workgroups?: WorkgroupSummary[];
}

interface AssetOverviewResponse {
  items: Array<Omit<Asset, 'id' | 'ip'> & { assetId: number; ipAddress?: string }>;
  matchingCount: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

const AssetManagement: React.FC = () => {
  const [assets, setAssets] = useState<Asset[]>([]);
  const [workgroups, setWorkgroups] = useState<Workgroup[]>([]);
  const [adDomainOptions, setAdDomainOptions] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [accessibleCount, setAccessibleCount] = useState<number | null>(null);
  const [matchingCount, setMatchingCount] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [totalPages, setTotalPages] = useState(0);
  const [compactRows, setCompactRows] = useState(false);
  const [urlStateReady, setUrlStateReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingAsset, setEditingAsset] = useState<Asset | null>(null);
  const [formData, setFormData] = useState<Asset & { workgroupIds?: number[] }>({
    name: '',
    type: '',
    ip: '',
    uri: '',
    owner: '',
    description: '',
    criticality: undefined,
    workgroupIds: []
  });
  const [showPortHistory, setShowPortHistory] = useState(false);
  const [selectedAssetForPorts, setSelectedAssetForPorts] = useState<Asset | null>(null);
  const [showVulnerabilities, setShowVulnerabilities] = useState(false);
  const [selectedAssetForVulns, setSelectedAssetForVulns] = useState<Asset | null>(null);

  // Filter states
  const [nameFilter, setNameFilter] = useState('');
  const [ipFilter, setIpFilter] = useState('');
  const [ownerFilter, setOwnerFilter] = useState('');
  const [adDomainFilter, setAdDomainFilter] = useState('');
  const [accountIdFilter, setAccountIdFilter] = useState('');
  const [workgroupFilter, setWorkgroupFilter] = useState('');

  // Bulk delete states (Feature 029 - User Story 1)
  const [showBulkDeleteModal, setShowBulkDeleteModal] = useState(false);
  const [isDeletingBulk, setIsDeletingBulk] = useState(false);
  const [bulkDeleteSuccess, setBulkDeleteSuccess] = useState<string | null>(null);

  // Export vulnerabilities state
  const [exportLoading, setExportLoading] = useState(false);
  const [exportProgress, setExportProgress] = useState<ExportJob | null>(null);
  const [exportJobId, setExportJobId] = useState<string | null>(null);
  const [exportError, setExportError] = useState<string | null>(null);

  // Owner candidates for select dropdown
  const [ownerCandidates, setOwnerCandidates] = useState<OwnerCandidate[]>([]);
  // Read after mount, not during render: getUser() is null during SSR, so using it
  // here directly would make the server and first client render disagree (see
  // utils/useClientAuth). The mount effect below keeps calling getUser() directly —
  // effects only ever run on the client, where it is already correct.
  const roles = useClientRoles();
  const canManageGrants = isAdmin(roles) || isSecChampion(roles);
  const canAssignOwner = canManageGrants;

  // Domain validation state (Feature 043 - User Story 2)
  const [domainError, setDomainError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const requestedSize = Number(params.get('pageSize')) || Number(window.localStorage.getItem('assetOverviewPageSize'));
    setPage(Math.max(0, Number(params.get('page')) || 0));
    if ([25, 50, 100, 250].includes(requestedSize)) setPageSize(requestedSize);
    setNameFilter(params.get('name') || '');
    setIpFilter(params.get('ip') || '');
    setOwnerFilter(params.get('owner') || '');
    setAdDomainFilter(params.get('adDomain') || '');
    setAccountIdFilter(params.get('accountId') || '');
    setWorkgroupFilter(params.get('workgroupId') || '');
    setCompactRows(window.localStorage.getItem('assetOverviewCompactRows') === 'true');
    setUrlStateReady(true);
    fetchWorkgroups();
    getDistinctAdDomains().then(setAdDomainOptions).catch(() => {});
    fetchAssetCount();
    if (isAdmin(getUser()?.roles) || isSecChampion(getUser()?.roles)) {
      fetchOwnerCandidates();
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const timer = window.setTimeout(() => fetchAssets(controller.signal), 300);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [page, pageSize, nameFilter, ipFilter, ownerFilter, adDomainFilter, accountIdFilter, workgroupFilter]);

  useEffect(() => {
    if (!urlStateReady) return;
    const params = new URLSearchParams();
    if (page) params.set('page', String(page));
    if (pageSize !== 50) params.set('pageSize', String(pageSize));
    if (nameFilter) params.set('name', nameFilter);
    if (ipFilter) params.set('ip', ipFilter);
    if (ownerFilter) params.set('owner', ownerFilter);
    if (adDomainFilter) params.set('adDomain', adDomainFilter);
    if (accountIdFilter) params.set('accountId', accountIdFilter);
    if (workgroupFilter) params.set('workgroupId', workgroupFilter);
    window.history.replaceState(null, '', `${window.location.pathname}${params.size ? `?${params}` : ''}`);
  }, [urlStateReady, page, pageSize, nameFilter, ipFilter, ownerFilter, adDomainFilter, accountIdFilter, workgroupFilter]);

  const fetchAssetCount = async () => {
    try {
      const response = await authenticatedGet('/api/assets/count');
      if (response.ok) setAccessibleCount((await response.json()).count);
    } catch {
      // The paged result remains usable if the independent total cannot load.
    }
  };

  const fetchAssets = async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
      if (nameFilter.trim()) params.set('name', nameFilter.trim());
      if (ipFilter.trim()) params.set('ip', ipFilter.trim());
      if (ownerFilter.trim()) params.set('owner', ownerFilter.trim());
      if (adDomainFilter.trim()) params.set('adDomain', adDomainFilter.trim());
      if (accountIdFilter.trim()) params.set('accountId', accountIdFilter.trim());
      if (workgroupFilter) params.set('workgroupId', workgroupFilter);
      const response = await authenticatedGet(`/api/assets/search?${params}`, { signal });
      if (response.ok) {
        const data: AssetOverviewResponse = await response.json();
        setAssets(data.items.map(item => ({ ...item, id: item.assetId, ip: item.ipAddress })));
        setMatchingCount(data.matchingCount);
        setTotalPages(data.totalPages);
        setError(null);
      } else {
        setError(`Failed to fetch assets: ${response.status}`);
      }
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchWorkgroups = async () => {
    try {
      const response = await authenticatedGet('/api/workgroups');
      if (response.ok) {
        const data: Workgroup[] = await response.json();
        setWorkgroups(data);
      } else if (response.status === 403) {
        // User doesn't have ADMIN role - workgroups filter not available
        console.info('Workgroups filter not available for non-admin users');
        setWorkgroups([]);
      } else {
        console.error('Failed to fetch workgroups, status:', response.status);
        setWorkgroups([]);
      }
    } catch (err) {
      console.error('Failed to fetch workgroups:', err);
      setWorkgroups([]);
    }
  };

  const fetchOwnerCandidates = async () => {
    try {
      const response = await authenticatedGet('/api/assets/owner-candidates');
      if (response.ok) {
        const data: OwnerCandidate[] = await response.json();
        setOwnerCandidates(data);
      }
    } catch (err) {
      console.error('Failed to fetch owner candidates:', err);
    }
  };

  /**
   * Validate Active Directory domain field
   * Feature 043: User Story 2 - Manual Domain Editing
   *
   * Validation rules:
   * - Pattern: alphanumeric, dots, and hyphens only
   * - Cannot start or end with a dot
   * - Max length 255 characters
   * - Optional field (empty is valid)
   */
  const validateDomain = (value: string): boolean => {
    if (!value || value.trim() === '') {
      setDomainError(null);
      return true; // Empty is valid (optional field)
    }

    const trimmedValue = value.trim();

    // Check length
    if (trimmedValue.length > 255) {
      setDomainError('Domain cannot exceed 255 characters');
      return false;
    }

    // Check pattern: only alphanumeric, dots, and hyphens
    const regex = /^[a-zA-Z0-9.-]+$/;
    if (!regex.test(trimmedValue)) {
      setDomainError('Domain must contain only letters, numbers, dots, and hyphens');
      return false;
    }

    // Cannot start with a dot
    if (trimmedValue.startsWith('.')) {
      setDomainError('Domain cannot start with a dot');
      return false;
    }

    // Cannot end with a dot
    if (trimmedValue.endsWith('.')) {
      setDomainError('Domain cannot end with a dot');
      return false;
    }

    // Cannot have consecutive dots
    if (trimmedValue.includes('..')) {
      setDomainError('Domain cannot contain consecutive dots');
      return false;
    }

    // Valid
    setDomainError(null);
    return true;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // Validate domain before submission (Feature 043)
    if (formData.adDomain && !validateDomain(formData.adDomain)) {
      return; // Validation error is already set in state
    }

    if (!editingAsset && !canManageGrants && !formData.workgroupIds?.length) {
      setError('Select an enabled workgroup for the new asset.');
      return;
    }
    const payload = { ...formData };
    if (!canManageGrants) {
      delete payload.adDomain;
      if (editingAsset) delete payload.workgroupIds;
    }
    try {
      if (editingAsset) {
        await authenticatedPut(`/api/assets/${editingAsset.id}`, payload);
      } else {
        await authenticatedPost('/api/assets', payload);
      }

      await fetchAssets();
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const handleEdit = async (asset: Asset) => {
    const response = await authenticatedGet(`/api/assets/${asset.id}`);
    if (!response.ok) {
      setError('Unable to load the asset details for editing.');
      return;
    }
    const detail: Asset = await response.json();
    setEditingAsset(detail);
    setFormData({ ...detail, workgroupIds: detail.workgroups?.map(wg => wg.id) || [] });
    setShowForm(true);

    // Scroll to top to show the form
    setTimeout(() => {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }, 100);
  };

  const handleResetName = async () => {
    if (!editingAsset?.id) return;

    try {
      const response = await authenticatedPost(`/api/assets/${editingAsset.id}/name/reset`);
      if (!response.ok) {
        const body = await response.json().catch(() => ({ error: 'Failed to reset asset name' }));
        throw new Error(body.error || `Failed to reset asset name: ${response.status}`);
      }

      const updated: Asset = await response.json();
      setEditingAsset(updated);
      setFormData({
        ...updated,
        workgroupIds: updated.workgroups?.map(workgroup => workgroup.id) || []
      });
      await fetchAssets();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reset asset name');
    }
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this asset? This will also delete all related vulnerabilities and exceptions.')) {
      return;
    }

    try {
      const response = await authenticatedDelete(`/api/assets/${id}`);

      if (!response.ok) {
        // Handle different error response formats
        const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));

        // Check for new DeletionErrorDto format (cascade deletion errors)
        if (errorData.errorType) {
          const errorMessage = `${errorData.cause}\n\nSuggested action: ${errorData.suggestedAction}`;
          throw new Error(errorMessage);
        }

        // Handle old ErrorResponse format
        throw new Error(errorData.error || `Failed to delete asset: ${response.status}`);
      }

      // Handle success - can be either old format (message) or new format (CascadeDeletionResultDto)
      const result = await response.json().catch(() => null);

      if (result && result.deletedVulnerabilities !== undefined) {
        // New cascade deletion result format
        console.log(`Deleted asset ${result.assetId}: ${result.deletedVulnerabilities} vulnerabilities, ${result.deletedExceptions} exceptions, ${result.deletedRequests} requests`);
      }

      await fetchAssets();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred while deleting the asset');
    }
  };

  /**
   * Handle bulk delete of all assets
   * Feature 029: User Story 1 - Bulk Delete Assets
   */
  const handleBulkDelete = async () => {
    setIsDeletingBulk(true);
    setError(null);
    setBulkDeleteSuccess(null);

    try {
      const result: BulkDeleteResult = await bulkDeleteAssets();

      // Show success message
      setBulkDeleteSuccess(result.message);

      // Close modal
      setShowBulkDeleteModal(false);

      // Refresh asset list
      await fetchAssets();

      // Clear success message after 5 seconds
      setTimeout(() => setBulkDeleteSuccess(null), 5000);

    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred during bulk delete');
      setShowBulkDeleteModal(false);
    } finally {
      setIsDeletingBulk(false);
    }
  };

  /**
   * Handle export of all vulnerabilities to Excel
   *
   * Uses background job pattern with progress tracking:
   * - Starts export job asynchronously
   * - Polls for progress (updates UI every 2 seconds)
   * - Downloads file when complete
   * - Supports cancellation
   */
  const handleExportVulnerabilities = async () => {
    try {
      setExportLoading(true);
      setExportProgress(null);
      setError(null);

      // Use server-side export with progress tracking
      await exportVulnerabilitiesServerSide((job) => {
        setExportProgress(job);
        setExportJobId(job.jobId);
      });

      // Show brief success message
      setBulkDeleteSuccess('Export completed successfully!');
      setTimeout(() => setBulkDeleteSuccess(null), 5000);

    } catch (err) {
      setExportError(err instanceof Error ? err.message : 'Failed to export vulnerabilities');
      setTimeout(() => setExportError(null), 10000);
    } finally {
      setExportLoading(false);
      setExportProgress(null);
      setExportJobId(null);
    }
  };

  /**
   * Handle cancellation of running export
   */
  const handleCancelExport = async () => {
    if (!exportJobId) return;

    try {
      await cancelExportJob(exportJobId);
      setExportLoading(false);
      setExportProgress(null);
      setExportJobId(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to cancel export');
    }
  };

  const resetForm = () => {
    setFormData({
      name: '',
      type: '',
      ip: '',
      uri: '',
      owner: '',
      description: '',
      adDomain: '',
      criticality: undefined,
      workgroupIds: []
    });
    setEditingAsset(null);
    setShowForm(false);
    setDomainError(null); // Clear domain validation error (Feature 043)
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const { name, value } = e.target;
    setFormData(prev => ({ ...prev, [name]: value }));
  };

  const handleCriticalityChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    setFormData(prev => ({
      ...prev,
      criticality: value === '' ? undefined : (value as 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NA')
    }));
  };

  const handleWorkgroupChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const workgroupId = parseInt(e.target.value);
    const { checked } = e.target;
    setFormData(prev => {
      const newWorkgroupIds = checked
        ? [...(prev.workgroupIds || []), workgroupId]
        : (prev.workgroupIds || []).filter(id => id !== workgroupId);
      return { ...prev, workgroupIds: newWorkgroupIds };
    });
  };

  const handleShowPorts = (asset: Asset) => {
    setSelectedAssetForPorts(asset);
    setShowPortHistory(true);
  };

  const handleClosePortHistory = () => {
    setShowPortHistory(false);
    setSelectedAssetForPorts(null);
  };

  const handleShowVulnerabilities = (asset: Asset) => {
    setSelectedAssetForVulns(asset);
    setShowVulnerabilities(true);
  };

  const handleCloseVulnerabilities = () => {
    setShowVulnerabilities(false);
    setSelectedAssetForVulns(null);
  };

  // Filter assets based on current filter values. Memoized: the render below reads
  // this several times, and each call used to re-scan the full asset array.
  const filteredAssets = useMemo(() => {
    return assets.filter(asset => {
      // Text filters use partial matching
      const nameMatch = !nameFilter || asset.name.toLowerCase().includes(nameFilter.toLowerCase());
      const displayedIps = asset.ipAddresses?.length ? asset.ipAddresses : asset.ip ? [asset.ip] : [];
      const ipMatch = !ipFilter || displayedIps.some(ip => ip.toLowerCase().includes(ipFilter.toLowerCase()));
      const accountIdMatch = !accountIdFilter || (asset.cloudAccountId && asset.cloudAccountId.toLowerCase().includes(accountIdFilter.toLowerCase()));
      // Dropdown filters use exact matching
      const ownerMatch = !ownerFilter || asset.owner === ownerFilter;
      const adDomainMatch = !adDomainFilter || asset.adDomain === adDomainFilter;
      const workgroupMatch = !workgroupFilter || (
        asset.workgroups && asset.workgroups.some(wg => wg.name === workgroupFilter)
      );

      return nameMatch && ipMatch && accountIdMatch && ownerMatch && adDomainMatch && workgroupMatch;
    });
  }, [assets, nameFilter, ipFilter, accountIdFilter, ownerFilter, adDomainFilter, workgroupFilter]);

  // Dropdown option lists derived from the asset array — memoized so the
  // Set + sort work runs when assets change, not on every keystroke re-render.
  const ownerOptions = useMemo(
    () => [...new Set(assets.map(a => a.owner).filter(Boolean))].sort(),
    [assets]
  );
  const assetWorkgroupOptions = useMemo(
    () => [...new Set(assets.flatMap(a => a.workgroups?.map(w => w.name) || []))].sort(),
    [assets]
  );

  if (loading) {
    return (
      <div className="d-flex justify-content-center">
        <div className="spinner-border" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-danger" role="alert">
          {error}
        </div>
      </div>
    );
  }

  return (
    <div
      className="container-fluid p-4 d-flex flex-column"
      style={showForm
        ? { minHeight: 0 }
        : { height: 'calc(100dvh - 9.5rem)', minHeight: 0, overflow: 'hidden' }}
    >
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>Asset Management</h2>
            <div className="btn-group" role="group">
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => {
                  if (showForm) {
                    resetForm();
                  } else {
                    setShowForm(true);
                  }
                }}
              >
                {showForm ? 'Cancel' : 'Add New Asset'}
              </button>
              {/* Export Vulnerabilities Button (ADMIN, VULN, SECCHAMPION roles) */}
              {hasVulnAccess() && (
                <>
                  <button
                    type="button"
                    className="btn btn-success"
                    onClick={handleExportVulnerabilities}
                    disabled={exportLoading}
                    title="Export all vulnerabilities to Excel"
                  >
                    {exportLoading ? (
                      <>
                        <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                        {exportProgress
                          ? `${exportProgress.progressPercent}%`
                          : 'Starting...'}
                      </>
                    ) : (
                      <>
                        <i className="bi bi-download me-2"></i>
                        Export Vulns
                      </>
                    )}
                  </button>
                  {exportLoading && (
                    <button
                      type="button"
                      className="btn btn-outline-danger"
                      onClick={handleCancelExport}
                      title="Cancel export"
                    >
                      <i className="bi bi-x-circle"></i>
                    </button>
                  )}
                </>
              )}
              {/* Feature 029: Bulk Delete Button (ADMIN only, hidden when no assets) */}
              {canManageGrants && matchingCount > 0 && (
                <button
                  type="button"
                  className="btn btn-danger"
                  onClick={() => setShowBulkDeleteModal(true)}
                  disabled={isDeletingBulk}
                >
                  <i className="bi bi-trash3-fill me-2"></i>
                  Delete All Assets
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
      {exportError && (
        <div className="row mb-3">
          <div className="col-12">
            <div className="alert alert-warning alert-dismissible fade show" role="alert">
              <i className="bi bi-exclamation-triangle-fill me-2"></i>
              {exportError}
              <button
                type="button"
                className="btn-close"
                onClick={() => setExportError(null)}
                aria-label="Close"
              ></button>
            </div>
          </div>
        </div>
      )}
      {showForm && (
        <div className="row mb-4">
          <div className="col-12">
            <div className="card">
              <div className="card-body">
                <h5 className="card-title">{editingAsset ? 'Edit Asset' : 'Add New Asset'}</h5>
                <form onSubmit={handleSubmit}>
                  <div className="mb-3">
                    <label htmlFor="name" className="form-label">Name *</label>
                    <input
                      type="text"
                      className="form-control"
                      id="name"
                      name="name"
                      value={formData.name}
                      onChange={handleInputChange}
                      required
                    />
                    {editingAsset?.crowdStrikeHostname && (
                      <div className="form-text">
                        CrowdStrike hostname: <code>{editingAsset.crowdStrikeHostname}</code>
                        {editingAsset.nameOverridden && (
                          <>
                            {' '}— this display name is protected from imports.
                            <button
                              type="button"
                              className="btn btn-link btn-sm p-0 ms-2 align-baseline"
                              onClick={handleResetName}
                            >
                              Restore CrowdStrike hostname
                            </button>
                          </>
                        )}
                      </div>
                    )}
                  </div>
                  <div className="mb-3">
                    <label htmlFor="type" className="form-label">Type *</label>
                    <select
                      className="form-control"
                      id="type"
                      name="type"
                      value={formData.type}
                      onChange={handleInputChange}
                      required
                    >
                      <option value="">Select Type</option>
                      <option value="Server">Server</option>
                      <option value="Workstation">Workstation</option>
                      <option value="Network Device">Network Device</option>
                      <option value="Mobile Device">Mobile Device</option>
                      <option value="IoT Device">IoT Device</option>
                      <option value="Database">Database</option>
                      <option value="Application">Application</option>
                      <option value="SaaS">SaaS</option>
                      <option value="URI">URI</option>
                      <option value="Other">Other</option>
                    </select>
                  </div>
                  <div className="mb-3">
                    <label htmlFor="ip" className="form-label">IP Address</label>
                    <input
                      type="text"
                      className="form-control"
                      id="ip"
                      name="ip"
                      value={formData.ip}
                      onChange={handleInputChange}
                      placeholder="e.g., 192.168.1.100"
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="uri" className="form-label">URI</label>
                    <input
                      type="text"
                      className="form-control"
                      id="uri"
                      name="uri"
                      value={formData.uri || ''}
                      onChange={handleInputChange}
                      placeholder="e.g., https://app.example.com or urn:asset:example"
                      maxLength={2048}
                    />
                    <small className="form-text text-muted">
                      Optional endpoint or application URI; supported schemes: http, https, and urn.
                    </small>
                  </div>
                  <div className="mb-3">
                    <label htmlFor="owner" className="form-label">Owner *</label>
                    {canAssignOwner && ownerCandidates.length > 0 ? (
                      <select
                        className="form-select"
                        id="owner"
                        name="owner"
                        value={formData.owner}
                        onChange={handleInputChange}
                        required
                      >
                        <option value="">Select Owner</option>
                        {/* Show current value if not in candidates list (legacy data) */}
                        {formData.owner && !ownerCandidates.some(c => c.value === formData.owner) && (
                          <option value={formData.owner} disabled>{formData.owner} (not in system)</option>
                        )}
                        {ownerCandidates.map(candidate => (
                          <option key={candidate.value} value={candidate.value}>{candidate.label}</option>
                        ))}
                      </select>
                    ) : (
                      <input
                        type="text"
                        className="form-control"
                        id="owner"
                        name="owner"
                        value={formData.owner}
                        onChange={handleInputChange}
                        placeholder="Person or team responsible"
                        required
                      />
                    )}
                  </div>
                  <div className="mb-3">
                    <label htmlFor="adDomain" className="form-label">
                      AD Domain <span className="text-muted">(optional)</span>
                    </label>
                    <input
                      type="text"
                      className={`form-control ${domainError ? 'is-invalid' : ''}`}
                      id="adDomain"
                      disabled={!canManageGrants}
                      name="adDomain"
                      value={formData.adDomain || ''}
                      onChange={(e) => {
                        handleInputChange(e);
                        validateDomain(e.target.value);
                      }}
                      placeholder="e.g., CONTOSO, corp.example.com"
                      maxLength={255}
                    />
                    {domainError && (
                      <div className="invalid-feedback">{domainError}</div>
                    )}
                    <small className="form-text text-muted">
                      Active Directory domain (alphanumeric, dots, and hyphens only)
                    </small>
                  </div>
                  <div className="mb-3">
                    <label htmlFor="description" className="form-label">Description</label>
                    <textarea
                      className="form-control"
                      id="description"
                      name="description"
                      value={formData.description}
                      onChange={handleInputChange}
                      placeholder="Description of the asset"
                      rows={3}
                    />
                  </div>
                  <div className="mb-3">
                    <label htmlFor="criticality" className="form-label">Criticality</label>
                    <select
                      className="form-select"
                      id="criticality"
                      name="criticality"
                      value={formData.criticality || ''}
                      onChange={handleCriticalityChange}
                    >
                      <option value="">Inherit from workgroup</option>
                      <option value="CRITICAL">🔴 CRITICAL</option>
                      <option value="HIGH">🟠 HIGH</option>
                      <option value="MEDIUM">🔵 MEDIUM</option>
                      <option value="LOW">⚪ LOW</option>
                      <option value="NA">➖ N/A</option>
                    </select>
                    <small className="text-muted">
                      Leave as "Inherit from workgroup" to use the highest criticality from assigned workgroups (excluding N/A). Default is MEDIUM if no workgroups assigned or all are N/A.
                    </small>
                  </div>
                  <div className="mb-3">
                    <label className="form-label">Workgroups</label>
                    <div>
                      {workgroups.length > 0 ? (
                        workgroups.map(workgroup => (
                          <div className="form-check" key={workgroup.id}>
                            <input
                              className="form-check-input"
                              type="checkbox"
                              id={`workgroup-${workgroup.id}`}
                              disabled={!!editingAsset && !canManageGrants}
                              value={workgroup.id}
                              checked={(formData.workgroupIds || []).includes(workgroup.id)}
                              onChange={handleWorkgroupChange}
                            />
                            <label className="form-check-label" htmlFor={`workgroup-${workgroup.id}`}>
                              {workgroup.name}
                            </label>
                          </div>
                        ))
                      ) : (
                        <small className="text-muted">No workgroups available</small>
                      )}
                    </div>
                  </div>
                  <div className="d-flex justify-content-end">
                    <button type="submit" className="btn btn-success me-2">
                      {editingAsset ? 'Update' : 'Save'}
                    </button>
                    <button type="button" onClick={resetForm} className="btn btn-secondary">
                      Cancel
                    </button>
                  </div>
                </form>
              </div>
            </div>
          </div>
        </div>
      )}
      {error && <div className="alert alert-danger" role="alert">{error}</div>}

      {/* Filters */}
      <div className="row mb-3 flex-shrink-0">
        <div className="col-12">
          <div className="card">
            <div className="card-body">
              <h6 className="card-title">Filters</h6>
              <div className="row">
                <div className="col-md-3">
                  <label htmlFor="nameFilter" className="form-label">Name</label>
                  <input
                    type="text"
                    id="nameFilter"
                    className="form-control"
                    placeholder="Filter by name..."
                    value={nameFilter}
                    onChange={(e) => { setNameFilter(e.target.value); setPage(0); }}
                  />
                </div>
                <div className="col-md-3">
                  <label htmlFor="ipFilter" className="form-label">IP Address</label>
                  <input
                    type="text"
                    id="ipFilter"
                    className="form-control"
                    placeholder="Filter by IP..."
                    value={ipFilter}
                    onChange={(e) => { setIpFilter(e.target.value); setPage(0); }}
                  />
                </div>
                <div className="col-md-3">
                  <label htmlFor="ownerFilter" className="form-label">Owner</label>
                  <input
                    type="text"
                    id="ownerFilter"
                    className="form-control"
                    placeholder="Filter by owner..."
                    value={ownerFilter}
                    onChange={(e) => { setOwnerFilter(e.target.value); setPage(0); }}
                  />
                </div>
                <div className="col-md-3">
                  <label htmlFor="adDomainFilter" className="form-label">AD Domain</label>
                  <select
                    id="adDomainFilter"
                    className="form-select"
                    value={adDomainFilter}
                    onChange={(e) => { setAdDomainFilter(e.target.value); setPage(0); }}
                  >
                    <option value="">All AD Domains</option>
                    {adDomainFilter && !adDomainOptions.includes(adDomainFilter) && (
                      <option value={adDomainFilter}>{adDomainFilter}</option>
                    )}
                    {adDomainOptions.map(domain => (
                      <option key={domain} value={domain}>{domain}</option>
                    ))}
                  </select>
                </div>
              </div>
              <div className="row mt-2">
                <div className="col-md-3">
                  <label htmlFor="workgroupFilter" className="form-label">Workgroups</label>
                  <select
                    id="workgroupFilter"
                    className="form-select"
                    value={workgroupFilter}
                    onChange={(e) => { setWorkgroupFilter(e.target.value); setPage(0); }}
                  >
                    <option value="">All Workgroups</option>
                    {workgroups.length > 0 ? (
                      workgroups.map(wg => (
                        <option key={wg.id} value={wg.id}>{wg.name}</option>
                      ))
                    ) : null}
                  </select>
                </div>
                <div className="col-md-3">
                  <label htmlFor="accountIdFilter" className="form-label">Account ID</label>
                  <input
                    type="text"
                    id="accountIdFilter"
                    className="form-control"
                    placeholder="Filter by Account ID..."
                    value={accountIdFilter}
                    onChange={(e) => { setAccountIdFilter(e.target.value); setPage(0); }}
                  />
                </div>
              </div>
              {(nameFilter || ipFilter || accountIdFilter || ownerFilter || adDomainFilter || workgroupFilter) && (
                <div className="mt-2">
                  <button
                    className="btn btn-sm btn-outline-secondary"
                    onClick={() => {
                      setNameFilter('');
                      setIpFilter('');
                      setAccountIdFilter('');
                      setOwnerFilter('');
                      setAdDomainFilter('');
                      setWorkgroupFilter('');
                      setPage(0);
                    }}
                  >
                    Clear All Filters
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="row flex-grow-1" style={{ minHeight: 0 }}>
        <div className="col-12 h-100">
          <div className="card h-100">
            <div className="card-body d-flex flex-column" style={{ minHeight: 0, overflow: 'hidden' }}>
              <div className="d-flex justify-content-between align-items-center flex-shrink-0 mb-2">
                <h5 className="card-title mb-0">
                  {accessibleCount === null
                    ? 'Assets (…)'
                    : loading && assets.length === 0
                      ? `${accessibleCount.toLocaleString()} accessible assets`
                      : `${matchingCount.toLocaleString()} matching of ${accessibleCount.toLocaleString()} accessible assets`}
                </h5>
                <label className="d-flex align-items-center gap-2">
                  Rows
                  <select className="form-select form-select-sm" value={pageSize} onChange={(event) => {
                    const nextSize = Number(event.target.value);
                    setPageSize(nextSize);
                    setPage(0);
                    window.localStorage.setItem('assetOverviewPageSize', String(nextSize));
                  }}>
                    {[25, 50, 100, 250].map(size => <option key={size} value={size}>{size}</option>)}
                  </select>
                </label>
                <label className="form-check-label ms-3">
                  <input className="form-check-input me-1" type="checkbox" checked={compactRows} onChange={(event) => {
                    setCompactRows(event.target.checked);
                    window.localStorage.setItem('assetOverviewCompactRows', String(event.target.checked));
                  }} />
                  Compact rows
                </label>
              </div>
              {loading && assets.length === 0 ? (
                <div aria-label="Loading assets">{Array.from({ length: 8 }, (_, index) => <div className="placeholder-glow py-2" key={index}><span className="placeholder col-12"></span></div>)}</div>
              ) : assets.length === 0 ? (
                <p className="text-muted">
                  {matchingCount === 0 && !nameFilter && !ipFilter && !accountIdFilter && !ownerFilter && !adDomainFilter && !workgroupFilter
                    ? 'No assets found. Click "Add New Asset" to create one.'
                    : 'No assets match the current filters.'}
                </p>
              ) : (
                <div className="table-responsive flex-grow-1" style={scrollContainerStyle}>
                  <table className={`table table-striped table-hover ${compactRows ? 'table-sm' : ''}`} style={{ borderCollapse: 'separate', borderSpacing: 0 }}>
                    <thead className="table-light">
                      <tr>
                        <th style={stickyHeaderCellStyle}>Name</th>
                        <th style={stickyHeaderCellStyle}>IP Addresses</th>
                        <th style={stickyHeaderCellStyle}>URI</th>
                        <th style={stickyHeaderCellStyle}>Instance ID</th>
                        <th style={stickyHeaderCellStyle}>Account ID</th>
                        <th style={stickyHeaderCellStyle}>AD Domain</th>
                        <th style={stickyHeaderCellStyle}>OS</th>
                        <th style={stickyHeaderCellStyle}>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {assets.map((asset) => (
                        <tr key={asset.id}>
                          <td>{asset.name}</td>
                          <td>
                            {(asset.ipAddresses?.length ? asset.ipAddresses : asset.ip ? [asset.ip] : []).length > 0
                              ? (asset.ipAddresses?.length ? asset.ipAddresses : [asset.ip!]).map(ip => (
                                  <code className="d-block" key={ip}>{ip}</code>
                                ))
                              : '-'}
                          </td>
                          <td>
                            {asset.uri ? (
                              asset.uri.toLowerCase().startsWith('http://') || asset.uri.toLowerCase().startsWith('https://') ? (
                                <a href={asset.uri} target="_blank" rel="noreferrer">
                                  {asset.uri}
                                </a>
                              ) : (
                                asset.uri
                              )
                            ) : (
                              '-'
                            )}
                          </td>
                          <td>{asset.cloudInstanceId || '-'}</td>
                          <td>{asset.cloudAccountId || '-'}</td>
                          <td>
                            {asset.adDomain ? (
                              <span className="badge bg-secondary" title="Active Directory Domain">
                                <i className="bi bi-building me-1"></i>{asset.adDomain}
                              </span>
                            ) : (
                              <span className="text-muted">-</span>
                            )}
                          </td>
                          <td>{asset.osVersion || '-'}</td>
                          <td>
                            <div className="btn-group" role="group">
                              <button
                                type="button"
                                onClick={() => handleEdit(asset)}
                                className="btn btn-sm btn-outline-primary"
                                title="Edit asset"
                              >
                                <i className="bi bi-pencil"></i> Edit
                              </button>
                              {asset.ip && (
                                <button
                                  type="button"
                                  onClick={() => handleShowPorts(asset)}
                                  className="btn btn-sm btn-outline-info"
                                  title="Show port history"
                                >
                                  <i className="bi bi-diagram-3"></i> Ports
                                </button>
                              )}
                              <a
                                href={`/vulnerabilities/system?hostname=${encodeURIComponent(asset.name)}`}
                                className="btn btn-sm btn-outline-danger"
                                title="Show vulnerabilities in CrowdStrike"
                              >
                                <i className="bi bi-shield-exclamation"></i> Vulns
                              </a>
                              {/* Delete button only visible to ADMIN users (Feature 033) */}
                              {canManageGrants && (
                                <button
                                  type="button"
                                  onClick={() => handleDelete(asset.id!)}
                                  className="btn btn-sm btn-outline-danger"
                                  title="Delete asset with all related data (cascade deletion)"
                                >
                                  <i className="bi bi-trash"></i> Delete
                                </button>
                              )}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <div className="d-flex justify-content-between align-items-center flex-shrink-0 pt-2">
                <span className="text-muted">
                  {matchingCount === 0 ? 'Showing 0 assets' : `Showing ${page * pageSize + 1}–${Math.min((page + 1) * pageSize, matchingCount)} of ${matchingCount.toLocaleString()}`}
                </span>
                <nav className="btn-group" aria-label="Asset pages">
                  <button className="btn btn-sm btn-outline-secondary" disabled={page === 0 || loading} onClick={() => setPage(value => value - 1)}>Previous</button>
                  <button className="btn btn-sm btn-outline-secondary" disabled={page + 1 >= totalPages || loading} onClick={() => setPage(value => value + 1)}>Next</button>
                </nav>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Port History Modal */}
      {showPortHistory && selectedAssetForPorts && selectedAssetForPorts.id && (
        <PortHistory
          assetId={selectedAssetForPorts.id}
          assetName={selectedAssetForPorts.name}
          onClose={handleClosePortHistory}
        />
      )}

      {/* Vulnerability History Modal */}
      {showVulnerabilities && selectedAssetForVulns && selectedAssetForVulns.id && (
        <VulnerabilityHistory
          assetId={selectedAssetForVulns.id}
          assetName={selectedAssetForVulns.name}
          onClose={handleCloseVulnerabilities}
        />
      )}

      {/* Bulk Delete Success Message (Feature 029) */}
      {bulkDeleteSuccess && (
        <div className="position-fixed top-0 start-50 translate-middle-x mt-3" style={{ zIndex: 9999 }}>
          <div className="alert alert-success alert-dismissible fade show" role="alert">
            <i className="bi bi-check-circle-fill me-2"></i>
            {bulkDeleteSuccess}
            <button
              type="button"
              className="btn-close"
              onClick={() => setBulkDeleteSuccess(null)}
              aria-label="Close"
            ></button>
          </div>
        </div>
      )}

      {/* Bulk Delete Confirmation Modal (Feature 029) */}
      <BulkDeleteConfirmModal
        isOpen={showBulkDeleteModal}
        onClose={() => setShowBulkDeleteModal(false)}
        onConfirm={handleBulkDelete}
        isDeleting={isDeletingBulk}
        assetCount={matchingCount}
      />

    </div>
  );
};

export default AssetManagement;
