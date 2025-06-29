package models;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "requirement")
public class Requirement extends VersionedEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String shortreq; // Renamed from title
    
    @Column(name = "description", columnDefinition = "TEXT") // Map field 'details' to DB column 'description'
    private String details; // Renamed from description

    @Column
    private String language; // New field

    @Column(columnDefinition = "TEXT")
    private String example; // New field

    @Column(columnDefinition = "TEXT")
    private String motivation; // New field
    
    @Column(columnDefinition = "TEXT")
    private String usecase; // New field for use cases

    @Column(name = "Norm", columnDefinition = "TEXT")
    private String norm; // New field for norm

    @Column(name = "chapter", columnDefinition = "TEXT") // New field for Chapter
    private String chapter; // New field

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // Note: Requirements are not directly linked to Standards
    // Relationship flow: Standard -> UseCase -> Requirements

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "requirement_usecase",
        joinColumns = @JoinColumn(name = "requirement_id"),
        inverseJoinColumns = @JoinColumn(name = "usecase_id")
    )
    private Set<UseCase> usecases = new HashSet<>();
    
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "requirement_norm",
        joinColumns = @JoinColumn(name = "requirement_id"),
        inverseJoinColumns = @JoinColumn(name = "norm_id")
    )
    private Set<Norm> norms = new HashSet<>();
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getShortreq() { // Renamed from getTitle
        return shortreq;
    }
    
    public void setShortreq(String shortreq) { // Renamed from setTitle
        this.shortreq = shortreq;
    }
    
    public String getDetails() { // Renamed from getDescription
        return details;
    }
    
    public void setDetails(String details) { // Renamed from setDescription
        this.details = details;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getExample() {
        return example;
    }

    public void setExample(String example) {
        this.example = example;
    }

    public void setUseCase(String usecase) {
        this.usecase = usecase;
    }

    public String getUseCase() {
        return usecase;
    }

    public String getMotivation() {
        return motivation;
    }

    public void setMotivation(String motivation) {
        this.motivation = motivation;
    }

    public String getUsecase() {
        return usecase;
    }

    public void setUsecase(String usecase) {
        this.usecase = usecase;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Standards relationship removed - use Standard -> UseCase -> Requirements flow

    public String getNorm() {
        return norm;
    }

    public void setNorm(String norm) {
        this.norm = norm;
    }

    public String getChapter() { // Getter for chapter
        return chapter;
    }

    public void setChapter(String chapter) { // Setter for chapter
        this.chapter = chapter;
    }

    public Set<UseCase> getUsecases() {
        return usecases;
    }

    public void setUsecases(Set<UseCase> usecases) {
        this.usecases = usecases;
    }

    public Set<Norm> getNorms() {
        return norms;
    }

    public void setNorms(Set<Norm> norms) {
        this.norms = norms;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
