import React, { useState, useRef, useEffect } from 'react';
import { csrfPost, csrfDelete } from '../utils/csrf';
import { authenticatedFetch } from '../utils/auth';

interface RequirementFile {
    id: number;
    filename: string;
    size: number;
    contentType: string;
    uploadedBy: string;
    createdAt: string;
}

interface RequirementFileUploadProps {
    riskAssessmentId: number;
    requirementId: number;
    requirementTitle: string;
    onFileUploaded?: () => void;
}

const RequirementFileUpload: React.FC<RequirementFileUploadProps> = ({
    riskAssessmentId,
    requirementId,
    requirementTitle,
    onFileUploaded
}) => {
    const [files, setFiles] = useState<RequirementFile[]>([]);
    const [loading, setLoading] = useState(false);
    const [uploading, setUploading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [isDragOver, setIsDragOver] = useState(false);
    const fileInputRef = useRef<HTMLInputElement>(null);

    const allowedTypes = [
        'application/pdf',
        'application/msword',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.ms-excel',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'image/jpeg',
        'image/png',
        'image/gif',
        'text/plain',
        'text/csv'
    ];

    useEffect(() => {
        fetchFiles();
    }, [riskAssessmentId, requirementId]);

    const fetchFiles = async () => {
        setLoading(true);
        try {
            const response = await authenticatedFetch(
                `/api/risk-assessments/${riskAssessmentId}/requirements/${requirementId}/files`,
                { credentials: 'include' as RequestCredentials }
            );
            if (response.ok) {
                const data = await response.json();
                setFiles(data);
            }
        } catch (err) {
            console.error('Error fetching files:', err);
        } finally {
            setLoading(false);
        }
    };

    const validateFile = (file: File): string | null => {
        if (file.size > 50 * 1024 * 1024) {
            return 'File size exceeds 50MB limit';
        }
        if (!allowedTypes.includes(file.type)) {
            return 'File type not allowed. Please use PDF, Word, Excel, images, or text files.';
        }
        return null;
    };

    const formatFileSize = (bytes: number): string => {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    const handleFileUpload = async (file: File) => {
        const validationError = validateFile(file);
        if (validationError) {
            setError(validationError);
            return;
        }

        setUploading(true);
        setError(null);

        const formData = new FormData();
        formData.append('file', file);

        try {
            const response = await csrfPost(
                `/api/risk-assessments/${riskAssessmentId}/requirements/${requirementId}/files`,
                formData
            );

            await fetchFiles();
            onFileUploaded?.();
            
            if (fileInputRef.current) {
                fileInputRef.current.value = '';
            }
        } catch (err: any) {
            console.error('Upload error:', err);
            setError(err.response?.data?.error || 'Upload failed');
        } finally {
            setUploading(false);
        }
    };

    const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        if (file) {
            handleFileUpload(file);
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
        
        const file = event.dataTransfer.files[0];
        if (file) {
            handleFileUpload(file);
        }
    };

    const handleDownload = async (fileId: number, filename: string) => {
        try {
            const response = await authenticatedFetch(`/api/files/${fileId}/download`, {
                credentials: 'include'
            } as RequestInit);
            
            if (response.ok) {
                const blob = await response.blob();
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = filename;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                window.URL.revokeObjectURL(url);
            } else {
                setError('Failed to download file');
            }
        } catch (err) {
            console.error('Download error:', err);
            setError('Download failed');
        }
    };

    const handleDelete = async (fileId: number) => {
        if (!confirm('Are you sure you want to delete this file?')) {
            return;
        }

        try {
            await csrfDelete(`/api/files/${fileId}`, {});
            await fetchFiles();
            onFileUploaded?.();
        } catch (err: any) {
            console.error('Delete error:', err);
            setError(err.response?.data?.error || 'Delete failed');
        }
    };

    const getFileIcon = (contentType: string) => {
        if (contentType.includes('pdf')) return 'bi-file-earmark-pdf-fill text-danger';
        if (contentType.includes('word')) return 'bi-file-earmark-word-fill text-primary';
        if (contentType.includes('excel') || contentType.includes('spreadsheet')) return 'bi-file-earmark-excel-fill text-success';
        if (contentType.includes('image')) return 'bi-file-earmark-image-fill text-info';
        if (contentType.includes('text')) return 'bi-file-earmark-text-fill text-secondary';
        return 'bi-file-earmark-fill text-dark';
    };

    return (
        <div className="requirement-file-upload mt-3">
            <div className="card border-0 shadow-sm">
                <div className="card-header bg-light py-2">
                    <h6 className="mb-0 text-muted">
                        <i className="bi bi-paperclip me-2"></i>
                        Files for: {requirementTitle}
                    </h6>
                </div>
                <div className="card-body p-3">
                    {/* Upload Area */}
                    <div 
                        className={`border-2 border-dashed rounded-2 p-3 text-center mb-3 ${
                            isDragOver ? 'border-primary bg-primary bg-opacity-10' : 'border-secondary'
                        } ${uploading ? 'pe-none' : 'cursor-pointer'}`}
                        onDragOver={handleDragOver}
                        onDragLeave={handleDragLeave}
                        onDrop={handleDrop}
                        onClick={() => fileInputRef.current?.click()}
                        style={{ cursor: uploading ? 'not-allowed' : 'pointer' }}
                    >
                        <input
                            ref={fileInputRef}
                            type="file"
                            className="d-none"
                            accept=".pdf,.doc,.docx,.xls,.xlsx,.jpg,.jpeg,.png,.gif,.txt,.csv"
                            onChange={handleFileSelect}
                            disabled={uploading}
                        />
                        
                        {uploading ? (
                            <div className="d-flex flex-column align-items-center">
                                <div className="spinner-border text-primary mb-2" role="status">
                                    <span className="visually-hidden">Uploading...</span>
                                </div>
                                <small className="text-muted">Uploading file...</small>
                            </div>
                        ) : (
                            <div className="d-flex flex-column align-items-center">
                                <i className={`bi ${isDragOver ? 'bi-cloud-upload-fill text-primary' : 'bi-cloud-upload text-muted'} mb-2`} 
                                   style={{fontSize: '2rem'}}></i>
                                <small className={isDragOver ? 'text-primary fw-medium' : 'text-muted'}>
                                    {isDragOver ? 'Drop file here' : 'Click to upload or drag & drop'}
                                </small>
                                <small className="text-muted">
                                    PDF, Word, Excel, Images, Text (max 50MB)
                                </small>
                            </div>
                        )}
                    </div>

                    {/* Error Message */}
                    {error && (
                        <div className="alert alert-danger alert-sm py-2 mb-3" role="alert">
                            <i className="bi bi-exclamation-triangle me-2"></i>
                            {error}
                        </div>
                    )}

                    {/* Files List */}
                    {loading ? (
                        <div className="text-center py-3">
                            <div className="spinner-border spinner-border-sm text-primary" role="status">
                                <span className="visually-hidden">Loading...</span>
                            </div>
                            <small className="text-muted ms-2">Loading files...</small>
                        </div>
                    ) : files.length > 0 ? (
                        <div className="list-group list-group-flush">
                            {files.map((file) => (
                                <div key={file.id} className="list-group-item px-0 py-2 border-0 border-bottom">
                                    <div className="d-flex align-items-center justify-content-between">
                                        <div className="d-flex align-items-center flex-grow-1">
                                            <i className={`${getFileIcon(file.contentType)} me-3`} style={{fontSize: '1.5rem'}}></i>
                                            <div className="flex-grow-1">
                                                <div className="fw-medium text-truncate" style={{maxWidth: '200px'}}>
                                                    {file.filename}
                                                </div>
                                                <small className="text-muted">
                                                    {formatFileSize(file.size)} • 
                                                    Uploaded by {file.uploadedBy} • 
                                                    {new Date(file.createdAt).toLocaleDateString()}
                                                </small>
                                            </div>
                                        </div>
                                        <div className="d-flex gap-1">
                                            <button
                                                className="btn btn-outline-primary btn-sm"
                                                onClick={() => handleDownload(file.id, file.filename)}
                                                title="Download file"
                                            >
                                                <i className="bi bi-download"></i>
                                            </button>
                                            <button
                                                className="btn btn-outline-danger btn-sm"
                                                onClick={() => handleDelete(file.id)}
                                                title="Delete file"
                                            >
                                                <i className="bi bi-trash"></i>
                                            </button>
                                        </div>
                                    </div>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <div className="text-center py-3 text-muted">
                            <i className="bi bi-file-earmark me-2"></i>
                            <small>No files uploaded yet</small>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

export default RequirementFileUpload;