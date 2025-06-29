package models;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

@Entity
@Table(name = "releases")
public class Release {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false, length = 50)
    private String version;
    
    @Column(nullable = false)
    private String name;
    
    @Lob
    private String description;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReleaseStatus status = ReleaseStatus.DRAFT;
    
    @Column(name = "release_date")
    private Instant releaseDate;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    // Relationships to versioned entities
    @OneToMany(mappedBy = "release", fetch = FetchType.LAZY)
    @JsonIgnore
    private Set<Requirement> requirements = new HashSet<>();
    
    @OneToMany(mappedBy = "release", fetch = FetchType.LAZY)
    @JsonIgnore
    private Set<Standard> standards = new HashSet<>();
    
    @OneToMany(mappedBy = "release", fetch = FetchType.LAZY)
    @JsonIgnore
    private Set<Norm> norms = new HashSet<>();
    
    @OneToMany(mappedBy = "release", fetch = FetchType.LAZY)
    @JsonIgnore
    private Set<UseCase> useCases = new HashSet<>();
    
    @OneToMany(mappedBy = "lockedRelease", fetch = FetchType.LAZY)
    @JsonIgnore
    private Set<RiskAssessment> riskAssessments = new HashSet<>();
    
    // Constructors
    public Release() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
    
    public Release(String version, String name, String description, User createdBy) {
        this();
        this.version = version;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
    }
    
    // Lifecycle callbacks
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
    
    
    // Business methods
    public boolean canEdit() {
        return this.status == ReleaseStatus.DRAFT;
    }
    
    public boolean canPublish() {
        return this.status == ReleaseStatus.DRAFT;
    }
    
    public boolean canArchive() {
        return this.status == ReleaseStatus.ACTIVE;
    }
    
    public void publish() {
        if (canPublish()) {
            this.status = ReleaseStatus.ACTIVE;
            this.releaseDate = Instant.now();
        } else {
            throw new IllegalStateException("Release cannot be published in current status: " + this.status);
        }
    }
    
    public void archive() {
        if (canArchive()) {
            this.status = ReleaseStatus.ARCHIVED;
        } else {
            throw new IllegalStateException("Release cannot be archived in current status: " + this.status);
        }
    }
    
    @JsonIgnore
    public int getRequirementsCount() {
        return requirements.size();
    }
    
    @JsonIgnore
    public int getStandardsCount() {
        return standards.size();
    }
    
    @JsonIgnore
    public int getNormsCount() {
        return norms.size();
    }
    
    @JsonIgnore
    public int getUseCasesCount() {
        return useCases.size();
    }
    
    @JsonIgnore
    public int getAssessmentsCount() {
        return riskAssessments.size();
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public ReleaseStatus getStatus() { return status; }
    public void setStatus(ReleaseStatus status) { this.status = status; }
    
    public Instant getReleaseDate() { return releaseDate; }
    public void setReleaseDate(Instant releaseDate) { this.releaseDate = releaseDate; }
    
    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    
    public Set<Requirement> getRequirements() { return requirements; }
    public void setRequirements(Set<Requirement> requirements) { this.requirements = requirements; }
    
    public Set<Standard> getStandards() { return standards; }
    public void setStandards(Set<Standard> standards) { this.standards = standards; }
    
    public Set<Norm> getNorms() { return norms; }
    public void setNorms(Set<Norm> norms) { this.norms = norms; }
    
    public Set<UseCase> getUseCases() { return useCases; }
    public void setUseCases(Set<UseCase> useCases) { this.useCases = useCases; }
    
    public Set<RiskAssessment> getRiskAssessments() { return riskAssessments; }
    public void setRiskAssessments(Set<RiskAssessment> riskAssessments) { this.riskAssessments = riskAssessments; }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Release release = (Release) o;
        return id != null && id.equals(release.id);
    }
    
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
    
    @Override
    public String toString() {
        return "Release{" +
            "id=" + id +
            ", version='" + version + '\'' +
            ", name='" + name + '\'' +
            ", status=" + status +
            ", releaseDate=" + releaseDate +
            '}';
    }
}