package models;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Date;

@Entity
@Table(name = "norm")
public class Norm extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Changed for DB compatibility
    public Long id;

    @Column(nullable = false, unique = true) // Added constraints
    public String name;

    @Column(nullable = false)
    public String version = ""; // Default to empty string

    public Integer year; // Default to null for Integer

    @Column(name = "created_at", nullable = false, updatable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;

    @Column(name = "updated_at", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date updatedAt;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = (version == null) ? "" : version; // Ensure empty string if null is passed
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    // No setter for createdAt as it's managed by @PrePersist

    public Date getUpdatedAt() {
        return updatedAt;
    }

    // No setter for updatedAt as it's managed by @PrePersist/@PreUpdate

    @PrePersist
    protected void onCreate() {
        updatedAt = createdAt = new Date();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Date();
    }
}