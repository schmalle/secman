package services;

import models.*;
import play.db.jpa.JPAApi;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Singleton
public class ReleaseService {
    
    private final ObjectMapper objectMapper;
    private final JPAApi jpaApi;
    
    @Inject
    public ReleaseService(ObjectMapper objectMapper, JPAApi jpaApi) {
        this.objectMapper = objectMapper;
        this.jpaApi = jpaApi;
    }
    
    public Release createRelease(String version, String name, String description, User createdBy) {
        return jpaApi.withTransaction(em -> {
            // Check if version already exists
            try {
                em.createQuery("SELECT r FROM Release r WHERE r.version = :version", Release.class)
                    .setParameter("version", version)
                    .getSingleResult();
                throw new IllegalArgumentException("Release version " + version + " already exists");
            } catch (NoResultException e) {
                // Version doesn't exist, proceed
            }
            
            Release release = new Release(version, name, description, createdBy);
            em.persist(release);
            return release;
        });
    }
    
    public Release updateRelease(Long id, String version, String name, String description) {
        return jpaApi.withTransaction(em -> {
            Release release = em.find(Release.class, id);
            if (release == null) {
                throw new IllegalArgumentException("Release not found with id: " + id);
            }
            
            if (!release.canEdit()) {
                throw new IllegalStateException("Cannot edit release in status: " + release.getStatus());
            }
            
            // Check if version is being changed and conflicts with existing
            if (!release.getVersion().equals(version)) {
                try {
                    Release existing = em.createQuery("SELECT r FROM Release r WHERE r.version = :version", Release.class)
                        .setParameter("version", version)
                        .getSingleResult();
                    if (!existing.getId().equals(id)) {
                        throw new IllegalArgumentException("Release version " + version + " already exists");
                    }
                } catch (NoResultException e) {
                    // Version doesn't exist, proceed
                }
                release.setVersion(version);
            }
            
            release.setName(name);
            release.setDescription(description);
            
            return em.merge(release);
        });
    }
    
    public Release publishRelease(Long id) {
        return jpaApi.withTransaction(em -> {
            Release release = em.find(Release.class, id);
            if (release == null) {
                throw new IllegalArgumentException("Release not found with id: " + id);
            }
            
            if (!release.canPublish()) {
                throw new IllegalStateException("Cannot publish release in status: " + release.getStatus());
            }
            
            // Archive any currently active release
            try {
                Release currentActive = em.createQuery("SELECT r FROM Release r WHERE r.status = :status ORDER BY r.releaseDate DESC", Release.class)
                    .setParameter("status", ReleaseStatus.ACTIVE)
                    .setMaxResults(1)
                    .getSingleResult();
                currentActive.archive();
                em.merge(currentActive);
            } catch (NoResultException e) {
                // No active release found, proceed
            }
            
            // Publish this release
            release.publish();
            
            // Create snapshots for all current entities
            createReleaseSnapshot(release);
            
            return em.merge(release);
        });
    }
    
    
    public Release archiveRelease(Long id) {
        return jpaApi.withTransaction(em -> {
            Release release = em.find(Release.class, id);
            if (release == null) {
                throw new IllegalArgumentException("Release not found with id: " + id);
            }
            
            if (!release.canArchive()) {
                throw new IllegalStateException("Cannot archive release in status: " + release.getStatus());
            }
            
            release.archive();
            return em.merge(release);
        });
    }
    
    
    public void deleteRelease(Long id) {
        jpaApi.withTransaction(em -> {
            Release release = em.find(Release.class, id);
            if (release == null) {
                throw new IllegalArgumentException("Release not found with id: " + id);
            }
            
            if (release.getStatus() != ReleaseStatus.DRAFT) {
                throw new IllegalStateException("Only draft releases can be deleted");
            }
            
            em.remove(release);
            return null;
        });
    }
    
