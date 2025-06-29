package models;

import com.fasterxml.jackson.annotation.JsonIgnore; // Added import
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users") // Use "users" as table name to avoid conflict with reserved keywords
public class User {

    // Define Role enum inside User class or in its own file (models.Role)
    public enum Role {
        USER,
        ADMIN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false) // Add email field
    private String email; 

    @Column(name = "password_hash", nullable = false) // Explicitly map to snake_case column name
    private String passwordHash; // Store hashed passwords, never plain text

    @ElementCollection(targetClass = Role.class, fetch = FetchType.EAGER) // Eager fetch for simplicity, consider LAZY for performance
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false)
    private Set<Role> roles = new HashSet<>(); // Initialize to avoid nulls

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @JsonIgnore // Added annotation
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
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
    
    // Business methods
    public boolean hasRole(String roleName) {
        if (roles == null || roleName == null) {
            return false;
        }
        try {
            Role role = Role.valueOf(roleName.toUpperCase());
            return roles.contains(role);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    
    public boolean hasRole(Role role) {
        return roles != null && roles.contains(role);
    }
    
    public boolean isAdmin() {
        return hasRole(Role.ADMIN);
    }
    
}
