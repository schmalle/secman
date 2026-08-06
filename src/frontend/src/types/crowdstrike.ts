export interface CrowdStrikeImportStatus {
    importedAt: string;
    importedBy?: string | null;
    serversProcessed: number;
    serversCreated: number;
    serversUpdated: number;
    vulnerabilitiesImported: number;
    vulnerabilitiesSkipped: number;
    errorCount: number;
}
