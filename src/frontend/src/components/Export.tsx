import React, { useState, useCallback, useEffect } from 'react';

interface UseCase {
    id: number;
    name: string;
}

const Export = () => {
    const [isExporting, setIsExporting] = useState<boolean>(false);
    const [exportStatus, setExportStatus] = useState<string>('');
    const [useCases, setUseCases] = useState<UseCase[]>([]);
    const [selectedUseCase, setSelectedUseCase] = useState<number | null>(null);
    const [isLoadingUseCases, setIsLoadingUseCases] = useState<boolean>(false);
    const [selectedLanguage, setSelectedLanguage] = useState<string>('english');
    const [translationConfigured, setTranslationConfigured] = useState<boolean>(false);

    useEffect(() => {
        const fetchUseCases = async () => {
            setIsLoadingUseCases(true);
            try {
                const response = await fetch('/api/usecases');
                if (response.ok) {
                    const data = await response.json();
                    setUseCases(data);
                } else {
                    console.error('Failed to fetch use cases');
                }
            } catch (error) {
                console.error('Error fetching use cases:', error);
            } finally {
                setIsLoadingUseCases(false);
            }
        };

        fetchUseCases();
        checkTranslationConfiguration();
    }, []);

    const checkTranslationConfiguration = async () => {
        try {
            const response = await fetch('/api/translation-config/active');
            if (response.ok) {
                setTranslationConfigured(true);
            } else {
                setTranslationConfigured(false);
            }
        } catch (error) {
            console.error('Error checking translation configuration:', error);
            setTranslationConfigured(false);
        }
    };

    const handleExportToWord = useCallback(async () => {
        setIsExporting(true);
        
        const isTranslated = selectedLanguage !== 'english' && translationConfigured;
        const endpoint = isTranslated 
            ? `/api/requirements/export/docx/translated/${selectedLanguage}`
            : '/api/requirements/export/docx';
        
        setExportStatus(isTranslated ? `Translating and exporting to ${selectedLanguage}...` : 'Exporting to Word...');
        
        try {
            const response = await fetch(endpoint, {
                method: 'GET',
            });

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

            const blob = await response.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            
            const contentDisposition = response.headers.get('Content-Disposition');
            let filename = 'requirements.docx';
            if (contentDisposition) {
                const filenameMatch = contentDisposition.match(/filename=(.+)/);
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
                ? `Requirements exported and translated to ${selectedLanguage} successfully`
                : 'Requirements exported successfully';
            setExportStatus(successMsg);
        } catch (error: any) {
            console.error('Export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export requirements.'}`);
        } finally {
            setIsExporting(false);
        }
    }, [selectedLanguage, translationConfigured]);

    const handleExportByUseCase = useCallback(async () => {
        if (!selectedUseCase) {
            setExportStatus('Error: Please select a use case first.');
            return;
        }

        setIsExporting(true);
        
        const isTranslated = selectedLanguage !== 'english' && translationConfigured;
        const endpoint = isTranslated 
            ? `/api/requirements/export/docx/usecase/${selectedUseCase}/translated/${selectedLanguage}`
            : `/api/requirements/export/docx/usecase/${selectedUseCase}`;
        
        const statusMsg = isTranslated 
            ? `Translating and exporting requirements for selected use case to ${selectedLanguage}...`
            : 'Exporting requirements for selected use case...';
        setExportStatus(statusMsg);
        
        try {
            const response = await fetch(endpoint, {
                method: 'GET',
            });

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

            const blob = await response.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            
            const contentDisposition = response.headers.get('Content-Disposition');
            let filename = 'requirements_usecase.docx';
            if (contentDisposition) {
                const filenameMatch = contentDisposition.match(/filename=(.+)/);
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
                ? `Requirements exported and translated to ${selectedLanguage} successfully for selected use case`
                : 'Requirements exported successfully for selected use case';
            setExportStatus(successMsg);
        } catch (error: any) {
            console.error('Export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export requirements.'}`);
        } finally {
            setIsExporting(false);
        }
    }, [selectedUseCase, selectedLanguage, translationConfigured]);

    const handleExportToExcel = useCallback(async () => {
        setIsExporting(true);
        setExportStatus('Exporting to Excel...');
        
        try {
            const response = await fetch('/api/requirements/export/xlsx', {
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
                const filenameMatch = contentDisposition.match(/filename=(.+)/);
                if (filenameMatch) {
                    filename = filenameMatch[1];
                }
            }
            
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            a.remove();
            window.URL.revokeObjectURL(url);
            
            setExportStatus('Requirements exported to Excel successfully');
        } catch (error: any) {
            console.error('Excel export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export requirements to Excel.'}`);
        } finally {
            setIsExporting(false);
        }
    }, []);

    const handleExportToExcelByUseCase = useCallback(async () => {
        if (!selectedUseCase) {
            setExportStatus('Error: Please select a use case first.');
            return;
        }

        setIsExporting(true);
        setExportStatus('Exporting requirements for selected use case to Excel...');
        
        try {
            const response = await fetch(`/api/requirements/export/xlsx/usecase/${selectedUseCase}`, {
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
                const filenameMatch = contentDisposition.match(/filename=(.+)/);
                if (filenameMatch) {
                    filename = filenameMatch[1];
                }
            }
            
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            a.remove();
            window.URL.revokeObjectURL(url);
            
            setExportStatus('Requirements exported to Excel successfully for selected use case');
        } catch (error: any) {
            console.error('Excel export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export requirements to Excel.'}`);
        } finally {
            setIsExporting(false);
        }
    }, [selectedUseCase]);

    return (
        <div className="container-fluid p-3">
            <div className="row">
                {/* Language Selection - Top Left */}
                <div className="col-md-4 mb-3">
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

                {/* Use Case Selection - Top Right */}
                <div className="col-md-4 mb-3">
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

                {/* Status - Top Right */}
                <div className="col-md-4 mb-3">
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
                                disabled={!selectedUseCase || isExporting}
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
                                disabled={!selectedUseCase || isExporting}
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