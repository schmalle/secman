package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.HashSet;

@Entity
@Table(name = "risk_assessment")
public class RiskAssessment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    @NotNull
    private Asset asset;

    @Column(name = "start_date", nullable = false)
    @NotNull
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    @NotNull
    private LocalDate endDate;

    @Column(name = "status", length = 50)
    private String status = "STARTED";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessor_id", nullable = false)
    @NotNull
    private User assessor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requestor_id", nullable = false)
    @NotNull
    private User requestor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "respondent_id")
    private User respondent; // The person who answers the risk assessment

    @Column(name = "notes", length = 1024)
    private String notes;
    
    // Release locking fields for versioning
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_id")
    @JsonIgnore
    private Release lockedRelease;
    
    @Column(name = "release_locked_at")
    private LocalDateTime releaseLockedAt;
    
    @Column(name = "is_release_locked")
    private Boolean isReleaseLocked = false;
    
    @Column(name = "content_snapshot_taken")
    private Boolean contentSnapshotTaken = false;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "risk_assessment_usecase",
        joinColumns = @JoinColumn(name = "risk_assessment_id"),
        inverseJoinColumns = @JoinColumn(name = "usecase_id")
    )
    private Set<UseCase> useCases = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Constructors
    public RiskAssessment() {}

    public RiskAssessment(Asset asset, LocalDate startDate, LocalDate endDate, User assessor, User requestor) {
        this.asset = asset;
        this.startDate = startDate;
        this.endDate = endDate;
        this.assessor = assessor;
        this.requestor = requestor;
        this.status = "STARTED";
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public Asset getAsset() {
        return asset;
    }

    public void setAsset(Asset asset) {
        this.asset = asset;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public User getAssessor() {
        return assessor;
    }

    public void setAssessor(User assessor) {
        this.assessor = assessor;
    }

    public User getRequestor() {
        return requestor;
    }

    public void setRequestor(User requestor) {
        this.requestor = requestor;
    }

    public User getRespondent() {
        return respondent;
    }

    public void setRespondent(User respondent) {
        this.respondent = respondent;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Set<UseCase> getUseCases() {
        return useCases;
    }

    public void setUseCases(Set<UseCase> useCases) {
        this.useCases = useCases;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public Release getLockedRelease() {
        return lockedRelease;
    }
    
    public void setLockedRelease(Release lockedRelease) {
        this.lockedRelease = lockedRelease;
    }
    
    public LocalDateTime getReleaseLockedAt() {
        return releaseLockedAt;
    }
    
    public void setReleaseLockedAt(LocalDateTime releaseLockedAt) {
        this.releaseLockedAt = releaseLockedAt;
    }
    
    public Boolean getIsReleaseLocked() {
        return isReleaseLocked;
    }
    
    public void setIsReleaseLocked(Boolean isReleaseLocked) {
        this.isReleaseLocked = isReleaseLocked;
    }
    
    public Boolean getContentSnapshotTaken() {
        return contentSnapshotTaken;
    }
    
    public void setContentSnapshotTaken(Boolean contentSnapshotTaken) {
        this.contentSnapshotTaken = contentSnapshotTaken;
    }

    // Lifecycle methods
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null) {
            status = "STARTED";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "RiskAssessment{" +
                "id=" + id +
                ", asset=" + (asset != null ? asset.getName() : "null") +
                ", startDate=" + startDate +
                ", endDate=" + endDate +
                ", status='" + status + '\'' +
                ", assessor=" + (assessor != null ? assessor.getUsername() : "null") +
                ", requestor=" + (requestor != null ? requestor.getUsername() : "null") +
                ", respondent=" + (respondent != null ? respondent.getUsername() : "null") +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