    public List<Release> getAllReleases() {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) ->
            em.createQuery("SELECT r FROM Release r JOIN FETCH r.createdBy ORDER BY r.releaseDate DESC, r.version DESC", Release.class)
                .getResultList()
        );
    }
    
    
    public List<Release> getReleasesByStatus(ReleaseStatus status) {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) ->
            em.createQuery("SELECT r FROM Release r JOIN FETCH r.createdBy WHERE r.status = :status ORDER BY r.releaseDate DESC", Release.class)
                .setParameter("status", status)
                .getResultList()
        );
    }
    
    
    public Release getCurrentActiveRelease() {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) -> {
            try {
                return em.createQuery("SELECT r FROM Release r JOIN FETCH r.createdBy WHERE r.status = :status ORDER BY r.releaseDate DESC", Release.class)
                    .setParameter("status", ReleaseStatus.ACTIVE)
                    .setMaxResults(1)
                    .getSingleResult();
            } catch (NoResultException e) {
                return null;
            }
        });
    }
    
    
    public Release getReleaseById(Long id) {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) -> {
            try {
                return em.createQuery("SELECT r FROM Release r JOIN FETCH r.createdBy WHERE r.id = :id", Release.class)
                    .setParameter("id", id)
                    .getSingleResult();
            } catch (NoResultException e) {
                return null;
            }
        });
    }
    
    
    public Release getReleaseByVersion(String version) {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) -> {
            try {
                return em.createQuery("SELECT r FROM Release r JOIN FETCH r.createdBy WHERE r.version = :version", Release.class)
                    .setParameter("version", version)
                    .getSingleResult();
            } catch (NoResultException e) {
                return null;
            }
        });
    }
    
    
    public long getDraftReleasesCount() {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) ->
            em.createQuery("SELECT COUNT(r) FROM Release r WHERE r.status = :status", Long.class)
                .setParameter("status", ReleaseStatus.DRAFT)
                .getSingleResult()
        );
    }
    
    
    public long getTotalReleasesCount() {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) -> 
            em.createQuery("SELECT COUNT(r) FROM Release r", Long.class)
                .getSingleResult()
        );
    }
    
    
    public long getActiveAssessmentsCount() {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) ->
            em.createQuery("SELECT COUNT(ra) FROM RiskAssessment ra WHERE ra.status != 'COMPLETED'", Long.class)
                .getSingleResult()
        );
    }
    
    
    public void createContentSnapshot(RiskAssessment assessment, Release release) {
        jpaApi.withTransaction(em -> {
            // Check if snapshot already exists
            Long count = em.createQuery("SELECT COUNT(acs) FROM AssessmentContentSnapshot acs WHERE acs.assessment.id = :assessmentId", Long.class)
                .setParameter("assessmentId", assessment.getId())
                .getSingleResult();
            if (count > 0) {
                return null; // Snapshot already exists
            }
            
            try {
                // Fetch all content for this release
                List<Requirement> requirements = getRequirementsForRelease(release.getId());
                List<Standard> standards = getStandardsForRelease(release.getId());
                List<Norm> norms = getNormsForRelease(release.getId());
                List<UseCase> useCases = getUseCasesForRelease(release.getId());
                
                // Create JSON snapshots
                String requirementsJson = objectMapper.writeValueAsString(requirements);
                String standardsJson = objectMapper.writeValueAsString(standards);
                String normsJson = objectMapper.writeValueAsString(norms);
                String useCasesJson = objectMapper.writeValueAsString(useCases);
                
                // Calculate content hash for integrity
                String contentHash = calculateContentHash(requirementsJson, standardsJson, normsJson, useCasesJson);
                
                // Save snapshot
                AssessmentContentSnapshot snapshot = new AssessmentContentSnapshot(assessment, release);
                snapshot.setRequirementsSnapshot(requirementsJson);
                snapshot.setStandardsSnapshot(standardsJson);
                snapshot.setNormsSnapshot(normsJson);
                snapshot.setUsecasesSnapshot(useCasesJson);
                snapshot.setSnapshotHash(contentHash);
                
                em.persist(snapshot);
                
                // Mark assessment as having snapshot
                assessment.setContentSnapshotTaken(true);
                em.merge(assessment);
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to create content snapshot", e);
            }
            return null;
        });
    }
    
    private void createReleaseSnapshot(Release release) {
        // Link all current entities to this release
        linkCurrentEntitiesToRelease(release);
        
        // Archive previous versions to history tables
        archiveEntitiesForRelease(release);
    }
    
    
    private void linkCurrentEntitiesToRelease(Release release) {
        jpaApi.withTransaction(em -> {
            // Link current requirements
            em.createQuery("UPDATE Requirement r SET r.release = :release WHERE r.isCurrent = true")
                .setParameter("release", release)
                .executeUpdate();
                
            // Link current standards
            em.createQuery("UPDATE Standard s SET s.release = :release WHERE s.isCurrent = true")
                .setParameter("release", release)
                .executeUpdate();
                
            // Link current norms
            em.createQuery("UPDATE Norm n SET n.release = :release WHERE n.isCurrent = true")
                .setParameter("release", release)
                .executeUpdate();
                
            // Link current use cases
            em.createQuery("UPDATE UseCase uc SET uc.release = :release WHERE uc.isCurrent = true")
                .setParameter("release", release)
                .executeUpdate();
            return null;
        });
    }
    
    
    private void archiveEntitiesForRelease(Release release) {
        jpaApi.withTransaction(em -> {
            // Archive requirements
            em.createNativeQuery(
                "INSERT INTO requirements_history (original_id, release_id, version_number, shortreq, description, language, example, motivation, usecase, norm, chapter, created_at) " +
                "SELECT id, :releaseId, version_number, shortreq, description, language, example, motivation, usecase, norm, chapter, created_at " +
                "FROM requirement WHERE release_id = :releaseId"
            )
            .setParameter("releaseId", release.getId())
            .executeUpdate();
            
            // Archive standards
            em.createNativeQuery(
                "INSERT INTO standards_history (original_id, release_id, version_number, name, description, created_at) " +
                "SELECT id, :releaseId, version_number, name, description, created_at " +
                "FROM standard WHERE release_id = :releaseId"
            )
            .setParameter("releaseId", release.getId())
            .executeUpdate();
            
            // Archive norms
            em.createNativeQuery(
                "INSERT INTO norms_history (original_id, release_id, version_number, name, version, year, created_at) " +
                "SELECT id, :releaseId, version_number, name, version, year, created_at " +
                "FROM norm WHERE release_id = :releaseId"
            )
            .setParameter("releaseId", release.getId())
            .executeUpdate();
            
            // Archive use cases
            em.createNativeQuery(
                "INSERT INTO usecases_history (original_id, release_id, version_number, name, created_at) " +
                "SELECT id, :releaseId, version_number, name, created_at " +
                "FROM usecase WHERE release_id = :releaseId"
            )
            .setParameter("releaseId", release.getId())
            .executeUpdate();
            return null;
        });
    }
    
    
    public List<Requirement> getRequirementsForRelease(Long releaseId) {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) ->
            em.createQuery("SELECT r FROM Requirement r WHERE r.release.id = :releaseId AND r.isCurrent = true ORDER BY r.chapter, r.shortreq", Requirement.class)
                .setParameter("releaseId", releaseId)
                .getResultList()
        );
    }
    
    
    public List<Standard> getStandardsForRelease(Long releaseId) {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) ->
            em.createQuery("SELECT s FROM Standard s WHERE s.release.id = :releaseId AND s.isCurrent = true ORDER BY s.name", Standard.class)
                .setParameter("releaseId", releaseId)
                .getResultList()
        );
    }
    
    
    public List<Norm> getNormsForRelease(Long releaseId) {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) ->
            em.createQuery("SELECT n FROM Norm n WHERE n.release.id = :releaseId AND n.isCurrent = true ORDER BY n.name, n.version", Norm.class)
                .setParameter("releaseId", releaseId)
                .getResultList()
        );
    }
    
    
    public List<UseCase> getUseCasesForRelease(Long releaseId) {
        return jpaApi.withTransaction((jakarta.persistence.EntityManager em) ->
            em.createQuery("SELECT uc FROM UseCase uc WHERE uc.release.id = :releaseId AND uc.isCurrent = true ORDER BY uc.name", UseCase.class)
                .setParameter("releaseId", releaseId)
                .getResultList()
        );
    }
    
    private String calculateContentHash(String... content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String str : content) {
                digest.update(str.getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate content hash", e);
        }
    }
}