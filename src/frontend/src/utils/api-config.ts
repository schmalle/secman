// API Configuration - Migration Complete
// All APIs now use the Kotlin backend (port 8080)

export const API_CONFIG = {
  // Backend URL - Using Kotlin backend on port 8080
  // In production (when served from secman.covestro.net), use relative URLs
  // In development, use localhost
  BACKEND_URL: typeof window !== 'undefined' && window.location.hostname !== 'localhost'
    ? '' // Use relative URLs in production
    : 'http://localhost:8080', // Use localhost in development
  
  // Legacy migration flags - kept for reference, all APIs are now migrated ✅
  USE_KOTLIN_FOR: {
    // Authentication - Migrated and tested ✅
    auth: true,           // /login, /logout, /status
    
    // User Management - Migrated and tested ✅  
    users: true,          // /api/users/*
    
    // Requirements - Migrated and tested ✅
    requirements: true,   // /requirements/*
    
    // Health checks - Kotlin only ✅
    health: true,         // /health
    
    // Standards - Migrated and tested ✅
    standards: true,      // /api/standards/*
    
    // Risk Assessments and Risks - Migrated and tested ✅
    risks: true,          // /api/risks/* and /api/risk-assessments/*
    responses: true,      // /api/responses/*
    
    // Norms - Migrated and tested ✅
    norms: true,          // /api/norms/*
    
    // UseCases - Migrated and tested ✅
    usecases: true,       // /api/usecases/*
    
    // Assets - Migrated and tested ✅
    assets: true,         // /api/assets/*
    
    // Import/Export - Migrated and tested ✅
    import: true,         // /api/import/*
    
    // Email Configuration - Migrated and tested ✅
    email: true,          // /api/email-config/*
    
    // Translation Configuration - Migrated and tested ✅
    translations: true,   // /api/translation-config/*
    
    // File Upload/Download - Migrated and tested ✅
    files: true,          // /api/files/*, /api/risk-assessments/*/requirements/*/files
    
    oauth: true,          // OAuth integration - Migrated ✅
    reports: true,        // Reporting - Migrated ✅
  }
};

/**
 * Get the backend URL - all APIs now use Kotlin backend
 */
export function getBackendUrl(category?: keyof typeof API_CONFIG.USE_KOTLIN_FOR): string {
  return API_CONFIG.BACKEND_URL;
}

/**
 * Get the full API endpoint URL for a given path and category
 */
export function getApiEndpoint(path: string, category: keyof typeof API_CONFIG.USE_KOTLIN_FOR): string {
  const baseUrl = getBackendUrl(category);
  
  // Remove leading slash if present to avoid double slashes
  const cleanPath = path.startsWith('/') ? path.slice(1) : path;
  
  return `${baseUrl}/${cleanPath}`;
}

/**
 * API endpoint helpers for each migrated category
 */
