package models;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "risk")
public class Risk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    @NotNull
    private Asset asset;

    @Column(nullable = false, length = 255)
    @NotBlank
    private String name;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(nullable = false)
    @Min(1)
    @Max(5)
    private Integer likelihood;

    @Column(nullable = false)
    @Min(1)
    @Max(5)
    private Integer impact;

    @Column(name = "risk_level", nullable = false)
    private Integer riskLevel;

    @Column(nullable = false, length = 20)
    private String status = "OPEN";

    @Column(nullable = true, length = 255)
    private String owner;

    @Column(name = "deadline", nullable = true)
    private LocalDate deadline;

    @Column(nullable = true, length = 20)
    private String severity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public Asset getAsset() { return asset; }
    public void setAsset(Asset asset) { this.asset = asset; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getLikelihood() { return likelihood; }
    public void setLikelihood(Integer likelihood) { this.likelihood = likelihood; }
    public Integer getImpact() { return impact; }
    public void setImpact(Integer impact) { this.impact = impact; }
    public Integer getRiskLevel() { return riskLevel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public LocalDate getDeadline() { return deadline; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        computeRiskLevel();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        computeRiskLevel();
    }

    private void computeRiskLevel() {
        int prod = (likelihood == null ? 1 : likelihood) * (impact == null ? 1 : impact);
        if (prod <= 4) {
            riskLevel = 1;
        } else if (prod <= 9) {
            riskLevel = 2;
        } else if (prod <= 15) {
            riskLevel = 3;
        } else {
            riskLevel = 4;
        }
    }
}
