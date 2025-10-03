import React, { useState, useRef } from 'react';
import { csrfPost } from '../utils/csrf';
import { authenticatedPost } from '../utils/auth';

type ImportType = 'requirements' | 'nmap';

interface ScanSummary {
    scanId: number;
    filename: string;
    scanDate: string;
    hostsDiscovered: number;
    assetsCreated: number;
    assetsUpdated: number;
    totalPorts: number;
    duration: string;
}

const Import = () => {
    const [importType, setImportType] = useState<ImportType>('requirements');
    const [selectedFile, setSelectedFile] = useState<File | null>(null);
    const [uploadStatus, setUploadStatus] = useState<string>('');
    const [isUploading, setIsUploading] = useState<boolean>(false);
    const [isDragOver, setIsDragOver] = useState<boolean>(false);
    const [scanSummary, setScanSummary] = useState<ScanSummary | null>(null);
    const fileInputRef = useRef<HTMLInputElement>(null);

    const validateFile = (file: File): boolean => {
        if (importType === 'requirements') {
            const validTypes = [
                'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                'application/vnd.ms-excel'
            ];
            const validExtensions = ['.xlsx', '.xls'];
            const fileExtension = file.name.toLowerCase().substring(file.name.lastIndexOf('.'));

            return validTypes.includes(file.type) || validExtensions.includes(fileExtension);
        } else if (importType === 'nmap') {
            const validTypes = ['text/xml', 'application/xml'];
            const fileExtension = file.name.toLowerCase().substring(file.name.lastIndexOf('.'));

            return validTypes.includes(file.type) || fileExtension === '.xml';
        }

        return false;
    };

    const handleFileSelection = (file: File) => {
        if (validateFile(file)) {
            setSelectedFile(file);
            setUploadStatus('');
            setScanSummary(null);
        } else {
            setSelectedFile(null);
            const fileTypeMsg = importType === 'requirements'
                ? 'Excel file (.xlsx or .xls)'
                : 'nmap XML file (.xml)';
            setUploadStatus(`Error: Please select a valid ${fileTypeMsg}.`);
        }
    };

    const handleImportTypeChange = (type: ImportType) => {
        setImportType(type);
        setSelectedFile(null);
        setUploadStatus('');
        setScanSummary(null);
        if (fileInputRef.current) {
            fileInputRef.current.value = '';
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

    const handleUploadRequirements = async () => {
        if (!selectedFile) return;

        const formData = new FormData();
        formData.append('xlsxFile', selectedFile);

        try {
            const response = await csrfPost('/api/import/upload-xlsx', formData);
            setUploadStatus(`Success: ${response.data.message || 'File uploaded and processed.'} (${response.data.requirementsProcessed} requirements added)`);
            setSelectedFile(null);
            if (fileInputRef.current) {
                fileInputRef.current.value = '';
            }
        } catch (error: any) {
            console.error('Upload error:', error);
            let errorMessage = 'Upload failed. Please try again.';
            if (error.response && error.response.data && error.response.data.error) {
                errorMessage = `Error: ${error.response.data.error}`;
            } else if (error.message) {
                errorMessage = `Error: ${error.message}`;
            }
            setUploadStatus(errorMessage);
        }
    };

    const handleUploadNmap = async () => {
        if (!selectedFile) return;

        const formData = new FormData();
        formData.append('file', selectedFile);

        try {
            const response = await authenticatedPost('/api/scan/upload-nmap', formData);

            if (response.ok) {
                const data: ScanSummary = await response.json();
                setScanSummary(data);
                setUploadStatus(`Success: Scan imported successfully! ${data.hostsDiscovered} hosts discovered.`);
                setSelectedFile(null);
                if (fileInputRef.current) {
                    fileInputRef.current.value = '';
                }
            } else if (response.status === 403) {
                setUploadStatus('Error: Access denied. Nmap import requires administrator privileges.');
            } else {
                const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
                setUploadStatus(`Error: ${errorData.error || 'Upload failed'}`);
            }
        } catch (error: any) {
            console.error('Upload error:', error);
            setUploadStatus(`Error: ${error.message || 'Upload failed. Please try again.'}`);
        }
    };

    const handleUpload = async () => {
        if (!selectedFile) {
            setUploadStatus('Error: No file selected.');
            return;
        }

        setIsUploading(true);
        setUploadStatus('Uploading...');
        setScanSummary(null);

        try {
            if (importType === 'requirements') {
                await handleUploadRequirements();
            } else if (importType === 'nmap') {
                await handleUploadNmap();
            }
        } finally {
            setIsUploading(false);
        }
    };

    return (
        <div className="container-fluid mt-4">
            <div className="row justify-content-center">
                <div className="col-lg-8">
                    <div className="card shadow-sm border-0">
                        <div className="card-header bg-gradient text-white" style={{background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)'}}>
                            <h3 className="card-title mb-0">
                                <i className="bi bi-cloud-upload me-2"></i>
                                Import Data
                            </h3>
                            <p className="mb-0 mt-1 opacity-75">Upload files to import data into the system</p>
                        </div>
                        <div className="card-body p-0">
                            {/* Import Type Tabs */}
                            <ul className="nav nav-tabs px-4 pt-3" role="tablist">
                                <li className="nav-item" role="presentation">
                                    <button
                                        className={`nav-link ${importType === 'requirements' ? 'active' : ''}`}
                                        type="button"
                                        onClick={() => handleImportTypeChange('requirements')}
                                    >
                                        <i className="bi bi-file-earmark-excel me-2"></i>
                                        Requirements
                                    </button>
                                </li>
                                <li className="nav-item" role="presentation">
                                    <button
                                        className={`nav-link ${importType === 'nmap' ? 'active' : ''}`}
                                        type="button"
                                        onClick={() => handleImportTypeChange('nmap')}
                                    >
                                        <i className="bi bi-diagram-3 me-2"></i>
                                        Nmap Scan
                                        <span className="badge bg-warning text-dark ms-2">ADMIN</span>
                                    </button>
                                </li>
                            </ul>

                            <div className="p-5">
                            {/* File Upload Area */}
                            <div 
                                className={`border-2 border-dashed rounded-3 p-5 text-center position-relative ${
                                    isDragOver ? 'border-primary bg-primary bg-opacity-10' : 
                                    selectedFile ? 'border-success bg-success bg-opacity-10' : 
                                    'border-secondary'
                                } ${isUploading ? 'pe-none' : 'cursor-pointer'}`}
                                onDragOver={handleDragOver}
                                onDragLeave={handleDragLeave}
                                onDrop={handleDrop}
                                onClick={handleUploadAreaClick}
                                style={{ minHeight: '200px', cursor: isUploading ? 'not-allowed' : 'pointer' }}
                            >
                                <input
                                    ref={fileInputRef}
                                    type="file"
                                    className="d-none"
                                    accept={importType === 'requirements'
                                        ? ".xlsx,.xls,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel"
                                        : ".xml,text/xml,application/xml"}
                                    onChange={handleFileChange}
                                    disabled={isUploading}
                                />
                                
                                {selectedFile ? (
                                    <div className="d-flex flex-column align-items-center">
                                        <div className="mb-3">
                                            <i className="bi bi-file-earmark-excel text-success" style={{fontSize: '4rem'}}></i>
                                        </div>
                                        <h4 className="text-success mb-3">File Selected</h4>
                                        <div className="bg-white rounded-3 p-4 shadow-sm w-100 max-width-400">
                                            <div className="d-flex align-items-center justify-content-between">
                                                <div className="d-flex align-items-center">
                                                    <i className="bi bi-file-earmark-excel text-success me-3"></i>
                                                    <div>
                                                        <div className="fw-semibold text-truncate" style={{maxWidth: '250px'}}>{selectedFile.name}</div>
                                                        <small className="text-muted">{formatFileSize(selectedFile.size)}</small>
                                                    </div>
                                                </div>
                                                <button 
                                                    className="btn btn-outline-secondary"
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
                                        <p className="text-muted mt-3 mb-0">Click to change file or drag and drop a new one</p>
                                    </div>
                                ) : (
                                    <div className="d-flex flex-column align-items-center">
                                        <div className="mb-4">
                                            <i className={`bi ${isDragOver ? 'bi-cloud-upload-fill text-primary' : 'bi-cloud-upload text-muted'}`} style={{fontSize: '4rem'}}></i>
                                        </div>
                                        <h4 className={isDragOver ? 'text-primary' : 'text-dark'}>
                                            {isDragOver ? 'Drop your file here' :
                                                importType === 'requirements' ? 'Choose Excel file or drag & drop' :
                                                'Choose nmap XML file or drag & drop'}
                                        </h4>
                                        <p className="text-muted mb-4">
                                            {importType === 'requirements'
                                                ? 'Supports .xlsx and .xls files up to 10MB'
                                                : 'Supports .xml files up to 10MB'}
                                        </p>
                                        <button className="btn btn-primary btn-lg">
                                            <i className="bi bi-folder2-open me-2"></i>Browse Files
                                        </button>
                                    </div>
                                )}
                            </div>

                            {/* File Requirements */}
                            {importType === 'requirements' && (
                                <div className="mt-4">
                                    <div className="bg-light rounded-3 p-3">
                                        <div className="row align-items-center">
                                            <div className="col-md-3">
                                                <h6 className="mb-0 text-primary">
                                                    <i className="bi bi-info-circle me-2"></i>File Requirements
                                                </h6>
                                            </div>
                                            <div className="col-md-2">
                                                <div className="text-muted small">Sheet Name:</div>
                                                <code className="fs-6 text-dark">Reqs</code>
                                            </div>
                                            <div className="col-md-7">
                                                <div className="text-muted small">Required Columns:</div>
                                                <div className="d-flex flex-wrap gap-1">
                                                    <code className="badge bg-secondary">Chapter</code>
                                                    <code className="badge bg-secondary">Norm</code>
                                                    <code className="badge bg-secondary">Short req</code>
                                                    <code className="badge bg-secondary">DetailsEN</code>
                                                    <code className="badge bg-secondary">MotivationEN</code>
                                                    <code className="badge bg-secondary">ExampleEN</code>
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            )}

                            {/* Scan Summary (for nmap) */}
                            {importType === 'nmap' && scanSummary && (
                                <div className="mt-4">
                                    <div className="card border-success">
                                        <div className="card-header bg-success text-white">
                                            <h6 className="mb-0">
                                                <i className="bi bi-check-circle me-2"></i>
                                                Scan Summary
                                            </h6>
                                        </div>
                                        <div className="card-body">
                                            <div className="row">
                                                <div className="col-md-6">
                                                    <p className="mb-2"><strong>Scan ID:</strong> {scanSummary.scanId}</p>
                                                    <p className="mb-2"><strong>Filename:</strong> {scanSummary.filename}</p>
                                                    <p className="mb-2"><strong>Scan Date:</strong> {new Date(scanSummary.scanDate).toLocaleString()}</p>
                                                </div>
                                                <div className="col-md-6">
                                                    <p className="mb-2"><strong>Hosts Discovered:</strong> {scanSummary.hostsDiscovered}</p>
                                                    <p className="mb-2"><strong>Assets Created:</strong> <span className="badge bg-success">{scanSummary.assetsCreated}</span></p>
                                                    <p className="mb-2"><strong>Assets Updated:</strong> <span className="badge bg-info">{scanSummary.assetsUpdated}</span></p>
                                                    <p className="mb-2"><strong>Total Ports:</strong> {scanSummary.totalPorts}</p>
                                                    <p className="mb-2"><strong>Duration:</strong> {scanSummary.duration}</p>
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            )}

                            {/* Upload Button */}
                            <div className="mt-5 d-grid">
                                <button
                                    className="btn btn-primary btn-lg py-3"
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
                                            {importType === 'requirements'
                                                ? 'Upload and Process Requirements'
                                                : 'Upload and Import Scan'}
                                        </>
                                    )}
                                </button>
                            </div>

                            {/* Status Messages */}
                            {uploadStatus && (
                                <div className={`alert mt-4 ${uploadStatus.startsWith('Error:') ? 'alert-danger' : 'alert-success'}`} role="alert">
                                    <div className="d-flex align-items-center">
                                        <i className={`bi ${uploadStatus.startsWith('Error:') ? 'bi-exclamation-triangle' : 'bi-check-circle'} me-2`}></i>
                                        {uploadStatus}
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            </div>
            </div>
        </div>
    );
};

export default Import;