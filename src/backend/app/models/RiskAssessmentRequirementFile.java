package models;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "risk_assessment_requirement_files")
public class RiskAssessmentRequirementFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_assessment_id", nullable = false)
    private RiskAssessment riskAssessment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requirement_id", nullable = false)
    private Requirement requirement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private RequirementFile file;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public RiskAssessmentRequirementFile() {}

    public RiskAssessmentRequirementFile(RiskAssessment riskAssessment, Requirement requirement, 
                                       RequirementFile file, User uploadedBy) {
        this.riskAssessment = riskAssessment;
        this.requirement = requirement;
        this.file = file;
        this.uploadedBy = uploadedBy;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public RiskAssessment getRiskAssessment() {
        return riskAssessment;
    }

    public void setRiskAssessment(RiskAssessment riskAssessment) {
        this.riskAssessment = riskAssessment;
    }

    public Requirement getRequirement() {
        return requirement;
    }

    public void setRequirement(Requirement requirement) {
        this.requirement = requirement;
    }

    public RequirementFile getFile() {
        return file;
    }

    public void setFile(RequirementFile file) {
        this.file = file;
    }

    public User getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(User uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "RiskAssessmentRequirementFile{" +
                "id=" + id +
                ", riskAssessment=" + (riskAssessment != null ? riskAssessment.getId() : "null") +
                ", requirement=" + (requirement != null ? requirement.getId() : "null") +
                ", file=" + (file != null ? file.getOriginalFilename() : "null") +
                ", uploadedBy=" + (uploadedBy != null ? uploadedBy.getUsername() : "null") +
                ", createdAt=" + createdAt +
                '}';
    }
}