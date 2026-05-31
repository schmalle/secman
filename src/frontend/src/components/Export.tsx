import React, { useState, useCallback, useEffect } from 'react';
import { authenticatedFetch } from '../utils/auth';
import ReleaseSelector from './ReleaseSelector';
import { exportApplications, exportAssets } from '../services/assetService';

interface UseCase {
    id: number;
    name: string;
}

interface RequirementExportTemplate {
    id: number;
    name: string;
    versionLabel?: string | null;
    uploadedBy: string;
    createdAt: string;
    lastUsedAt?: string | null;
    sha256: string;
}

type WordTemplateMode = 'LATEST' | 'SAVED' | 'ADHOC' | 'NONE';

const Export = () => {
    const [isExporting, setIsExporting] = useState<boolean>(false);
    const [exportStatus, setExportStatus] = useState<string>('');
    const [useCases, setUseCases] = useState<UseCase[]>([]);
    const [selectedUseCase, setSelectedUseCase] = useState<number | null>(null);
    const [isLoadingUseCases, setIsLoadingUseCases] = useState<boolean>(false);
    const [selectedLanguage, setSelectedLanguage] = useState<string>('english');
    const [translationConfigured, setTranslationConfigured] = useState<boolean>(false);
    const [selectedReleaseId, setSelectedReleaseId] = useState<number | null>(() => {
        if (typeof window === 'undefined') {
            return null;
        }
        const stored = sessionStorage.getItem('secman_selectedReleaseId');
        if (!stored) {
            return null;
        }
        const parsed = parseInt(stored, 10);
        return isNaN(parsed) ? null : parsed;
    });
    const [wordTemplateMode, setWordTemplateMode] = useState<WordTemplateMode>('LATEST');
    const [savedTemplates, setSavedTemplates] = useState<RequirementExportTemplate[]>([]);
    const [selectedTemplateId, setSelectedTemplateId] = useState<number | null>(null);
    const [adHocTemplateFile, setAdHocTemplateFile] = useState<File | null>(null);
    const [saveAdHocAsTemplate, setSaveAdHocAsTemplate] = useState<boolean>(false);
    const [templateName, setTemplateName] = useState<string>('');
    const [templateVersionLabel, setTemplateVersionLabel] = useState<string>('');
    const [templateDescription, setTemplateDescription] = useState<string>('');
    const [missingPlaceholderBehavior, setMissingPlaceholderBehavior] = useState<'REJECT' | 'APPEND'>('REJECT');
    const [classification, setClassification] = useState<string>('Internal');


    useEffect(() => {
        const fetchUseCases = async () => {
            setIsLoadingUseCases(true);
            try {
                const response = await authenticatedFetch('/api/usecases');
                if (response.ok) {
                    const data = await response.json();
                    setUseCases(data);
                } else if (response.status !== 401 && response.status !== 403) {
                    // 401/403 = user lacks permission to manage use cases (RBAC); ignore silently.
                    console.error('Failed to fetch use cases');
                }
            } catch (error) {
                console.error('Error fetching use cases:', error);
            } finally {
                setIsLoadingUseCases(false);
            }
        };

        const checkTranslationConfigurationInEffect = async () => {
            try {
                const response = await authenticatedFetch('/api/translation-config/active');
                setTranslationConfigured(response.ok);
            } catch (error) {
                console.error('Error checking translation configuration:', error);
                setTranslationConfigured(false);
            }
        };

        const fetchRequirementExportTemplatesInEffect = async () => {
            try {
                const response = await authenticatedFetch('/api/requirement-export-templates');
                if (response.ok) {
                    const templates = await response.json();
                    setSavedTemplates(templates);
                    if (templates.length > 0) {
                        setSelectedTemplateId(templates[0].id);
                    }
                }
            } catch (error) {
                console.error('Error fetching Word export templates:', error);
            }
        };

        fetchUseCases();
        checkTranslationConfigurationInEffect();
        fetchRequirementExportTemplatesInEffect();
    }, []);


    const appendTemplateParams = (endpoint: string): string => {
        const separator = endpoint.includes('?') ? '&' : '?';
        const params = new URLSearchParams({
            templateMode: wordTemplateMode,
            missingPlaceholderBehavior,
            classification,
        });
        if (wordTemplateMode === 'SAVED' && selectedTemplateId) {
            params.set('templateId', selectedTemplateId.toString());
        }
        return `${endpoint}${separator}${params.toString()}`;
    };

    const downloadBlobResponse = async (response: Response, defaultFilename: string) => {
        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        const contentDisposition = response.headers.get('Content-Disposition');
        let filename = defaultFilename;
        if (contentDisposition) {
            const filenameMatch = contentDisposition.match(/filename="?([^"]+)"?/);
            if (filenameMatch) {
                filename = filenameMatch[1];
            }
        }
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.URL.revokeObjectURL(url);
    };

    const saveReusableTemplateIfRequested = async () => {
        if (wordTemplateMode !== 'ADHOC' || !saveAdHocAsTemplate || !adHocTemplateFile) {
            return;
        }

        const formData = new FormData();
        formData.append('templateFile', adHocTemplateFile);
        formData.append('name', templateName.trim() || adHocTemplateFile.name.replace(/\.docx$/i, ''));
        formData.append('description', templateDescription);
        formData.append('versionLabel', templateVersionLabel);
        formData.append('activate', 'true');
        formData.append('requireRequirementsPlaceholder', (missingPlaceholderBehavior === 'REJECT').toString());

        const response = await fetch('/api/requirement-export-templates', {
            method: 'POST',
            body: formData,
            credentials: 'include',
        });

        if (!response.ok) {
            let errorMsg = 'Could not save the reusable Word template.';
            try {
                const errorData = await response.json();
                errorMsg = errorData.error || errorData.errors?.join('; ') || errorMsg;
            } catch (e) {
                errorMsg = response.statusText || errorMsg;
            }
            throw new Error(errorMsg);
        }

        const savedTemplate = await response.json();
        setSavedTemplates((current) => [savedTemplate, ...current.filter((template) => template.id !== savedTemplate.id)]);
        setSelectedTemplateId(savedTemplate.id);
    };

    const requestWordExport = async (endpoint: string): Promise<Response> => {
        if (wordTemplateMode === 'ADHOC') {
            if (!adHocTemplateFile) {
                throw new Error('Please select an ad hoc Word template file first.');
            }
            await saveReusableTemplateIfRequested();
            const formData = new FormData();
            formData.append('templateFile', adHocTemplateFile);
            formData.append('templateMode', 'ADHOC');
            formData.append('missingPlaceholderBehavior', missingPlaceholderBehavior);
            formData.append('classification', classification);
            return fetch(endpoint, {
                method: 'POST',
                body: formData,
                credentials: 'include',
            });
        }

        return authenticatedFetch(appendTemplateParams(endpoint), {
            method: 'GET',
        });
    };


    const handleExportToWord = useCallback(async () => {
        setIsExporting(true);

        const isTranslated = selectedLanguage !== 'english' && translationConfigured;
        let endpoint = isTranslated
            ? `/api/requirements/export/docx/translated/${selectedLanguage}`
            : '/api/requirements/export/docx';

        // Add releaseId parameter if a release is selected
        if (selectedReleaseId !== null) {
            endpoint += `?releaseId=${selectedReleaseId}`;
        }

        if (isTranslated && wordTemplateMode === 'ADHOC') {
            setExportStatus('Error: Ad hoc templates are not supported for translated exports yet. Please use a saved/latest template or English export.');
            setIsExporting(false);
            return;
        }

        setExportStatus(isTranslated ? `Translating and exporting to ${selectedLanguage}...` : 'Exporting to Word...');
        
        try {
            const response = await requestWordExport(endpoint);

            if (!response.ok) {
                let errorMsg = 'Failed to export requirements.';
                try {
                    const errorData = await response.json();
                    errorMsg = errorData.error || errorMsg;
                } catch (e) {
                    errorMsg = response.statusText || errorMsg;
                }
                throw new Error(errorMsg);
            }

            await downloadBlobResponse(response, 'requirements.docx');
            
            const successMsg = isTranslated 
                ? `Requirements exported and translated to ${selectedLanguage} successfully`
                : 'Requirements exported successfully';
            setExportStatus(successMsg);
        } catch (error: any) {
            console.error('Export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export requirements.'}`);
        } finally {
            setIsExporting(false);
        }
    }, [selectedLanguage, translationConfigured, selectedReleaseId, wordTemplateMode, selectedTemplateId, adHocTemplateFile, saveAdHocAsTemplate, templateName, templateVersionLabel, templateDescription, missingPlaceholderBehavior, classification]);

    const handleExportByUseCase = useCallback(async () => {
        if (!selectedUseCase) {
            setExportStatus('Error: Please select a use case first.');
            return;
        }

        setIsExporting(true);
        
        const isTranslated = selectedLanguage !== 'english' && translationConfigured;
        let endpoint = isTranslated
            ? `/api/requirements/export/docx/usecase/${selectedUseCase}/translated/${selectedLanguage}`
            : `/api/requirements/export/docx/usecase/${selectedUseCase}`;

        // Add releaseId parameter if a release is selected
        if (selectedReleaseId !== null) {
            endpoint += `?releaseId=${selectedReleaseId}`;
        }
        
        if (isTranslated && wordTemplateMode === 'ADHOC') {
            setExportStatus('Error: Ad hoc templates are not supported for translated exports yet. Please use a saved/latest template or English export.');
            setIsExporting(false);
            return;
        }

        const statusMsg = isTranslated 
            ? `Translating and exporting requirements for selected use case to ${selectedLanguage}...`
            : 'Exporting requirements for selected use case...';
        setExportStatus(statusMsg);
        
        try {
            const response = await requestWordExport(endpoint);

            if (!response.ok) {
                let errorMsg = 'Failed to export requirements.';
                try {
                    const errorData = await response.json();
                    errorMsg = errorData.error || errorMsg;
                } catch (e) {
                    errorMsg = response.statusText || errorMsg;
                }
                throw new Error(errorMsg);
            }

            await downloadBlobResponse(response, 'requirements_usecase.docx');
            
            const successMsg = isTranslated 
                ? `Requirements exported and translated to ${selectedLanguage} successfully for selected use case`
                : 'Requirements exported successfully for selected use case';
            setExportStatus(successMsg);
        } catch (error: any) {
            console.error('Export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export requirements.'}`);
        } finally {
            setIsExporting(false);
        }
    }, [selectedUseCase, selectedLanguage, translationConfigured, selectedReleaseId, wordTemplateMode, selectedTemplateId, adHocTemplateFile, saveAdHocAsTemplate, templateName, templateVersionLabel, templateDescription, missingPlaceholderBehavior, classification]);

    const handleExportToExcel = useCallback(async () => {
        setIsExporting(true);

        const isTranslated = selectedLanguage !== 'english' && translationConfigured;
        let endpoint = isTranslated
            ? `/api/requirements/export/xlsx/translated/${selectedLanguage}`
            : '/api/requirements/export/xlsx';

        // Add releaseId parameter if a release is selected
        if (selectedReleaseId !== null) {
            endpoint += `?releaseId=${selectedReleaseId}`;
        }

        setExportStatus(isTranslated ? `Translating and exporting to Excel in ${selectedLanguage}...` : 'Exporting to Excel...');
        
        try {
            const response = await authenticatedFetch(endpoint, {
                method: 'GET',
            });

            if (!response.ok) {
                let errorMsg = 'Failed to export requirements to Excel.';
                try {
                    const errorData = await response.json();
                    errorMsg = errorData.error || errorMsg;
                } catch (e) {
                    errorMsg = response.statusText || errorMsg;
                }
                throw new Error(errorMsg);
            }

            const blob = await response.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            
            const contentDisposition = response.headers.get('Content-Disposition');
            let filename = 'requirements_export.xlsx';
            if (contentDisposition) {
                const filenameMatch = contentDisposition.match(/filename="?([^"]+)"?/);
                if (filenameMatch) {
                    filename = filenameMatch[1];
                }
            }
            
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            a.remove();
            window.URL.revokeObjectURL(url);
            
            const successMsg = isTranslated 
                ? `Requirements exported and translated to ${selectedLanguage} in Excel successfully`
                : 'Requirements exported to Excel successfully';
            setExportStatus(successMsg);
        } catch (error: any) {
            console.error('Excel export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export requirements to Excel.'}`);
        } finally {
            setIsExporting(false);
        }
    }, [selectedLanguage, translationConfigured, selectedReleaseId]);

    const handleExportToExcelByUseCase = useCallback(async () => {
        if (!selectedUseCase) {
            setExportStatus('Error: Please select a use case first.');
            return;
        }

        setIsExporting(true);
        
        const isTranslated = selectedLanguage !== 'english' && translationConfigured;
        let endpoint = isTranslated
            ? `/api/requirements/export/xlsx/usecase/${selectedUseCase}/translated/${selectedLanguage}`
            : `/api/requirements/export/xlsx/usecase/${selectedUseCase}`;

        // Add releaseId parameter if a release is selected
        if (selectedReleaseId !== null) {
            endpoint += `?releaseId=${selectedReleaseId}`;
        }
        
        const statusMsg = isTranslated 
            ? `Translating and exporting requirements for selected use case to Excel in ${selectedLanguage}...`
            : 'Exporting requirements for selected use case to Excel...';
        setExportStatus(statusMsg);
        
        try {
            const response = await authenticatedFetch(endpoint, {
                method: 'GET',
            });

            if (!response.ok) {
                let errorMsg = 'Failed to export requirements to Excel.';
                try {
                    const errorData = await response.json();
                    errorMsg = errorData.error || errorMsg;
                } catch (e) {
                    errorMsg = response.statusText || errorMsg;
                }
                throw new Error(errorMsg);
            }

            const blob = await response.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            
            const contentDisposition = response.headers.get('Content-Disposition');
            let filename = 'requirements_usecase.xlsx';
            if (contentDisposition) {
                const filenameMatch = contentDisposition.match(/filename="?([^"]+)"?/);
                if (filenameMatch) {
                    filename = filenameMatch[1];
                }
            }
            
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            a.remove();
            window.URL.revokeObjectURL(url);
            
            const successMsg = isTranslated 
                ? `Requirements exported and translated to ${selectedLanguage} in Excel successfully for selected use case`
                : 'Requirements exported to Excel successfully for selected use case';
            setExportStatus(successMsg);
        } catch (error: any) {
            console.error('Excel export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export requirements to Excel.'}`);
        } finally {
            setIsExporting(false);
        }
    }, [selectedUseCase, selectedLanguage, translationConfigured, selectedReleaseId]);

    /**
     * Handle asset export to Excel
     * Feature 029: User Story 2 - Export Assets to File
     */
    const handleExportAssets = useCallback(async () => {
        setIsExporting(true);
        setExportStatus('Exporting assets...');

        try {
            const blob = await exportAssets();

            // Generate filename with current date
            const dateStr = new Date().toISOString().split('T')[0];
            const filename = `assets_export_${dateStr}.xlsx`;

            // Trigger download
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.style.display = 'none';
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            a.remove();
            window.URL.revokeObjectURL(url);

            setExportStatus('Assets exported to Excel successfully');
        } catch (error: any) {
            console.error('Asset export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export assets'}`);
        } finally {
            setIsExporting(false);
        }
    }, []);

    const handleExportApplications = useCallback(async () => {
        setIsExporting(true);
        setExportStatus('Exporting applications...');

        try {
            const blob = await exportApplications();
            const dateStr = new Date().toISOString().split('T')[0];
            const filename = `applications_export_${dateStr}.xlsx`;

            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.style.display = 'none';
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            a.remove();
            window.URL.revokeObjectURL(url);

            setExportStatus('Applications exported to Excel successfully');
        } catch (error: any) {
            console.error('Application export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export applications'}`);
        } finally {
            setIsExporting(false);
        }
    }, []);

    return (
        <div className="container-fluid p-3">
            <div className="row">
                {/* Release Version Selection */}
                <div className="col-md-3 mb-3">
                    <div className="card h-100 border-0 shadow-sm">
                        <div className="card-body p-3">
                            <h6 className="card-title mb-2">
                                <i className="bi bi-tag me-2 text-primary"></i>Release Version
                            </h6>
                            <ReleaseSelector
                                onReleaseChange={setSelectedReleaseId}
                                selectedReleaseId={selectedReleaseId}
                                className="release-selector-compact"
                            />
                        </div>
                    </div>
                </div>

                {/* Language Selection */}
                <div className="col-md-3 mb-3">
                    <div className="card h-100 border-0 shadow-sm">
                        <div className="card-body p-3">
                            <h6 className="card-title mb-2">
                                <i className="bi bi-translate me-2 text-primary"></i>Export Language
                            </h6>
                            <select
                                className="form-select form-select-sm"
                                value={selectedLanguage}
                                onChange={(e) => setSelectedLanguage(e.target.value)}
                                disabled={isExporting}
                            >
                                <option value="english">🇺🇸 English (Original)</option>
                                <option value="german" disabled={!translationConfigured}>
                                    🇩🇪 German {!translationConfigured ? '(Not configured)' : ''}
                                </option>
                            </select>
                            {!translationConfigured && (
                                <div className="mt-2 p-2 bg-warning bg-opacity-10 rounded-2">
                                    <small className="text-warning">
                                        <i className="bi bi-exclamation-triangle me-1"></i>
                                        <a href="/admin/translation-config" className="text-decoration-none">Configure translation</a>
                                    </small>
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Use Case Selection */}
                <div className="col-md-3 mb-3">
                    <div className="card h-100 border-0 shadow-sm">
                        <div className="card-body p-3">
                            <h6 className="card-title mb-2">
                                <i className="bi bi-funnel me-2 text-primary"></i>Filter by Use Case
                            </h6>
                            <select
                                className="form-select form-select-sm"
                                value={selectedUseCase || ''}
                                onChange={(e) => setSelectedUseCase(e.target.value ? parseInt(e.target.value) : null)}
                                disabled={isLoadingUseCases || isExporting}
                            >
                                <option value="">All Use Cases</option>
                                {useCases.map((useCase) => (
                                    <option key={useCase.id} value={useCase.id}>
                                        {useCase.name}
                                    </option>
                                ))}
                            </select>
                        </div>
                    </div>
                </div>

                {/* Status */}
                <div className="col-md-3 mb-3">
                    {exportStatus && (
                        <div className={`alert mb-0 ${exportStatus.startsWith('Error:') ? 'alert-danger' : 'alert-success'}`} role="alert">
                            <small className="d-flex align-items-center">
                                <i className={`bi ${exportStatus.startsWith('Error:') ? 'bi-exclamation-triangle' : 'bi-check-circle'} me-2`}></i>
                                {exportStatus}
                            </small>
                        </div>
                    )}
                </div>
            </div>

            {/* Word Template Options */}
            <div className="card border-secondary border-opacity-25 mb-3" data-testid="word-template-options">
                <div className="card-body">
                    <div className="d-flex align-items-center justify-content-between mb-3">
                        <div>
                            <h6 className="mb-1"><i className="bi bi-file-earmark-word me-2"></i>Word template</h6>
                            <small className="text-muted">
                                Use the latest saved Secman template by default, choose a saved template, upload an ad hoc template for one export, or use the built-in layout.
                            </small>
                        </div>
                        {savedTemplates.length > 0 && (
                            <span className="badge bg-success-subtle text-success">
                                Latest: {savedTemplates[0].name}
                            </span>
                        )}
                    </div>

                    <div className="row g-3">
                        <div className="col-lg-4">
                            <label className="form-label small fw-semibold" htmlFor="word-template-mode">Template mode</label>
                            <select
                                id="word-template-mode"
                                data-testid="word-template-mode"
                                className="form-select form-select-sm"
                                value={wordTemplateMode}
                                onChange={(event) => setWordTemplateMode(event.target.value as WordTemplateMode)}
                            >
                                <option value="LATEST">Latest saved template</option>
                                <option value="SAVED">Specific saved template</option>
                                <option value="ADHOC">Ad hoc upload for this export</option>
                                <option value="NONE">No template / Secman default</option>
                            </select>
                        </div>

                        <div className="col-lg-4">
                            <label className="form-label small fw-semibold" htmlFor="saved-word-template">Saved template</label>
                            <select
                                id="saved-word-template"
                                data-testid="saved-word-template"
                                className="form-select form-select-sm"
                                value={selectedTemplateId ?? ''}
                                onChange={(event) => setSelectedTemplateId(event.target.value ? parseInt(event.target.value, 10) : null)}
                                disabled={wordTemplateMode !== 'SAVED' || savedTemplates.length === 0}
                            >
                                {savedTemplates.length === 0 ? (
                                    <option value="">No saved templates</option>
                                ) : savedTemplates.map((template) => (
                                    <option key={template.id} value={template.id}>
                                        {template.name}{template.versionLabel ? ` (${template.versionLabel})` : ''}
                                    </option>
                                ))}
                            </select>
                        </div>

                        <div className="col-lg-4">
                            <label className="form-label small fw-semibold" htmlFor="adhoc-word-template">Ad hoc template file</label>
                            <input
                                id="adhoc-word-template"
                                data-testid="adhoc-word-template"
                                className="form-control form-control-sm"
                                type="file"
                                accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                disabled={wordTemplateMode !== 'ADHOC'}
                                onChange={(event) => setAdHocTemplateFile(event.target.files?.[0] ?? null)}
                            />
                        </div>

                        <div className="col-lg-4">
                            <div className="form-check mt-4">
                                <input
                                    id="save-adhoc-template"
                                    data-testid="save-adhoc-template"
                                    className="form-check-input"
                                    type="checkbox"
                                    checked={saveAdHocAsTemplate}
                                    disabled={wordTemplateMode !== 'ADHOC'}
                                    onChange={(event) => setSaveAdHocAsTemplate(event.target.checked)}
                                />
                                <label className="form-check-label small" htmlFor="save-adhoc-template">
                                    Save ad hoc file as latest reusable template
                                </label>
                            </div>
                        </div>

                        {wordTemplateMode === 'ADHOC' && saveAdHocAsTemplate && (
                            <>
                                <div className="col-lg-4">
                                    <label className="form-label small fw-semibold" htmlFor="template-name">Template name</label>
                                    <input
                                        id="template-name"
                                        data-testid="template-name"
                                        className="form-control form-control-sm"
                                        type="text"
                                        value={templateName}
                                        onChange={(event) => setTemplateName(event.target.value)}
                                        placeholder={adHocTemplateFile?.name.replace(/\.docx$/i, '') || 'Corporate requirement template'}
                                    />
                                </div>
                                <div className="col-lg-4">
                                    <label className="form-label small fw-semibold" htmlFor="template-version-label">Version label</label>
                                    <input
                                        id="template-version-label"
                                        data-testid="template-version-label"
                                        className="form-control form-control-sm"
                                        type="text"
                                        value={templateVersionLabel}
                                        onChange={(event) => setTemplateVersionLabel(event.target.value)}
                                        placeholder="Corporate 2026-Q2"
                                    />
                                </div>
                                <div className="col-lg-4">
                                    <label className="form-label small fw-semibold" htmlFor="template-description">Description</label>
                                    <input
                                        id="template-description"
                                        data-testid="template-description"
                                        className="form-control form-control-sm"
                                        type="text"
                                        value={templateDescription}
                                        onChange={(event) => setTemplateDescription(event.target.value)}
                                        placeholder="Approved audience or project context"
                                    />
                                </div>
                            </>
                        )}

                        <div className="col-lg-4">
                            <label className="form-label small fw-semibold" htmlFor="missing-placeholder-behavior">Missing placeholder behavior</label>
                            <select
                                id="missing-placeholder-behavior"
                                data-testid="missing-placeholder-behavior"
                                className="form-select form-select-sm"
                                value={missingPlaceholderBehavior}
                                onChange={(event) => setMissingPlaceholderBehavior(event.target.value as 'REJECT' | 'APPEND')}
                            >
                                <option value="REJECT">{'Reject template without ${requirements}'}</option>
                                <option value="APPEND">Append requirements after template body</option>
                            </select>
                        </div>

                        <div className="col-lg-4">
                            <label className="form-label small fw-semibold" htmlFor="document-classification">Classification</label>
                            <input
                                id="document-classification"
                                data-testid="document-classification"
                                className="form-control form-control-sm"
                                type="text"
                                value={classification}
                                onChange={(event) => setClassification(event.target.value)}
                                placeholder="Internal"
                            />
                        </div>

                        <div className="col-lg-4">
                            <label className="form-label small fw-semibold">Preview summary</label>
                            <div className="form-control form-control-sm bg-light" data-testid="word-template-preview">
                                {wordTemplateMode === 'LATEST' && (savedTemplates[0] ? `Latest active: ${savedTemplates[0].name} (${savedTemplates[0].sha256.slice(0, 8)})` : 'No saved template; Secman default will be used')}
                                {wordTemplateMode === 'SAVED' && (savedTemplates.find((template) => template.id === selectedTemplateId)?.name || 'Select a saved template')}
                                {wordTemplateMode === 'ADHOC' && (adHocTemplateFile ? `Ad hoc: ${adHocTemplateFile.name}` : 'Choose a .docx file for this export')}
                                {wordTemplateMode === 'NONE' && 'Built-in Secman Word layout'}
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {/* Export Options - Compact Grid */}
            <div className="row g-3">
                {/* Word - All Requirements */}
                <div className="col-md-3 col-sm-6">
                    <div className="card border-success border-opacity-25 bg-success bg-opacity-5 h-100">
                        <div className="card-body p-3 text-center">
                            <i className="bi bi-file-earmark-word text-success mb-2" style={{fontSize: '2rem'}}></i>
                            <h6 className="card-title mb-2">Word - All</h6>
                            <button 
                                className="btn btn-success btn-sm w-100"
                                onClick={handleExportToWord} 
                                disabled={isExporting}
                            >
                                {isExporting ? (
                                    <span className="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
                                ) : (
                                    <>
                                        <i className="bi bi-download me-1"></i>Export
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                </div>

                {/* Word - Filtered */}
                <div className="col-md-3 col-sm-6">
                    <div className="card border-primary border-opacity-25 bg-primary bg-opacity-5 h-100">
                        <div className="card-body p-3 text-center">
                            <i className="bi bi-file-earmark-word text-primary mb-2" style={{fontSize: '2rem'}}></i>
                            <h6 className="card-title mb-2">Word - Filtered</h6>
                            <button
                                className="btn btn-primary btn-sm w-100"
                                onClick={handleExportByUseCase}
                                disabled={isExporting}
                            >
                                {isExporting ? (
                                    <span className="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
                                ) : (
                                    <>
                                        <i className="bi bi-download me-1"></i>Export
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                </div>

                {/* Excel - All Requirements */}
                <div className="col-md-3 col-sm-6">
                    <div className="card border-success border-opacity-25 bg-success bg-opacity-5 h-100">
                        <div className="card-body p-3 text-center">
                            <i className="bi bi-file-earmark-excel text-success mb-2" style={{fontSize: '2rem'}}></i>
                            <h6 className="card-title mb-2">Excel - All</h6>
                            <button 
                                className="btn btn-outline-success btn-sm w-100"
                                onClick={handleExportToExcel} 
                                disabled={isExporting}
                            >
                                {isExporting ? (
                                    <span className="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
                                ) : (
                                    <>
                                        <i className="bi bi-download me-1"></i>Export
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                </div>

                {/* Excel - Filtered */}
                <div className="col-md-3 col-sm-6">
                    <div className="card border-primary border-opacity-25 bg-primary bg-opacity-5 h-100">
                        <div className="card-body p-3 text-center">
                            <i className="bi bi-file-earmark-excel text-primary mb-2" style={{fontSize: '2rem'}}></i>
                            <h6 className="card-title mb-2">Excel - Filtered</h6>
                            <button
                                className="btn btn-outline-primary btn-sm w-100"
                                onClick={handleExportToExcelByUseCase}
                                disabled={isExporting}
                            >
                                {isExporting ? (
                                    <span className="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
                                ) : (
                                    <>
                                        <i className="bi bi-download me-1"></i>Export
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                </div>

                {/* Assets - Excel (Feature 029) */}
                <div className="col-md-3 col-sm-6">
                    <div className="card border-warning border-opacity-25 bg-warning bg-opacity-5 h-100">
                        <div className="card-body p-3 text-center">
                            <i className="bi bi-hdd-rack text-warning mb-2" style={{fontSize: '2rem'}}></i>
                            <h6 className="card-title mb-2">Assets - All</h6>
                            <button
                                className="btn btn-warning btn-sm w-100"
                                onClick={handleExportAssets}
                                disabled={isExporting}
                            >
                                {isExporting ? (
                                    <span className="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
                                ) : (
                                    <>
                                        <i className="bi bi-download me-1"></i>Export
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                </div>

                {/* Applications - Excel */}
                <div className="col-md-3 col-sm-6">
                    <div className="card border-info border-opacity-25 bg-info bg-opacity-5 h-100">
                        <div className="card-body p-3 text-center">
                            <i className="bi bi-window-stack text-info mb-2" style={{fontSize: '2rem'}}></i>
                            <h6 className="card-title mb-2">Applications - All</h6>
                            <button
                                className="btn btn-info btn-sm w-100"
                                onClick={handleExportApplications}
                                disabled={isExporting}
                            >
                                {isExporting ? (
                                    <span className="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
                                ) : (
                                    <>
                                        <i className="bi bi-download me-1"></i>Export
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default Export;
