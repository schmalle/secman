package models;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Entity
@Table(name = "response")
public class Response {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_assessment_id", nullable = false)
    @NotNull
    private RiskAssessment riskAssessment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requirement_id", nullable = false)
    @NotNull
    private Requirement requirement;

    @Column(name = "respondent_email", nullable = false, length = 255)
    @NotNull
    private String respondentEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer", nullable = false)
    @NotNull
    private AnswerType answer;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum AnswerType {
        YES, NO, N_A
    }

    // Constructors
    public Response() {}

    public Response(RiskAssessment riskAssessment, Requirement requirement, String respondentEmail, AnswerType answer) {
        this.riskAssessment = riskAssessment;
        this.requirement = requirement;
        this.respondentEmail = respondentEmail;
        this.answer = answer;
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

    public String getRespondentEmail() {
        return respondentEmail;
    }

    public void setRespondentEmail(String respondentEmail) {
        this.respondentEmail = respondentEmail;
    }

    public AnswerType getAnswer() {
        return answer;
    }

    public void setAnswer(AnswerType answer) {
        this.answer = answer;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // Lifecycle methods
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "Response{" +
                "id=" + id +
                ", riskAssessmentId=" + (riskAssessment != null ? riskAssessment.getId() : null) +
                ", requirementId=" + (requirement != null ? requirement.getId() : null) +
                ", respondentEmail='" + respondentEmail + '\'' +
                ", answer=" + answer +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}