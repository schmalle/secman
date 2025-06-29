package models;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "auth_audit_log")
public class AuthAuditLog {

    public enum EventType {
        LOGIN_SUCCESS, LOGIN_FAILURE, LOGOUT, TOKEN_REFRESH, PROVISION_USER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id")
    private IdentityProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "external_user_id", length = 255)
    private String externalUserId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Constructors
    public AuthAuditLog() {}

    public AuthAuditLog(EventType eventType) {
        this.eventType = eventType;
        this.createdAt = Instant.now();
    }

    public AuthAuditLog(User user, IdentityProvider provider, EventType eventType) {
        this.user = user;
        this.provider = provider;
        this.eventType = eventType;
        this.createdAt = Instant.now();
    }

    // Lifecycle methods
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public void setProvider(IdentityProvider provider) {
        this.provider = provider;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public void setExternalUserId(String externalUserId) {
        this.externalUserId = externalUserId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // Static factory methods
    public static AuthAuditLog loginSuccess(User user, IdentityProvider provider, String ipAddress, String userAgent) {
        AuthAuditLog log = new AuthAuditLog(user, provider, EventType.LOGIN_SUCCESS);
        log.setIpAddress(ipAddress);
        log.setUserAgent(userAgent);
        return log;
    }

    public static AuthAuditLog loginFailure(IdentityProvider provider, String externalUserId, String errorMessage, String ipAddress, String userAgent) {
        AuthAuditLog log = new AuthAuditLog(null, provider, EventType.LOGIN_FAILURE);
        log.setExternalUserId(externalUserId);
        log.setErrorMessage(errorMessage);
        log.setIpAddress(ipAddress);
        log.setUserAgent(userAgent);
        return log;
    }

    public static AuthAuditLog userProvisioned(User user, IdentityProvider provider, String ipAddress, String userAgent) {
        AuthAuditLog log = new AuthAuditLog(user, provider, EventType.PROVISION_USER);
        log.setIpAddress(ipAddress);
        log.setUserAgent(userAgent);
        return log;
    }
}