package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Asset;
import models.Risk;
import models.RiskAssessment;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Singleton
public class ReportController extends Controller {

    private final JPAApi jpaApi;
    private final ObjectMapper objectMapper;

    @Inject
    public ReportController(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
        this.objectMapper = new ObjectMapper();
    }

    public Result riskAssessmentSummary() {
        return jpaApi.withTransaction(em -> {
            try {
                // Get all risk assessments with their assets
                TypedQuery<RiskAssessment> assessmentQuery = em.createQuery(
                    "SELECT ra FROM RiskAssessment ra JOIN FETCH ra.asset ORDER BY ra.createdAt DESC", 
                    RiskAssessment.class
                );
                List<RiskAssessment> assessments = assessmentQuery.getResultList();

                // Get all risks with their assets
                TypedQuery<Risk> riskQuery = em.createQuery(
                    "SELECT r FROM Risk r JOIN FETCH r.asset ORDER BY r.createdAt DESC", 
                    Risk.class
                );
                List<Risk> risks = riskQuery.getResultList();

                // Create summary report
                ObjectNode report = Json.newObject();
                
                // Assessment statistics
                ObjectNode assessmentStats = Json.newObject();
                assessmentStats.put("total", assessments.size());
                
                Map<String, Long> statusCounts = assessments.stream()
                    .collect(Collectors.groupingBy(
                        ra -> ra.getStatus() != null ? ra.getStatus() : "UNKNOWN",
                        Collectors.counting()
                    ));
                
                ObjectNode statusBreakdown = Json.newObject();
                statusCounts.forEach((status, count) -> statusBreakdown.put(status, count));
                assessmentStats.set("statusBreakdown", statusBreakdown);
                
                report.set("assessmentSummary", assessmentStats);

                // Risk statistics
                ObjectNode riskStats = Json.newObject();
                riskStats.put("total", risks.size());
                
                Map<String, Long> riskStatusCounts = risks.stream()
                    .collect(Collectors.groupingBy(
                        r -> r.getStatus() != null ? r.getStatus() : "OPEN",
                        Collectors.counting()
                    ));
                
                ObjectNode riskStatusBreakdown = Json.newObject();
                riskStatusCounts.forEach((status, count) -> riskStatusBreakdown.put(status, count));
                riskStats.set("statusBreakdown", riskStatusBreakdown);
                
                Map<Integer, Long> riskLevelCounts = risks.stream()
                    .collect(Collectors.groupingBy(
                        Risk::getRiskLevel,
                        Collectors.counting()
                    ));
                
                ObjectNode riskLevelBreakdown = Json.newObject();
                riskLevelCounts.forEach((level, count) -> riskLevelBreakdown.put(level.toString(), count));
                riskStats.set("riskLevelBreakdown", riskLevelBreakdown);
                
                report.set("riskSummary", riskStats);

                // Asset coverage
                TypedQuery<Long> assetCountQuery = em.createQuery("SELECT COUNT(a) FROM Asset a", Long.class);
                Long totalAssets = assetCountQuery.getSingleResult();
                
                TypedQuery<Long> assetsWithAssessmentsQuery = em.createQuery(
                    "SELECT COUNT(DISTINCT ra.asset) FROM RiskAssessment ra", Long.class
                );
                Long assetsWithAssessments = assetsWithAssessmentsQuery.getSingleResult();
                
                ObjectNode assetCoverage = Json.newObject();
                assetCoverage.put("totalAssets", totalAssets);
                assetCoverage.put("assetsWithAssessments", assetsWithAssessments);
                assetCoverage.put("coveragePercentage", 
                    totalAssets > 0 ? Math.round((assetsWithAssessments * 100.0) / totalAssets) : 0);
                
                report.set("assetCoverage", assetCoverage);

                // Recent assessments
                ArrayNode recentAssessments = Json.newArray();
                assessments.stream().limit(10).forEach(assessment -> {
                    ObjectNode assessmentNode = Json.newObject();
                    assessmentNode.put("id", assessment.getId());
                    assessmentNode.put("assetName", assessment.getAsset().getName());
                    assessmentNode.put("status", assessment.getStatus());
                    assessmentNode.put("assessor", assessment.getAssessor() != null ? assessment.getAssessor().getUsername() : "N/A");
                    assessmentNode.put("startDate", assessment.getStartDate().toString());
                    assessmentNode.put("endDate", assessment.getEndDate().toString());
                    recentAssessments.add(assessmentNode);
                });
                report.set("recentAssessments", recentAssessments);

                // High priority risks
                ArrayNode highPriorityRisks = Json.newArray();
                risks.stream()
                    .filter(risk -> risk.getRiskLevel() >= 3) // High and Very High risks
                    .limit(10)
                    .forEach(risk -> {
                        ObjectNode riskNode = Json.newObject();
                        riskNode.put("id", risk.getId());
                        riskNode.put("name", risk.getName());
                        riskNode.put("assetName", risk.getAsset().getName());
                        riskNode.put("riskLevel", risk.getRiskLevel());
                        riskNode.put("status", risk.getStatus());
                        riskNode.put("owner", risk.getOwner());
                        riskNode.put("severity", risk.getSeverity());
                        if (risk.getDeadline() != null) {
                            riskNode.put("deadline", risk.getDeadline().toString());
                        }
                        highPriorityRisks.add(riskNode);
                    });
                report.set("highPriorityRisks", highPriorityRisks);

                return ok(report);
            } catch (Exception e) {
                return internalServerError(Json.toJson("Error generating report: " + e.getMessage()));
            }
        });
    }