export const API_ENDPOINTS = {
  // Authentication endpoints
  auth: {
    login: () => getApiEndpoint('api/auth/login', 'auth'),
    logout: () => getApiEndpoint('api/auth/logout', 'auth'),
    status: () => getApiEndpoint('api/auth/status', 'auth'),
  },
  
  // User management endpoints
  users: {
    list: () => getApiEndpoint('api/users', 'users'),
    create: () => getApiEndpoint('api/users', 'users'),
    get: (id: number) => getApiEndpoint(`api/users/${id}`, 'users'),
    update: (id: number) => getApiEndpoint(`api/users/${id}`, 'users'),
    delete: (id: number) => getApiEndpoint(`api/users/${id}`, 'users'),
  },
  
  // Requirements endpoints
  requirements: {
    list: () => getApiEndpoint('api/requirements', 'requirements'),
    create: () => getApiEndpoint('api/requirements', 'requirements'),
    get: (id: number) => getApiEndpoint(`api/requirements/${id}`, 'requirements'),
    update: (id: number) => getApiEndpoint(`api/requirements/${id}`, 'requirements'),
    delete: (id: number) => getApiEndpoint(`api/requirements/${id}`, 'requirements'),
    deleteAll: () => getApiEndpoint('api/requirements/all', 'requirements'),
    exportDocx: () => getApiEndpoint('api/requirements/export/docx', 'requirements'),
    exportDocxByUseCase: (usecaseId: number) => getApiEndpoint(`api/requirements/export/docx/usecase/${usecaseId}`, 'requirements'),
    exportExcel: () => getApiEndpoint('api/requirements/export/xlsx', 'requirements'),
    exportExcelByUseCase: (usecaseId: number) => getApiEndpoint(`api/requirements/export/xlsx/usecase/${usecaseId}`, 'requirements'),
    // Translated exports - integrated with translation service
    exportDocxTranslated: (language: string) => getApiEndpoint(`api/requirements/export/docx/translated/${language}`, 'translations'),
    exportDocxTranslatedByUseCase: (usecaseId: number, language: string) => getApiEndpoint(`api/requirements/export/docx/usecase/${usecaseId}/translated/${language}`, 'translations'),
  },
  
  // Health endpoint (Java backend)
  health: {
    check: () => getApiEndpoint('api/health', 'health'),
  },
  
  // Standards endpoints - Migrated ✅
  standards: {
    list: () => getApiEndpoint('api/standards', 'standards'),
    create: () => getApiEndpoint('api/standards', 'standards'),
    get: (id: number) => getApiEndpoint(`api/standards/${id}`, 'standards'),
    update: (id: number) => getApiEndpoint(`api/standards/${id}`, 'standards'),
    delete: (id: number) => getApiEndpoint(`api/standards/${id}`, 'standards'),
  },
  
  // Risk Assessment endpoints - Migrated ✅
  riskAssessments: {
    list: () => getApiEndpoint('api/risk-assessments', 'risks'),
    create: () => getApiEndpoint('api/risk-assessments', 'risks'),
    get: (id: number) => getApiEndpoint(`api/risk-assessments/${id}`, 'risks'),
    update: (id: number) => getApiEndpoint(`api/risk-assessments/${id}`, 'risks'),
    delete: (id: number) => getApiEndpoint(`api/risk-assessments/${id}`, 'risks'),
    getByAsset: (assetId: number) => getApiEndpoint(`api/risk-assessments/asset/${assetId}`, 'risks'),
    generateToken: (id: number) => getApiEndpoint(`api/risk-assessments/${id}/token`, 'risks'),
    notify: (id: number) => getApiEndpoint(`api/risk-assessments/${id}/notify`, 'risks'),
    remind: (id: number) => getApiEndpoint(`api/risk-assessments/${id}/remind`, 'risks'),
  },
  
  // Risk endpoints - Migrated ✅
  risks: {
    list: () => getApiEndpoint('api/risks', 'risks'),
    create: () => getApiEndpoint('api/risks', 'risks'),
    get: (id: number) => getApiEndpoint(`api/risks/${id}`, 'risks'),
    update: (id: number) => getApiEndpoint(`api/risks/${id}`, 'risks'),
    delete: (id: number) => getApiEndpoint(`api/risks/${id}`, 'risks'),
    getByAsset: (assetId: number) => getApiEndpoint(`api/risks/asset/${assetId}`, 'risks'),
  },
  
  // Response endpoints - Migrated ✅
  responses: {
    getAssessment: (token: string) => getApiEndpoint(`api/responses/assessment/${token}`, 'responses'),
    saveResponse: (token: string) => getApiEndpoint(`api/responses/${token}/save`, 'responses'),
    submitAssessment: (token: string) => getApiEndpoint(`api/responses/${token}/submit`, 'responses'),
    getAllResponses: (assessmentId: number) => getApiEndpoint(`api/responses/assessment/${assessmentId}`, 'responses'),
    getResponsesByEmail: (assessmentId: number, email: string) => getApiEndpoint(`api/responses/assessment/${assessmentId}/email/${email}`, 'responses'),
  },
  
  // Norms endpoints - Migrated ✅
  norms: {
    list: () => getApiEndpoint('api/norms', 'norms'),
    create: () => getApiEndpoint('api/norms', 'norms'),
    get: (id: number) => getApiEndpoint(`api/norms/${id}`, 'norms'),
    update: (id: number) => getApiEndpoint(`api/norms/${id}`, 'norms'),
    delete: (id: number) => getApiEndpoint(`api/norms/${id}`, 'norms'),
    deleteAll: () => getApiEndpoint('api/norms/all', 'norms'),
  },
  
  // UseCases endpoints - Migrated ✅
  useCases: {
    list: () => getApiEndpoint('api/usecases', 'usecases'),
    create: () => getApiEndpoint('api/usecases', 'usecases'),
    get: (id: number) => getApiEndpoint(`api/usecases/${id}`, 'usecases'),
    update: (id: number) => getApiEndpoint(`api/usecases/${id}`, 'usecases'),
    delete: (id: number) => getApiEndpoint(`api/usecases/${id}`, 'usecases'),
  },
  
  // Assets endpoints - Migrated ✅
  assets: {
    list: () => getApiEndpoint('api/assets', 'assets'),
    create: () => getApiEndpoint('api/assets', 'assets'),
    get: (id: number) => getApiEndpoint(`api/assets/${id}`, 'assets'),
    update: (id: number) => getApiEndpoint(`api/assets/${id}`, 'assets'),
    delete: (id: number) => getApiEndpoint(`api/assets/${id}`, 'assets'),
  },
  
  // Import/Export endpoints - Migrated ✅
  import: {
    uploadXlsx: () => getApiEndpoint('api/import/upload-xlsx', 'import'),
  },
  
  // Email Configuration endpoints - Migrated ✅
  emailConfig: {
    list: () => getApiEndpoint('api/email-config', 'email'),
    create: () => getApiEndpoint('api/email-config', 'email'),
    get: (id: number) => getApiEndpoint(`api/email-config/${id}`, 'email'),
    update: (id: number) => getApiEndpoint(`api/email-config/${id}`, 'email'),
    delete: (id: number) => getApiEndpoint(`api/email-config/${id}`, 'email'),
    getActive: () => getApiEndpoint('api/email-config/active', 'email'),
    test: (id: number) => getApiEndpoint(`api/email-config/${id}/test`, 'email'),
  },
  
  // Translation Configuration endpoints - Migrated ✅
  translationConfig: {
    list: () => getApiEndpoint('api/translation-config', 'translations'),
    create: () => getApiEndpoint('api/translation-config', 'translations'),
    get: (id: number) => getApiEndpoint(`api/translation-config/${id}`, 'translations'),
    update: (id: number) => getApiEndpoint(`api/translation-config/${id}`, 'translations'),
    delete: (id: number) => getApiEndpoint(`api/translation-config/${id}`, 'translations'),
    getActive: () => getApiEndpoint('api/translation-config/active', 'translations'),
    test: (id: number) => getApiEndpoint(`api/translation-config/${id}/test`, 'translations'),
    activate: (id: number) => getApiEndpoint(`api/translation-config/${id}/activate`, 'translations'),
    deactivate: (id: number) => getApiEndpoint(`api/translation-config/${id}/deactivate`, 'translations'),
    getModels: () => getApiEndpoint('api/translation-config/models', 'translations'),
    getLanguages: () => getApiEndpoint('api/translation-config/languages', 'translations'),
  },

  // File Upload/Download endpoints - Migrated ✅
  files: {
    upload: (riskAssessmentId: number, requirementId: number) => 
      getApiEndpoint(`api/risk-assessments/${riskAssessmentId}/requirements/${requirementId}/files`, 'files'),
    list: (riskAssessmentId: number, requirementId: number) => 
      getApiEndpoint(`api/risk-assessments/${riskAssessmentId}/requirements/${requirementId}/files`, 'files'),
    download: (fileId: number) => getApiEndpoint(`api/files/${fileId}/download`, 'files'),
    get: (fileId: number) => getApiEndpoint(`api/files/${fileId}`, 'files'),
    delete: (fileId: number) => getApiEndpoint(`api/files/${fileId}`, 'files'),
    myFiles: () => getApiEndpoint('api/files/my-files', 'files'),
    statistics: () => getApiEndpoint('api/files/statistics', 'files'),
    config: () => getApiEndpoint('api/files/config', 'files'),
  },
  
  // Add other endpoints as they get migrated...
};

/**
 * Migration status helper
 */
export function getMigrationStatus() {
  const migrated = Object.entries(API_CONFIG.USE_KOTLIN_FOR).filter(([_, usesKotlin]) => usesKotlin);
  const total = Object.keys(API_CONFIG.USE_KOTLIN_FOR).length;
  
  return {
    migrated: migrated.length,
    total: total,
    percentage: Math.round((migrated.length / total) * 100),
    migratedApis: migrated.map(([api]) => api),
    remainingApis: Object.entries(API_CONFIG.USE_KOTLIN_FOR)
      .filter(([_, usesKotlin]) => !usesKotlin)
      .map(([api]) => api)
  };
}

/**
 * Development helper to log current migration status
 */
if (import.meta.env.DEV) {
  const status = getMigrationStatus();
  console.log('🚀 API Migration Status:', status);
  console.log(`📊 Progress: ${status.migrated}/${status.total} (${status.percentage}%)`);
  console.log('✅ Migrated APIs:', status.migratedApis);
  console.log('🚧 Remaining APIs:', status.remainingApis);
}