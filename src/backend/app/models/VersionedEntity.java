package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * Base class for entities that support versioning.
 * Provides common fields and behavior for versioned entities.
 */
@MappedSuperclass
public abstract class VersionedEntity {
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_id")
    @JsonIgnore
    private Release release;
    
    @Column(name = "version_number")
    private Integer versionNumber = 1;
    
    @Column(name = "is_current")
    private Boolean isCurrent = true;
    
    // Constructors
    public VersionedEntity() {}
    
    public VersionedEntity(Release release) {
        this.release = release;
        this.versionNumber = 1;
        this.isCurrent = true;
    }
    
    // Business methods
    public void incrementVersion() {
        if (this.versionNumber == null) {
            this.versionNumber = 1;
        } else {
            this.versionNumber++;
        }
    }
    
    public void markAsHistorical() {
        this.isCurrent = false;
    }
    
    public void markAsCurrent() {
        this.isCurrent = true;
    }
    
    public boolean isCurrentVersion() {
        return this.isCurrent != null && this.isCurrent;
    }
    
    public boolean belongsToRelease(Release release) {
        return this.release != null && this.release.equals(release);
    }
    
    // Getters and Setters
    public Release getRelease() { return release; }
    public void setRelease(Release release) { this.release = release; }
    
    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }
    
    public Boolean getIsCurrent() { return isCurrent; }
    public void setIsCurrent(Boolean isCurrent) { this.isCurrent = isCurrent; }
}