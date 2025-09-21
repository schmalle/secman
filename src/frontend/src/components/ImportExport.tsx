import React, { useState, useCallback, useEffect, useRef } from 'react';
import { csrfPost } from '../utils/csrf'; // Import the CSRF-enhanced POST helper
import { authenticatedFetch } from '../utils/auth';

interface UseCase {
    id: number;
    name: string;
}

const ImportExport = () => {
    const [selectedFile, setSelectedFile] = useState<File | null>(null);
    const [uploadStatus, setUploadStatus] = useState<string>('');
    const [isUploading, setIsUploading] = useState<boolean>(false);
    const [isExporting, setIsExporting] = useState<boolean>(false); // New state for export loading
    const [exportStatus, setExportStatus] = useState<string>(''); // New state for export status messages
    const [useCases, setUseCases] = useState<UseCase[]>([]);
    const [selectedUseCase, setSelectedUseCase] = useState<number | null>(null);
    const [isLoadingUseCases, setIsLoadingUseCases] = useState<boolean>(false);
    const [selectedLanguage, setSelectedLanguage] = useState<string>('english');
    const [translationConfigured, setTranslationConfigured] = useState<boolean>(false);
    const [isDragOver, setIsDragOver] = useState<boolean>(false);
    const fileInputRef = useRef<HTMLInputElement>(null);

    // Fetch available use cases on component mount
    useEffect(() => {
        const fetchUseCases = async () => {
            setIsLoadingUseCases(true);
            try {
                const response = await authenticatedFetch('/api/usecases');
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

    // Check if translation is configured
    const checkTranslationConfiguration = async () => {
        try {
            const response = await authenticatedFetch('/api/translation-config/active');
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

    const validateFile = (file: File): boolean => {
        const validTypes = [
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            'application/vnd.ms-excel'
        ];
        const validExtensions = ['.xlsx', '.xls'];
        const fileExtension = file.name.toLowerCase().substring(file.name.lastIndexOf('.'));
        
        return validTypes.includes(file.type) || validExtensions.includes(fileExtension);
    };

    const handleFileSelection = (file: File) => {
        if (validateFile(file)) {
            setSelectedFile(file);
            setUploadStatus(''); // Clear previous status
        } else {
            setSelectedFile(null);
            setUploadStatus('Error: Please select a valid Excel file (.xlsx or .xls).');
        }
    };

    const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        if (event.target.files && event.target.files[0]) {
            handleFileSelection(event.target.files[0]);
        } else {
            setSelectedFile(null);
        }
    };

    const handleDragOver = (event: React.DragEvent<HTMLDivElement>) => {
        event.preventDefault();
        setIsDragOver(true);
    };

    const handleDragLeave = (event: React.DragEvent<HTMLDivElement>) => {
        event.preventDefault();
        setIsDragOver(false);
    };

    const handleDrop = (event: React.DragEvent<HTMLDivElement>) => {
        event.preventDefault();
        setIsDragOver(false);
        
        const files = event.dataTransfer.files;
        if (files.length > 0) {
            handleFileSelection(files[0]);
        }
    };

    const handleUploadAreaClick = () => {
        fileInputRef.current?.click();
    };

    const formatFileSize = (bytes: number): string => {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    const handleUpload = useCallback(async () => {
        if (!selectedFile) {
            setUploadStatus('Error: No file selected.');
            return;
        }

        setIsUploading(true);
        setUploadStatus('Uploading...');

        const formData = new FormData();
        formData.append('xlsxFile', selectedFile); // Match the backend expected key

        try {
            // Use the CSRF-enhanced POST method; let axios set multipart boundary
            const response = await csrfPost('/api/import/upload-xlsx', formData);

            setUploadStatus(`Success: ${response.data.message || 'File uploaded and processed.'} (${response.data.requirementsProcessed} requirements added)`);
            setSelectedFile(null); // Clear selection after successful upload
            // Clear the file input visually
            if (fileInputRef.current) {
                fileInputRef.current.value = '';
            }

        } catch (error: any) {
            console.error('Upload error:', error);
            let errorMessage = 'Upload failed. Please try again.';
            if (error.response && error.response.data && error.response.data.error) {
                // Use specific error from backend if available
                errorMessage = `Error: ${error.response.data.error}`;
            } else if (error.message) {
                errorMessage = `Error: ${error.message}`;
            }
            setUploadStatus(errorMessage);
        } finally {
            setIsUploading(false);
        }
    }, [selectedFile]);

    const handleExportToWord = useCallback(async () => {
        setIsExporting(true);
        
        // Determine the endpoint based on language selection
        const isTranslated = selectedLanguage !== 'english' && translationConfigured;
        const endpoint = isTranslated 
            ? `/api/requirements/export/docx/translated/${selectedLanguage}`
            : '/api/requirements/export/docx';
        
        setExportStatus(isTranslated ? `Translating and exporting to ${selectedLanguage}...` : 'Exporting to Word...');
        
        try {
            const response = await authenticatedFetch(endpoint, {
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
            
            // Get filename from response headers or generate default
            const contentDisposition = response.headers.get('Content-Disposition');
            let filename = 'requirements.docx';
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
        
        // Determine the endpoint based on language selection
        const isTranslated = selectedLanguage !== 'english' && translationConfigured;
        const endpoint = isTranslated 
            ? `/api/requirements/export/docx/usecase/${selectedUseCase}/translated/${selectedLanguage}`
            : `/api/requirements/export/docx/usecase/${selectedUseCase}`;
        
        const statusMsg = isTranslated 
            ? `Translating and exporting requirements for selected use case to ${selectedLanguage}...`
            : 'Exporting requirements for selected use case...';
        setExportStatus(statusMsg);
        
        try {
            const response = await authenticatedFetch(endpoint, {
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
            
            // Get the filename from Content-Disposition header if available
            const contentDisposition = response.headers.get('Content-Disposition');
            let filename = 'requirements_usecase.docx';
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
            const response = await authenticatedFetch('/api/requirements/export/xlsx', {
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
            
            // Get filename from response headers or generate default
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
            const response = await authenticatedFetch(`/api/requirements/export/xlsx/usecase/${selectedUseCase}`, {
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
            
            // Get the filename from Content-Disposition header if available
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
            
            setExportStatus('Requirements exported to Excel successfully for selected use case');
        } catch (error: any) {
            console.error('Excel export error:', error);
            setExportStatus(`Error: ${error.message || 'Could not export requirements to Excel.'}`);
        } finally {
            setIsExporting(false);
        }
    }, [selectedUseCase]);

    return (
        <div className="container-fluid mt-4">
            <div className="row g-4">
                {/* Import Section */}
                <div className="col-lg-6">
                    <div className="card h-100 shadow-sm border-0">
                        <div className="card-header bg-gradient" style={{background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)'}}>
                            <h3 className="card-title text-white mb-0">
                                <i className="bi bi-cloud-upload me-2"></i>
                                Import Requirements
                            </h3>
                            <p className="text-white-50 mb-0 mt-1">Upload Excel files to import requirements data</p>
                        </div>
                        <div className="card-body p-4">
                            {/* Step Indicator */}
                            <div className="mb-4">
                                <div className="d-flex align-items-center justify-content-between">
                                    <div className="d-flex align-items-center">
                                        <div className={`rounded-circle d-flex align-items-center justify-content-center ${selectedFile ? 'bg-success text-white' : 'bg-light border'}`} style={{width: '32px', height: '32px', fontSize: '14px'}}>
                                            {selectedFile ? <i className="bi bi-check"></i> : '1'}
                                        </div>
                                        <span className="ms-2 fw-medium">Select File</span>
                                    </div>
                                    <div className="flex-fill mx-3">
                                        <div className={`border-top ${selectedFile ? 'border-success' : 'border-secondary'}`} style={{height: '2px'}}></div>
                                    </div>
                                    <div className="d-flex align-items-center">
                                        <div className={`rounded-circle d-flex align-items-center justify-content-center ${uploadStatus.includes('Success') ? 'bg-success text-white' : 'bg-light border'}`} style={{width: '32px', height: '32px', fontSize: '14px'}}>
                                            {uploadStatus.includes('Success') ? <i className="bi bi-check"></i> : '2'}
                                        </div>
                                        <span className="ms-2 fw-medium">Upload & Process</span>
                                    </div>
                                </div>
                            </div>

                            {/* File Upload Area */}
                            <div 
                                className={`border-2 border-dashed rounded-3 p-4 text-center position-relative ${
                                    isDragOver ? 'border-primary bg-primary bg-opacity-10' : 
                                    selectedFile ? 'border-success bg-success bg-opacity-10' : 
                                    'border-secondary'
                                } ${isUploading ? 'pe-none' : 'cursor-pointer'}`}
                                onDragOver={handleDragOver}
                                onDragLeave={handleDragLeave}
                                onDrop={handleDrop}
                                onClick={handleUploadAreaClick}
                                style={{ minHeight: '180px', cursor: isUploading ? 'not-allowed' : 'pointer' }}
                            >
                                <input
                                    ref={fileInputRef}
                                    type="file"
                                    className="d-none"
                                    accept=".xlsx,.xls,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel"
                                    onChange={handleFileChange}
                                    disabled={isUploading}
                                />
                                
                                {selectedFile ? (
                                    <div className="d-flex flex-column align-items-center">
                                        <div className="mb-3">
                                            <i className="bi bi-file-earmark-excel text-success" style={{fontSize: '3rem'}}></i>
                                        </div>
                                        <h5 className="text-success mb-2">File Selected</h5>
                                        <div className="bg-white rounded-2 p-3 shadow-sm w-100">
                                            <div className="d-flex align-items-center justify-content-between">
                                                <div className="d-flex align-items-center">
                                                    <i className="bi bi-file-earmark-excel text-success me-2"></i>
                                                    <div>
                                                        <div className="fw-medium text-truncate" style={{maxWidth: '200px'}}>{selectedFile.name}</div>
                                                        <small className="text-muted">{formatFileSize(selectedFile.size)}</small>
                                                    </div>
                                                </div>
                                                <button 
                                                    className="btn btn-sm btn-outline-secondary"
                                                    onClick={(e) => {
                                                        e.stopPropagation();
                                                        setSelectedFile(null);
                                                        if (fileInputRef.current) fileInputRef.current.value = '';
                                                    }}
                                                    disabled={isUploading}
                                                >
                                                    <i className="bi bi-x"></i>
                                                </button>
                                            </div>
                                        </div>
                                        <p className="text-muted mt-2 mb-0">Click to change file or drag and drop a new one</p>
                                    </div>
                                ) : (
                                    <div className="d-flex flex-column align-items-center">
                                        <div className="mb-3">
                                            <i className={`bi ${isDragOver ? 'bi-cloud-upload-fill text-primary' : 'bi-cloud-upload text-muted'}`} style={{fontSize: '3rem'}}></i>
                                        </div>
                                        <h5 className={isDragOver ? 'text-primary' : 'text-dark'}>
                                            {isDragOver ? 'Drop your file here' : 'Choose Excel file or drag & drop'}
                                        </h5>
                                        <p className="text-muted mb-3">Supports .xlsx and .xls files up to 10MB</p>
                                        <button className="btn btn-outline-primary">
                                            <i className="bi bi-folder2-open me-2"></i>Browse Files
                                        </button>
                                    </div>
                                )}
                            </div>

                            {/* File Requirements */}
                            <div className="mt-4">
                                <h6 className="fw-semibold mb-3">
                                    <i className="bi bi-info-circle me-2 text-info"></i>File Requirements
                                </h6>
                                <div className="row g-3">
                                    <div className="col-md-6">
                                        <div className="bg-light rounded-2 p-3">
                                            <h6 className="mb-2 text-primary">
                                                <i className="bi bi-table me-1"></i>Sheet Name
                                            </h6>
                                            <code className="text-dark">Reqs</code>
                                        </div>
                                    </div>
                                    <div className="col-md-6">
                                        <div className="bg-light rounded-2 p-3">
                                            <h6 className="mb-2 text-primary">
                                                <i className="bi bi-columns me-1"></i>Required Columns
                                            </h6>
                                            <div className="small">
                                                <div><code>Chapter</code></div>
                                                <div><code>Norm</code></div>
                                                <div><code>Short req</code></div>
                                                <div><code>DetailsEN</code></div>
                                                <div><code>MotivationEN</code></div>
                                                <div><code>ExampleEN</code></div>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            {/* Upload Button */}
                            <div className="mt-4 d-grid">
                                <button
                                    className="btn btn-primary btn-lg"
                                    onClick={handleUpload}
                                    disabled={!selectedFile || isUploading}
                                >
                                    {isUploading ? (
                                        <>
                                            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                            Processing File...
                                        </>
                                    ) : (
                                        <>
                                            <i className="bi bi-cloud-upload-fill me-2"></i>
                                            Upload and Process Requirements
                                        </>
                                    )}
                                </button>
                            </div>

                            {/* Status Messages */}
                            {uploadStatus && (
                                <div className={`alert mt-3 ${uploadStatus.startsWith('Error:') ? 'alert-danger' : 'alert-success'}`} role="alert">
                                    <div className="d-flex align-items-center">
                                        <i className={`bi ${uploadStatus.startsWith('Error:') ? 'bi-exclamation-triangle' : 'bi-check-circle'} me-2`}></i>
                                        {uploadStatus}
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Export Section */}
                <div className="col-lg-6">
                    <div className="card h-100 shadow-sm border-0">
                        <div className="card-header bg-gradient" style={{background: 'linear-gradient(135deg, #11998e 0%, #38ef7d 100%)'}}>
                            <h3 className="card-title text-white mb-0">
                                <i className="bi bi-download me-2"></i>
                                Export Requirements
                            </h3>
                            <p className="text-white-50 mb-0 mt-1">Generate and download requirement documents</p>
                        </div>
                        <div className="card-body p-4">
                            {/* Language Selection */}
                            <div className="mb-4">
                                <h5 className="fw-semibold mb-3">
                                    <i className="bi bi-translate me-2 text-primary"></i>Export Language
                                </h5>
                                <div className="bg-light rounded-3 p-3">
                                    <select
                                        id="languageSelect"
                                        className="form-select form-select-lg"
                                        value={selectedLanguage}
                                        onChange={(e) => setSelectedLanguage(e.target.value)}
                                        disabled={isExporting}
                                    >
                                        <option value="english">🇺🇸 English (Original)</option>
                                        <option value="german" disabled={!translationConfigured}>
                                            🇩🇪 German {!translationConfigured ? '(Translation not configured)' : ''}
                                        </option>
                                    </select>
                                    {!translationConfigured && (
                                        <div className="mt-2 p-2 bg-warning bg-opacity-10 rounded-2">
                                            <div className="d-flex align-items-center text-warning">
                                                <i className="bi bi-exclamation-triangle me-2"></i>
                                                <small>
                                                    Translation requires configuration. 
                                                    <a href="/admin/translation-config" className="text-decoration-none fw-medium"> Configure now</a>
                                                </small>
                                            </div>
                                        </div>
                                    )}
                                </div>
                            </div>
                            
                            {/* Export Options */}
                            <div className="row g-3">
                                {/* Export All */}
                                <div className="col-12">
                                    <div className="card border border-success border-opacity-25 bg-success bg-opacity-5">
                                        <div className="card-body p-3">
                                            <div className="d-flex align-items-center justify-content-between">
                                                <div>
                                                    <h6 className="card-title mb-1">
                                                        <i className="bi bi-file-earmark-word text-success me-2"></i>
                                                        Export All Requirements
                                                    </h6>
                                                    <p className="card-text text-muted small mb-0">
                                                        Download all requirements in a single Word document
                                                    </p>
                                                </div>
                                                <button 
                                                    className="btn btn-success"
                                                    onClick={handleExportToWord} 
                                                    disabled={isExporting}
                                                >
                                                    {isExporting ? (
                                                        <>
                                                            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                                            Exporting...
                                                        </>
                                                    ) : (
                                                        <>
                                                            <i className="bi bi-download me-2"></i>Export All
                                                        </>
                                                    )}
                                                </button>
                                            </div>
                                        </div>
                                    </div>
                                </div>

                                {/* Export by Use Case */}
                                <div className="col-12">
                                    <div className="card border border-primary border-opacity-25 bg-primary bg-opacity-5">
                                        <div className="card-body p-3">
                                            <h6 className="card-title mb-3">
                                                <i className="bi bi-funnel text-primary me-2"></i>
                                                Export by Use Case
                                            </h6>
                                            <div className="mb-3">
                                                <select
                                                    id="useCaseSelect"
                                                    className="form-select"
                                                    value={selectedUseCase || ''}
                                                    onChange={(e) => setSelectedUseCase(e.target.value ? parseInt(e.target.value) : null)}
                                                    disabled={isLoadingUseCases || isExporting}
                                                >
                                                    <option value="">
                                                        {isLoadingUseCases ? 'Loading use cases...' : 'Select a use case'}
                                                    </option>
                                                    {useCases.map((useCase) => (
                                                        <option key={useCase.id} value={useCase.id}>
                                                            {useCase.name}
                                                        </option>
                                                    ))}
                                                </select>
                                            </div>
                                            <button
                                                className="btn btn-primary w-100"
                                                onClick={handleExportByUseCase}
                                                disabled={!selectedUseCase || isExporting}
                                            >
                                                {isExporting ? (
                                                    <>
                                                        <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                                        Exporting...
                                                    </>
                                                ) : (
                                                    <>
                                                        <i className="bi bi-download me-2"></i>Export Filtered Requirements
                                                    </>
                                                )}
                                            </button>
                                        </div>
                                    </div>
                                </div>

                                {/* Excel Export Section */}
                                <div className="col-12 mt-4">
                                    <div className="border-top pt-4">
                                        <h6 className="fw-semibold mb-3">
                                            <i className="bi bi-file-earmark-excel text-success me-2"></i>Excel Export
                                        </h6>
                                        <div className="row g-2">
                                            {/* Export All to Excel */}
                                            <div className="col-md-6">
                                                <div className="card border border-success border-opacity-25 bg-success bg-opacity-5">
                                                    <div className="card-body p-3">
                                                        <div className="text-center">
                                                            <h6 className="card-title mb-1">
                                                                <i className="bi bi-file-earmark-excel text-success me-2"></i>
                                                                All Requirements
                                                            </h6>
                                                            <p className="card-text text-muted small mb-2">
                                                                Export in Excel format matching import structure
                                                            </p>
                                                            <button 
                                                                className="btn btn-outline-success btn-sm"
                                                                onClick={handleExportToExcel} 
                                                                disabled={isExporting}
                                                            >
                                                                {isExporting ? (
                                                                    <>
                                                                        <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                                                        Exporting...
                                                                    </>
                                                                ) : (
                                                                    <>
                                                                        <i className="bi bi-download me-2"></i>Export Excel
                                                                    </>
                                                                )}
                                                            </button>
                                                        </div>
                                                    </div>
                                                </div>
                                            </div>

                                            {/* Export by Use Case to Excel */}
                                            <div className="col-md-6">
                                                <div className="card border border-primary border-opacity-25 bg-primary bg-opacity-5">
                                                    <div className="card-body p-3">
                                                        <div className="text-center">
                                                            <h6 className="card-title mb-1">
                                                                <i className="bi bi-funnel text-primary me-2"></i>
                                                                By Use Case
                                                            </h6>
                                                            <p className="card-text text-muted small mb-2">
                                                                Export filtered requirements to Excel
                                                            </p>
                                                            <button
                                                                className="btn btn-outline-primary btn-sm"
                                                                onClick={handleExportToExcelByUseCase}
                                                                disabled={!selectedUseCase || isExporting}
                                                            >
                                                                {isExporting ? (
                                                                    <>
                                                                        <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                                                        Exporting...
                                                                    </>
                                                                ) : (
                                                                    <>
                                                                        <i className="bi bi-download me-2"></i>Export Excel
                                                                    </>
                                                                )}
                                                            </button>
                                                        </div>
                                                    </div>
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            {/* Export Status */}
                            {exportStatus && (
                                <div className={`alert mt-4 ${exportStatus.startsWith('Error:') ? 'alert-danger' : 'alert-success'}`} role="alert">
                                    <div className="d-flex align-items-center">
                                        <i className={`bi ${exportStatus.startsWith('Error:') ? 'bi-exclamation-triangle' : 'bi-check-circle'} me-2`}></i>
                                        {exportStatus}
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default ImportExport;