    public Result riskMitigationStatus() {
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<Risk> query = em.createQuery(
                    "SELECT r FROM Risk r JOIN FETCH r.asset WHERE r.status IN ('OPEN', 'IN_PROGRESS') ORDER BY r.riskLevel DESC, r.createdAt DESC", 
                    Risk.class
                );
                List<Risk> openRisks = query.getResultList();

                ObjectNode report = Json.newObject();
                
                // Mitigation status summary
                ObjectNode summary = Json.newObject();
                summary.put("totalOpenRisks", openRisks.size());
                
                long overdue = openRisks.stream()
                    .filter(risk -> risk.getDeadline() != null && 
                                  risk.getDeadline().isBefore(java.time.LocalDate.now()))
                    .count();
                summary.put("overdueRisks", overdue);
                
                long unassigned = openRisks.stream()
                    .filter(risk -> risk.getOwner() == null || risk.getOwner().trim().isEmpty())
                    .count();
                summary.put("unassignedRisks", unassigned);
                
                report.set("summary", summary);

                // Risk details for mitigation tracking
                ArrayNode riskDetails = Json.newArray();
                openRisks.forEach(risk -> {
                    ObjectNode riskNode = Json.newObject();
                    riskNode.put("id", risk.getId());
                    riskNode.put("name", risk.getName());
                    riskNode.put("description", risk.getDescription());
                    riskNode.put("assetName", risk.getAsset().getName());
                    riskNode.put("riskLevel", risk.getRiskLevel());
                    riskNode.put("status", risk.getStatus());
                    riskNode.put("owner", risk.getOwner());
                    riskNode.put("severity", risk.getSeverity());
                    if (risk.getDeadline() != null) {
                        riskNode.put("deadline", risk.getDeadline().toString());
                        riskNode.put("isOverdue", risk.getDeadline().isBefore(java.time.LocalDate.now()));
                    }
                    riskNode.put("likelihood", risk.getLikelihood());
                    riskNode.put("impact", risk.getImpact());
                    riskDetails.add(riskNode);
                });
                report.set("risks", riskDetails);

                return ok(report);
            } catch (Exception e) {
                return internalServerError(Json.toJson("Error generating mitigation report: " + e.getMessage()));
            }
        });
    }

    public Result assetRiskProfile(Long assetId) {
        return jpaApi.withTransaction(em -> {
            try {
                Asset asset = em.find(Asset.class, assetId);
                if (asset == null) {
                    return notFound(Json.toJson("Asset not found"));
                }

                // Get risk assessments for this asset
                TypedQuery<RiskAssessment> assessmentQuery = em.createQuery(
                    "SELECT ra FROM RiskAssessment ra WHERE ra.asset.id = :assetId ORDER BY ra.createdAt DESC", 
                    RiskAssessment.class
                );
                assessmentQuery.setParameter("assetId", assetId);
                List<RiskAssessment> assessments = assessmentQuery.getResultList();

                // Get risks for this asset
                TypedQuery<Risk> riskQuery = em.createQuery(
                    "SELECT r FROM Risk r WHERE r.asset.id = :assetId ORDER BY r.riskLevel DESC, r.createdAt DESC", 
                    Risk.class
                );
                riskQuery.setParameter("assetId", assetId);
                List<Risk> risks = riskQuery.getResultList();

                ObjectNode report = Json.newObject();
                
                // Asset information
                ObjectNode assetInfo = Json.newObject();
                assetInfo.put("id", asset.getId());
                assetInfo.put("name", asset.getName());
                assetInfo.put("type", asset.getType());
                assetInfo.put("owner", asset.getOwner());
                assetInfo.put("ip", asset.getIp());
                assetInfo.put("description", asset.getDescription());
                report.set("asset", assetInfo);

                // Assessment summary
                ObjectNode assessmentSummary = Json.newObject();
                assessmentSummary.put("totalAssessments", assessments.size());
                
                if (!assessments.isEmpty()) {
                    RiskAssessment latest = assessments.get(0);
                    ObjectNode latestAssessment = Json.newObject();
                    latestAssessment.put("id", latest.getId());
                    latestAssessment.put("status", latest.getStatus());
                    latestAssessment.put("assessor", latest.getAssessor() != null ? latest.getAssessor().getUsername() : "N/A");
                    latestAssessment.put("startDate", latest.getStartDate().toString());
                    latestAssessment.put("endDate", latest.getEndDate().toString());
                    // Add scope as use cases
                    ArrayNode scopeArray = Json.newArray();
                    if (latest.getUseCases() != null) {
                        latest.getUseCases().forEach(useCase -> scopeArray.add(useCase.getName()));
                    }
                    latestAssessment.set("scope", scopeArray);
                    assessmentSummary.set("latest", latestAssessment);
                }
                
                report.set("assessmentSummary", assessmentSummary);

                // Risk summary
                ObjectNode riskSummary = Json.newObject();
                riskSummary.put("totalRisks", risks.size());
                
                Map<String, Long> statusCounts = risks.stream()
                    .collect(Collectors.groupingBy(
                        r -> r.getStatus() != null ? r.getStatus() : "OPEN",
                        Collectors.counting()
                    ));
                
                ObjectNode statusBreakdown = Json.newObject();
                statusCounts.forEach((status, count) -> statusBreakdown.put(status, count));
                riskSummary.set("statusBreakdown", statusBreakdown);
                
                Map<Integer, Long> levelCounts = risks.stream()
                    .collect(Collectors.groupingBy(
                        Risk::getRiskLevel,
                        Collectors.counting()
                    ));
                
                ObjectNode levelBreakdown = Json.newObject();
                levelCounts.forEach((level, count) -> levelBreakdown.put(level.toString(), count));
                riskSummary.set("riskLevelBreakdown", levelBreakdown);
                
                report.set("riskSummary", riskSummary);

                // Detailed risk list
                ArrayNode riskDetails = Json.newArray();
                risks.forEach(risk -> {
                    ObjectNode riskNode = Json.newObject();
                    riskNode.put("id", risk.getId());
                    riskNode.put("name", risk.getName());
                    riskNode.put("description", risk.getDescription());
                    riskNode.put("likelihood", risk.getLikelihood());
                    riskNode.put("impact", risk.getImpact());
                    riskNode.put("riskLevel", risk.getRiskLevel());
                    riskNode.put("status", risk.getStatus());
                    riskNode.put("owner", risk.getOwner());
                    riskNode.put("severity", risk.getSeverity());
                    if (risk.getDeadline() != null) {
                        riskNode.put("deadline", risk.getDeadline().toString());
                    }
                    riskDetails.add(riskNode);
                });
                report.set("risks", riskDetails);

                return ok(report);
            } catch (Exception e) {
                return internalServerError(Json.toJson("Error generating asset risk profile: " + e.getMessage()));
            }
        });
    }
}