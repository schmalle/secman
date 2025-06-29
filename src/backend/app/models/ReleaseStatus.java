package models;

/**
 * Enumeration for Release statuses in the versioning system.
 */
public enum ReleaseStatus {
    /**
     * Draft release - can be edited and modified
     */
    DRAFT,
    
    /**
     * Active release - published and in use, read-only
     */
    ACTIVE,
    
    /**
     * Archived release - no longer active but preserved for historical access
     */
    ARCHIVED
}