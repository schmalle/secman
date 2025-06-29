package models;


import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "assessment_content_snapshots")
public class AssessmentContentSnapshot {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private RiskAssessment assessment;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_id", nullable = false)
    private Release release;
    
    @Lob
    @Column(name = "requirements_snapshot", nullable = false, columnDefinition = "JSON")
    private String requirementsSnapshot;
    
    @Lob
    @Column(name = "standards_snapshot", nullable = false, columnDefinition = "JSON")
    private String standardsSnapshot;
    
    @Lob
    @Column(name = "norms_snapshot", nullable = false, columnDefinition = "JSON")
    private String normsSnapshot;
    
    @Lob
    @Column(name = "usecases_snapshot", nullable = false, columnDefinition = "JSON")
    private String usecasesSnapshot;
    
    @Column(name = "snapshot_created_at", nullable = false)
    private Instant snapshotCreatedAt;
    
    @Column(name = "snapshot_hash", nullable = false, length = 64)
    private String snapshotHash;
    
    // Constructors
    public AssessmentContentSnapshot() {
        this.snapshotCreatedAt = Instant.now();
    }
    
    public AssessmentContentSnapshot(RiskAssessment assessment, Release release) {
        this();
        this.assessment = assessment;
        this.release = release;
    }
    
    
    // Business methods
    public boolean verifyIntegrity(String expectedHash) {
        return this.snapshotHash != null && this.snapshotHash.equals(expectedHash);
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public RiskAssessment getAssessment() { return assessment; }
    public void setAssessment(RiskAssessment assessment) { this.assessment = assessment; }
    
    public Release getRelease() { return release; }
    public void setRelease(Release release) { this.release = release; }
    
    public String getRequirementsSnapshot() { return requirementsSnapshot; }
    public void setRequirementsSnapshot(String requirementsSnapshot) { this.requirementsSnapshot = requirementsSnapshot; }
    
    public String getStandardsSnapshot() { return standardsSnapshot; }
    public void setStandardsSnapshot(String standardsSnapshot) { this.standardsSnapshot = standardsSnapshot; }
    
    public String getNormsSnapshot() { return normsSnapshot; }
    public void setNormsSnapshot(String normsSnapshot) { this.normsSnapshot = normsSnapshot; }
    
    public String getUsecasesSnapshot() { return usecasesSnapshot; }
    public void setUsecasesSnapshot(String usecasesSnapshot) { this.usecasesSnapshot = usecasesSnapshot; }
    
    public Instant getSnapshotCreatedAt() { return snapshotCreatedAt; }
    public void setSnapshotCreatedAt(Instant snapshotCreatedAt) { this.snapshotCreatedAt = snapshotCreatedAt; }
    
    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String snapshotHash) { this.snapshotHash = snapshotHash; }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AssessmentContentSnapshot that = (AssessmentContentSnapshot) o;
        return id != null && id.equals(that.id);
    }
    
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
    
    @Override
    public String toString() {
        return "AssessmentContentSnapshot{" +
            "id=" + id +
            ", assessmentId=" + (assessment != null ? assessment.getId() : null) +
            ", releaseId=" + (release != null ? release.getId() : null) +
            ", snapshotCreatedAt=" + snapshotCreatedAt +
            ", snapshotHash='" + snapshotHash + '\'' +
            '}';
    }
}