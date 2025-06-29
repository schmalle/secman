package controllers;

import actions.Secured;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import models.Requirement;
import models.UseCase; // Added import for UseCase
import models.Norm; // Added import for Norm
import models.User; // Added import for User
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;
import services.TranslationService; // Added import for TranslationService
import java.util.Set; // Added import for Set
import java.util.HashSet; // Added import for HashSet
import java.util.stream.Collectors; // Added import for Collectors

// Imports for Word document generation
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable; // Keep for now, might remove if not used
import org.apache.poi.xwpf.usermodel.XWPFTableRow; // Keep for now, might remove if not used

// Imports for Excel document generation
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTString;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabStop;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabJc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

//@With(Secured.class) // Apply to the whole controller
@Singleton
public class RequirementController extends Controller {

    private final JPAApi jpaApi;
    private final TranslationService translationService;
    private static final play.Logger.ALogger logger = play.Logger.of(RequirementController.class);

    @Inject
    public RequirementController(JPAApi jpaApi, TranslationService translationService) {
        this.jpaApi = jpaApi;
        this.translationService = translationService;
    }

    public Result list(Http.Request request) {
        return jpaApi.withTransaction(em -> {
            TypedQuery<Requirement> query = em.createQuery("SELECT r FROM Requirement r ORDER BY r.id", Requirement.class);
            List<Requirement> requirements = query.getResultList();
            return ok(Json.toJson(requirements));
        });
    }

    public Result create(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting JSON data"));
        }
        
        try {
            Requirement requirement = Json.fromJson(json, Requirement.class);
            return jpaApi.withTransaction(em -> {
                em.persist(requirement);
                return created(Json.toJson(requirement));
            });
        } catch (Exception e) {
            return badRequest(Json.newObject().put("error", "Error processing request: " + e.getMessage()));
        }
    }
    
    public Result get(Http.Request request, Long id) {
        return jpaApi.withTransaction(em -> {
            Optional<Requirement> requirement = Optional.ofNullable(em.find(Requirement.class, id));
            return requirement.map(r -> ok(Json.toJson(r)))
                    .orElse(notFound(Json.newObject().put("error", "Requirement not found")));
        });
    }
    
    public Result update(Http.Request request, Long id) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting JSON data"));
        }

        try {
            return jpaApi.withTransaction(em -> {
                Optional<Requirement> optionalRequirement = Optional.ofNullable(em.find(Requirement.class, id));
                if (!optionalRequirement.isPresent()) {
                    return notFound(Json.newObject().put("error", "Requirement not found"));
                }

                Requirement existingRequirement = optionalRequirement.get();
                
                // Update scalar fields directly from JSON
                if (json.has("shortreq")) {
                    existingRequirement.setShortreq(json.get("shortreq").asText(null));
                }
                if (json.has("details")) {
                    existingRequirement.setDetails(json.get("details").asText(null));
                }
                if (json.has("language")) {
                    existingRequirement.setLanguage(json.get("language").asText(null));
                }
                if (json.has("example")) {
                    existingRequirement.setExample(json.get("example").asText(null));
                }
                if (json.has("motivation")) {
                    existingRequirement.setMotivation(json.get("motivation").asText(null));
                }


                // Handle UseCases update
                if (json.has("usecases")) {
                    Set<UseCase> newUseCases = new HashSet<>();
                    JsonNode useCasesNode = json.get("usecases");
                    if (useCasesNode.isArray()) {
                        for (JsonNode ucNode : useCasesNode) {
                            if (ucNode.has("id")) {
                                Long ucId = ucNode.get("id").asLong();
                                UseCase uc = em.find(UseCase.class, ucId);
                                if (uc != null) {
                                    newUseCases.add(uc);
                                } else {
                                    play.Logger.warn("UseCase with id " + ucId + " not found during requirement update.");
                                }
                            }
                        }
                    }
                    existingRequirement.setUsecases(newUseCases);
                } // If "usecases" is not in json, existing usecases remain untouched.
                  // To clear usecases, an empty array should be sent: "usecases": []

                // Handle Norms update
                if (json.has("norms")) {
                    Set<Norm> newNorms = new HashSet<>();
                    JsonNode normsNode = json.get("norms");
                    if (normsNode.isArray()) {
                        for (JsonNode normNode : normsNode) {
                            if (normNode.has("id")) {
                                Long normId = normNode.get("id").asLong();
                                Norm norm = em.find(Norm.class, normId);
                                if (norm != null) {
                                    newNorms.add(norm);
                                } else {
                                    play.Logger.warn("Norm with id " + normId + " not found during requirement update.");
                                }
                            }
                        }
                    }
                    existingRequirement.setNorms(newNorms);
                } // If "norms" is not in json, existing norms remain untouched.
                  // To clear norms, an empty array should be sent: "norms": []

                em.merge(existingRequirement);
                return ok(Json.toJson(existingRequirement));
            });
        } catch (Exception e) {
            play.Logger.error("Error updating requirement: " + id, e); 
            return badRequest(Json.newObject().put("error", "Error processing request: " + e.getMessage()));
        }
    }
    
    public Result delete(Http.Request request, Long id) {
        return jpaApi.withTransaction(em -> {
            Optional<Requirement> optionalRequirement = Optional.ofNullable(em.find(Requirement.class, id));
            if (!optionalRequirement.isPresent()) {
                return notFound(Json.newObject().put("error", "Requirement not found"));
            }

            try {
                em.remove(optionalRequirement.get());
                return ok(Json.newObject().put("message", "Requirement deleted"));
            } catch (Exception e) {
                return internalServerError(Json.newObject().put("error", "Error deleting requirement: " + e.getMessage()));
            }
        });
    }

    public Result deleteAllRequirements(Http.Request request) {
        logger.info("=== DELETE ALL REQUIREMENTS REQUEST RECEIVED ===");
        logger.info("Request method: {}", request.method());
        logger.info("Request URI: {}", request.uri());
        
        // Get current user (should be admin)
        User currentUser = null;
        try {
            currentUser = getCurrentUser(request);
            logger.info("Current user retrieved: {}", currentUser != null ? currentUser.getUsername() : "null");
        } catch (Exception e) {
            logger.error("Error getting current user", e);
            return internalServerError(Json.newObject()
                .put("error", "Authentication error: " + e.getMessage()));
        }
        
        if (currentUser == null) {
            logger.warn("No current user found in session");
            return forbidden(Json.newObject()
                .put("error", "Admin access required - not logged in"));
        }
        
        if (!currentUser.hasRole("ADMIN")) {
            logger.warn("User {} does not have ADMIN role. Roles: {}", 
                currentUser.getUsername(), currentUser.getRoles());
            return forbidden(Json.newObject()
                .put("error", "Admin access required - insufficient privileges"));
        }
        
        logger.info("Admin user {} authorized for delete operation", currentUser.getUsername());

        return jpaApi.withTransaction(em -> {
            try {
                // First, get count of requirements to be deleted for response
                TypedQuery<Long> countQuery = em.createQuery("SELECT COUNT(r) FROM Requirement r", Long.class);
                Long count = countQuery.getSingleResult();

                if (count == 0) {
                    return ok(Json.newObject()
                        .put("message", "No requirements found to delete")
                        .put("deletedCount", 0));
                }

                logger.info("Starting deletion of {} requirements with all related data", count);

                // Clear all related tables in the correct order to avoid foreign key constraint violations
                
                // 1. Clear assessment content snapshots first (references requirements indirectly)
                try {
                    em.createNativeQuery("DELETE FROM assessment_content_snapshots").executeUpdate();
                    logger.info("Cleared assessment_content_snapshots table");
                } catch (Exception e) {
                    logger.warn("Could not clear assessment_content_snapshots table", e);
                }
                
                // 2. Clear version management tables (no FK constraints but contain requirement references)
                try {
                    em.createNativeQuery("DELETE FROM requirement_usecase_versions").executeUpdate();
                    logger.info("Cleared requirement_usecase_versions table");
                } catch (Exception e) {
                    logger.warn("Could not clear requirement_usecase_versions table", e);
                }
                
                try {
                    em.createNativeQuery("DELETE FROM requirement_standard_versions").executeUpdate();
                    logger.info("Cleared requirement_standard_versions table");
                } catch (Exception e) {
                    logger.warn("Could not clear requirement_standard_versions table", e);
                }
                
                // 3. Clear change tracking table (has FK to requirement without CASCADE)
                try {
                    em.createNativeQuery("DELETE FROM standard_requirement_changes").executeUpdate();
                    logger.info("Cleared standard_requirement_changes table");
                } catch (Exception e) {
                    logger.warn("Could not clear standard_requirement_changes table", e);
                }
                
                // 4. Clear requirements history table (references original_id which is requirement.id)
                try {
                    em.createNativeQuery("DELETE FROM requirements_history").executeUpdate();
                    logger.info("Cleared requirements_history table");
                } catch (Exception e) {
                    logger.warn("Could not clear requirements_history table", e);
                }
                
                // 5. Clear responses (has CASCADE DELETE to requirement but explicit is safer)
                try {
                    em.createNativeQuery("DELETE FROM response").executeUpdate();
                    logger.info("Cleared response table");
                } catch (Exception e) {
                    logger.warn("Could not clear response table", e);
                }
                
                // 6. Clear all many-to-many relationship tables (have CASCADE DELETE but explicit is cleaner)
                try {
                    em.createNativeQuery("DELETE FROM requirement_usecase").executeUpdate();
                    logger.info("Cleared requirement_usecase table");
                } catch (Exception e) {
                    logger.warn("Could not clear requirement_usecase table", e);
                }
                
                try {
                    em.createNativeQuery("DELETE FROM requirement_standard").executeUpdate();
                    logger.info("Cleared requirement_standard table");
                } catch (Exception e) {
                    logger.warn("Could not clear requirement_standard table", e);
                }
                
                // 7. Finally delete all requirements
                int deletedCount = em.createQuery("DELETE FROM Requirement r").executeUpdate();
                logger.info("Successfully deleted {} requirements", deletedCount);
                
                return ok(Json.newObject()
                    .put("message", "All requirements deleted successfully")
                    .put("deletedCount", deletedCount));

            } catch (Exception e) {
                logger.error("Error deleting all requirements", e);
                return internalServerError(Json.newObject()
                    .put("error", "Error deleting all requirements: " + e.getMessage()));
            }
        });
    }

    public Result exportToDocx(Http.Request request) {
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<Requirement> query = em.createQuery(
                    "SELECT r FROM Requirement r ORDER BY r.chapter, r.id", Requirement.class
                );
                List<Requirement> requirements = query.getResultList();

                XWPFDocument document = generateWordDocument(requirements, "Requirements Export");

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                document.write(baos);
                document.close();

                return ok(baos.toByteArray())
                    .as("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    .withHeader("Content-Disposition", "attachment; filename=requirements_export.docx");

            } catch (IOException e) {
                logger.error("Error generating Word document for requirements export", e);
                return internalServerError(Json.newObject().put("error", "Could not generate Word document: " + e.getMessage()));
            } catch (Exception e) {
                logger.error("Unexpected error during requirements export to Word", e);
                return internalServerError(Json.newObject().put("error", "An unexpected error occurred during export: " + e.getMessage()));
            }
        });
    }

    public Result exportToDocxByUseCase(Http.Request request, Long usecaseId) {
        return jpaApi.withTransaction(em -> {
            try {
                // First, check if the use case exists
                UseCase useCase = em.find(UseCase.class, usecaseId);
                if (useCase == null) {
                    return notFound(Json.newObject().put("error", "Use case not found"));
                }

                // Query requirements that are associated with this specific use case
                TypedQuery<Requirement> query = em.createQuery(
                    "SELECT DISTINCT r FROM Requirement r JOIN r.usecases uc WHERE uc.id = :usecaseId ORDER BY r.chapter, r.id", 
                    Requirement.class
                );
                query.setParameter("usecaseId", usecaseId);
                List<Requirement> requirements = query.getResultList();

                if (requirements.isEmpty()) {
                    return badRequest(Json.newObject().put("error", "No requirements found for the specified use case"));
                }

                XWPFDocument document = generateWordDocument(requirements, "Requirements for Use Case: " + useCase.getName());

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                document.write(baos);
                document.close();

                String filename = "requirements_usecase_" + useCase.getName().replaceAll("[^a-zA-Z0-9]", "_") + ".docx";
                return ok(baos.toByteArray())
                    .as("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    .withHeader("Content-Disposition", "attachment; filename=" + filename);

            } catch (IOException e) {
                logger.error("Error generating Word document for requirements export by use case", e);
                return internalServerError(Json.newObject().put("error", "Could not generate Word document: " + e.getMessage()));
            } catch (Exception e) {
                logger.error("Unexpected error during requirements export to Word by use case", e);
                return internalServerError(Json.newObject().put("error", "An unexpected error occurred during export: " + e.getMessage()));
            }
        });
    }

    public Result exportToDocxWithTranslation(Http.Request request, String language) {
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<Requirement> query = em.createQuery(
                    "SELECT r FROM Requirement r ORDER BY r.chapter, r.id", Requirement.class
                );
                List<Requirement> requirements = query.getResultList();

                XWPFDocument document = generateWordDocumentWithTranslation(requirements, "Requirements Export", language);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                document.write(baos);
                document.close();

                String filename = "requirements_export_" + language + ".docx";
                return ok(baos.toByteArray())
                    .as("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    .withHeader("Content-Disposition", "attachment; filename=" + filename);

            } catch (IOException e) {
                logger.error("Error generating translated Word document for requirements export", e);
                return internalServerError(Json.newObject().put("error", "Could not generate Word document: " + e.getMessage()));
            } catch (Exception e) {
                logger.error("Unexpected error during translated requirements export to Word", e);
                return internalServerError(Json.newObject().put("error", "An unexpected error occurred during export: " + e.getMessage()));
            }
        });
    }

    public Result exportToDocxByUseCaseWithTranslation(Http.Request request, Long usecaseId, String language) {
        return jpaApi.withTransaction(em -> {
            try {
                // First, check if the use case exists
                UseCase useCase = em.find(UseCase.class, usecaseId);
                if (useCase == null) {
                    return notFound(Json.newObject().put("error", "Use case not found"));
                }

                // Query requirements that are associated with this specific use case
                TypedQuery<Requirement> query = em.createQuery(
                    "SELECT DISTINCT r FROM Requirement r JOIN r.usecases uc WHERE uc.id = :usecaseId ORDER BY r.chapter, r.id", 
                    Requirement.class
                );
                query.setParameter("usecaseId", usecaseId);
                List<Requirement> requirements = query.getResultList();

                if (requirements.isEmpty()) {
                    return badRequest(Json.newObject().put("error", "No requirements found for the specified use case"));
                }

                XWPFDocument document = generateWordDocumentWithTranslation(requirements, "Requirements for Use Case: " + useCase.getName(), language);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                document.write(baos);
                document.close();

                String filename = "requirements_usecase_" + useCase.getName().replaceAll("[^a-zA-Z0-9]", "_") + "_" + language + ".docx";
                return ok(baos.toByteArray())
                    .as("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    .withHeader("Content-Disposition", "attachment; filename=" + filename);

            } catch (IOException e) {
                logger.error("Error generating translated Word document for requirements export by use case", e);
                return internalServerError(Json.newObject().put("error", "Could not generate Word document: " + e.getMessage()));
            } catch (Exception e) {
                logger.error("Unexpected error during translated requirements export to Word by use case", e);
                return internalServerError(Json.newObject().put("error", "An unexpected error occurred during export: " + e.getMessage()));
            }
        });
    }

    public Result exportToExcel(Http.Request request) {
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<Requirement> query = em.createQuery(
                    "SELECT r FROM Requirement r ORDER BY r.chapter, r.id", Requirement.class
                );
                List<Requirement> requirements = query.getResultList();

                XSSFWorkbook workbook = generateExcelDocument(requirements);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                workbook.write(baos);
                workbook.close();

                return ok(baos.toByteArray())
                    .as("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .withHeader("Content-Disposition", "attachment; filename=requirements_export.xlsx");

            } catch (IOException e) {
                logger.error("Error generating Excel document for requirements export", e);
                return internalServerError(Json.newObject().put("error", "Could not generate Excel document: " + e.getMessage()));
            } catch (Exception e) {
                logger.error("Unexpected error during requirements export to Excel", e);
                return internalServerError(Json.newObject().put("error", "An unexpected error occurred during export: " + e.getMessage()));
            }
        });
    }

    public Result exportToExcelByUseCase(Http.Request request, Long usecaseId) {
        return jpaApi.withTransaction(em -> {
            try {
                // First, check if the use case exists
                UseCase useCase = em.find(UseCase.class, usecaseId);
                if (useCase == null) {
                    return notFound(Json.newObject().put("error", "Use case not found"));
                }

                // Query requirements that are associated with this specific use case
                TypedQuery<Requirement> query = em.createQuery(
                    "SELECT DISTINCT r FROM Requirement r JOIN r.usecases uc WHERE uc.id = :usecaseId ORDER BY r.chapter, r.id", 
                    Requirement.class
                );
                query.setParameter("usecaseId", usecaseId);
                List<Requirement> requirements = query.getResultList();

                if (requirements.isEmpty()) {
                    return badRequest(Json.newObject().put("error", "No requirements found for the specified use case"));
                }

                XSSFWorkbook workbook = generateExcelDocument(requirements);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                workbook.write(baos);
                workbook.close();

                String filename = "requirements_usecase_" + useCase.getName().replaceAll("[^a-zA-Z0-9]", "_") + ".xlsx";
                return ok(baos.toByteArray())
                    .as("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .withHeader("Content-Disposition", "attachment; filename=" + filename);

            } catch (IOException e) {
                logger.error("Error generating Excel document for requirements export by use case", e);
                return internalServerError(Json.newObject().put("error", "Could not generate Excel document: " + e.getMessage()));
            } catch (Exception e) {
                logger.error("Unexpected error during requirements export to Excel by use case", e);
                return internalServerError(Json.newObject().put("error", "An unexpected error occurred during export: " + e.getMessage()));
            }
        });
    }

    private XSSFWorkbook generateExcelDocument(List<Requirement> requirements) throws IOException {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Reqs"); // Must match the import sheet name

        // Create header row with the exact same column names as used in import
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Chapter", "Norm", "Short req", "DetailsEN", "MotivationEN", "ExampleEN", "UseCase"};
        
        // Create header style
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Fill data rows
        int rowNum = 1;
        for (Requirement req : requirements) {
            Row row = sheet.createRow(rowNum++);
            
            // Chapter
            Cell chapterCell = row.createCell(0);
            chapterCell.setCellValue(req.getChapter() != null ? req.getChapter() : "");
            
            // Norm
            Cell normCell = row.createCell(1);
            normCell.setCellValue(req.getNorm() != null ? req.getNorm() : "");
            
            // Short req (required field)
            Cell shortReqCell = row.createCell(2);
            shortReqCell.setCellValue(req.getShortreq() != null ? req.getShortreq() : "");
            
            // DetailsEN
            Cell detailsCell = row.createCell(3);
            detailsCell.setCellValue(req.getDetails() != null ? req.getDetails() : "");
            
            // MotivationEN
            Cell motivationCell = row.createCell(4);
            motivationCell.setCellValue(req.getMotivation() != null ? req.getMotivation() : "");
            
            // ExampleEN
            Cell exampleCell = row.createCell(5);
            exampleCell.setCellValue(req.getExample() != null ? req.getExample() : "");
            
            // UseCase - comma-separated list of use case names
            Cell useCaseCell = row.createCell(6);
            if (req.getUsecases() != null && !req.getUsecases().isEmpty()) {
                String useCaseNames = req.getUsecases().stream()
                    .map(UseCase::getName)
                    .collect(Collectors.joining(", "));
                useCaseCell.setCellValue(useCaseNames);
            } else {
                useCaseCell.setCellValue("");
            }
        }

        // Auto-size columns for better readability
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            // Set minimum width to ensure readability
            if (sheet.getColumnWidth(i) < 2000) {
                sheet.setColumnWidth(i, 2000);
            }
        }

        return workbook;
    }

    // Refactored to extract common Word document generation logic
    private XWPFDocument generateWordDocument(List<Requirement> requirements, String titleText) throws IOException {
        XWPFDocument document = new XWPFDocument();

        // 1. Title Page / Header Information
        XWPFParagraph title = document.createParagraph();
        title.setStyle("Title");
        XWPFRun titleRun = title.createRun();
        titleRun.setText(titleText);
        titleRun.setBold(true);
        titleRun.setFontSize(24);

        XWPFParagraph subtitle = document.createParagraph();
        XWPFRun subtitleRun = subtitle.createRun();
        subtitleRun.setText("Generated on: " + java.time.LocalDate.now().toString());
        subtitleRun.setFontSize(14);
        subtitle.createRun().addBreak(); // Blank paragraph for spacing

        // 2. Table of Contents Placeholder
        XWPFParagraph tocHeader = document.createParagraph();
        XWPFRun tocHeaderRun = tocHeader.createRun();
        tocHeaderRun.setText("Table of Contents");
        tocHeaderRun.setBold(true);
        tocHeaderRun.setFontSize(18);
        addTableOfContents(document);
        document.createParagraph().setPageBreak(true); // Start chapters on a new page

        // 3. Requirements by Chapter
        Map<String, List<Requirement>> requirementsByChapter = requirements.stream()
            .sorted(Comparator.comparing(Requirement::getChapter, Comparator.nullsLast(String::compareTo))
                              .thenComparing(Requirement::getId, Comparator.nullsLast(Long::compareTo)))
            .collect(Collectors.groupingBy(req -> req.getChapter() != null ? req.getChapter() : "Uncategorized", LinkedHashMap::new, Collectors.toList()));

        // Add sequential numbering counter
        int requirementCounter = 1;

        for (Map.Entry<String, List<Requirement>> entry : requirementsByChapter.entrySet()) {
            String chapterName = entry.getKey();
            List<Requirement> chapterRequirements = entry.getValue();

            // Chapter Heading
            XWPFParagraph chapterParagraph = document.createParagraph();
            chapterParagraph.setStyle("Heading1");
            if (chapterParagraph.getCTP().getPPr() == null) {
                chapterParagraph.getCTP().addNewPPr();
            }
            if (chapterParagraph.getCTP().getPPr().getOutlineLvl() == null) {
                chapterParagraph.getCTP().getPPr().addNewOutlineLvl();
            }
            chapterParagraph.getCTP().getPPr().getOutlineLvl().setVal(java.math.BigInteger.valueOf(0));

            XWPFRun chapterRun = chapterParagraph.createRun();
            chapterRun.setText(chapterName != null ? chapterName : "Uncategorized Requirements");
            chapterRun.setBold(true);
            chapterRun.setFontSize(16);

            for (Requirement req : chapterRequirements) {
                // Requirement Sub-Heading
                XWPFParagraph reqSubHeading = document.createParagraph();
                if (reqSubHeading.getCTP().getPPr() == null) {
                    reqSubHeading.getCTP().addNewPPr();
                }
                if (reqSubHeading.getCTP().getPPr().getShd() == null) {
                    reqSubHeading.getCTP().getPPr().addNewShd();
                }
                reqSubHeading.getCTP().getPPr().getShd().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd.CLEAR);
                reqSubHeading.getCTP().getPPr().getShd().setColor("auto");
                reqSubHeading.getCTP().getPPr().getShd().setFill("ADD8E6"); // Light Blue hex color

                reqSubHeading.setStyle("Heading2");
                XWPFRun reqSubHeadingRun = reqSubHeading.createRun();
                String shortReqText = req.getShortreq() != null ? req.getShortreq() : "";
                reqSubHeadingRun.setText("Requirement " + requirementCounter + ": " + shortReqText);
                reqSubHeadingRun.setBold(true);
                reqSubHeadingRun.setFontSize(14);

                requirementCounter++;

                // Details
                if (req.getDetails() != null && !req.getDetails().isEmpty()) {
                    XWPFParagraph detailsPara = document.createParagraph();
                    detailsPara.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH);
                    String detailsText = req.getDetails();
                    String[] detailLines = detailsText.split("\\\\r?\\\\n");
                    for (int i = 0; i < detailLines.length; i++) {
                        XWPFRun run = detailsPara.createRun();
                        run.setText(detailLines[i]);
                        if (i < detailLines.length - 1) {
                            run.addBreak();
                        }
                    }
                }

                // Motivation
                if (req.getMotivation() != null && !req.getMotivation().isEmpty()) {
                    XWPFParagraph motivationPara = document.createParagraph();
                    motivationPara.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH);
                    XWPFRun motivationLabelRun = motivationPara.createRun();
                    motivationLabelRun.setBold(true);
                    motivationLabelRun.setText("Motivation: ");

                    String motivationText = req.getMotivation();
                    String[] motivationLines = motivationText.split("\\\\r?\\\\n");
                    for (int i = 0; i < motivationLines.length; i++) {
                        XWPFRun run = motivationPara.createRun();
                        run.setText(motivationLines[i]);
                        if (i < motivationLines.length - 1) {
                            run.addBreak();
                        }
                    }
                }

                // Example
                if (req.getExample() != null && !req.getExample().isEmpty()) {
                    XWPFParagraph examplePara = document.createParagraph();
                    examplePara.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH);
                    XWPFRun exampleLabelRun = examplePara.createRun();
                    exampleLabelRun.setBold(true);
                    exampleLabelRun.setText("Example: ");

                    String exampleText = req.getExample();
                    String[] exampleLines = exampleText.split("\\\\r?\\\\n");
                    for (int i = 0; i < exampleLines.length; i++) {
                        XWPFRun run = examplePara.createRun();
                        run.setText(exampleLines[i]);
                        if (i < exampleLines.length - 1) {
                            run.addBreak();
                        }
                    }
                }

                // Norm Reference
                if (req.getNorm() != null && !req.getNorm().isEmpty()) {
                    XWPFParagraph normPara = document.createParagraph();
                    XWPFRun normRun = normPara.createRun();
                    normRun.setBold(true);
                    normRun.setText("Norm: ");
                    normRun.setFontSize(8);
                    XWPFRun normValueRun = normPara.createRun();
                    normValueRun.setText(req.getNorm());
                    normValueRun.setFontSize(8);
                }
                
                document.createParagraph().createRun().addBreak(); // Space between requirements
            }
        }

        return document;
    }

    // Translation-enabled Word document generation
    private XWPFDocument generateWordDocumentWithTranslation(List<Requirement> requirements, String titleText, String targetLanguage) throws IOException {
        XWPFDocument document = new XWPFDocument();

        // Skip translation if target language is English (source language)
        if ("english".equalsIgnoreCase(targetLanguage) || "en".equalsIgnoreCase(targetLanguage)) {
            return generateWordDocument(requirements, titleText);
        }

        // 1. Title Page / Header Information
        XWPFParagraph title = document.createParagraph();
        title.setStyle("Title");
        XWPFRun titleRun = title.createRun();
        
        // Translate title
        String translatedTitle = translateText(titleText, targetLanguage);
        titleRun.setText(translatedTitle);
        titleRun.setBold(true);
        titleRun.setFontSize(24);

        XWPFParagraph subtitle = document.createParagraph();
        XWPFRun subtitleRun = subtitle.createRun();
        String generatedOnText = "Generated on: " + java.time.LocalDate.now().toString();
        String translatedGeneratedOn = translateText(generatedOnText, targetLanguage);
        subtitleRun.setText(translatedGeneratedOn);
        subtitleRun.setFontSize(14);
        subtitle.createRun().addBreak();

        // 2. Table of Contents Placeholder
        XWPFParagraph tocHeader = document.createParagraph();
        XWPFRun tocHeaderRun = tocHeader.createRun();
        String tocText = "Table of Contents";
        String translatedToc = translateText(tocText, targetLanguage);
        tocHeaderRun.setText(translatedToc);
        tocHeaderRun.setBold(true);
        tocHeaderRun.setFontSize(18);
        addTableOfContents(document);
        document.createParagraph().setPageBreak(true);

        // 3. Requirements by Chapter
        Map<String, List<Requirement>> requirementsByChapter = requirements.stream()
            .sorted(Comparator.comparing(Requirement::getChapter, Comparator.nullsLast(String::compareTo))
                              .thenComparing(Requirement::getId, Comparator.nullsLast(Long::compareTo)))
            .collect(Collectors.groupingBy(req -> req.getChapter() != null ? req.getChapter() : "Uncategorized", LinkedHashMap::new, Collectors.toList()));

        int requirementCounter = 1;

        for (Map.Entry<String, List<Requirement>> entry : requirementsByChapter.entrySet()) {
            String chapterName = entry.getKey();
            List<Requirement> chapterRequirements = entry.getValue();

            // Chapter Heading
            XWPFParagraph chapterParagraph = document.createParagraph();
            chapterParagraph.setStyle("Heading1");
            if (chapterParagraph.getCTP().getPPr() == null) {
                chapterParagraph.getCTP().addNewPPr();
            }
            if (chapterParagraph.getCTP().getPPr().getOutlineLvl() == null) {
                chapterParagraph.getCTP().getPPr().addNewOutlineLvl();
            }
            chapterParagraph.getCTP().getPPr().getOutlineLvl().setVal(java.math.BigInteger.valueOf(0));

            XWPFRun chapterRun = chapterParagraph.createRun();
            String translatedChapterName = chapterName != null ? translateText(chapterName, targetLanguage) : translateText("Uncategorized Requirements", targetLanguage);
            chapterRun.setText(translatedChapterName);
            chapterRun.setBold(true);
            chapterRun.setFontSize(16);

            for (Requirement req : chapterRequirements) {
                // Requirement Sub-Heading
                XWPFParagraph reqSubHeading = document.createParagraph();
                if (reqSubHeading.getCTP().getPPr() == null) {
                    reqSubHeading.getCTP().addNewPPr();
                }
                if (reqSubHeading.getCTP().getPPr().getShd() == null) {
                    reqSubHeading.getCTP().getPPr().addNewShd();
                }
                reqSubHeading.getCTP().getPPr().getShd().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd.CLEAR);
                reqSubHeading.getCTP().getPPr().getShd().setColor("auto");
                reqSubHeading.getCTP().getPPr().getShd().setFill("ADD8E6");

                reqSubHeading.setStyle("Heading2");
                XWPFRun reqSubHeadingRun = reqSubHeading.createRun();
                String shortReqText = req.getShortreq() != null ? req.getShortreq() : "";
                String translatedShortReq = translateText(shortReqText, targetLanguage);
                String requirementLabel = translateText("Requirement", targetLanguage);
                reqSubHeadingRun.setText(requirementLabel + " " + requirementCounter + ": " + translatedShortReq);
                reqSubHeadingRun.setBold(true);
                reqSubHeadingRun.setFontSize(14);

                requirementCounter++;

                // Details
                if (req.getDetails() != null && !req.getDetails().isEmpty()) {
                    XWPFParagraph detailsPara = document.createParagraph();
                    detailsPara.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH);
                    String detailsText = req.getDetails();
                    String translatedDetails = translateText(detailsText, targetLanguage);
                    String[] detailLines = translatedDetails.split("\\\\r?\\\\n");
                    for (int i = 0; i < detailLines.length; i++) {
                        XWPFRun run = detailsPara.createRun();
                        run.setText(detailLines[i]);
                        if (i < detailLines.length - 1) {
                            run.addBreak();
                        }
                    }
                }

                // Motivation
                if (req.getMotivation() != null && !req.getMotivation().isEmpty()) {
                    XWPFParagraph motivationPara = document.createParagraph();
                    motivationPara.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH);
                    XWPFRun motivationLabelRun = motivationPara.createRun();
                    motivationLabelRun.setBold(true);
                    String motivationLabel = translateText("Motivation", targetLanguage);
                    motivationLabelRun.setText(motivationLabel + ": ");

                    String motivationText = req.getMotivation();
                    String translatedMotivation = translateText(motivationText, targetLanguage);
                    String[] motivationLines = translatedMotivation.split("\\\\r?\\\\n");
                    for (int i = 0; i < motivationLines.length; i++) {
                        XWPFRun run = motivationPara.createRun();
                        run.setText(motivationLines[i]);
                        if (i < motivationLines.length - 1) {
                            run.addBreak();
                        }
                    }
                }

                // Example
                if (req.getExample() != null && !req.getExample().isEmpty()) {
                    XWPFParagraph examplePara = document.createParagraph();
                    examplePara.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH);
                    XWPFRun exampleLabelRun = examplePara.createRun();
                    exampleLabelRun.setBold(true);
                    String exampleLabel = translateText("Example", targetLanguage);
                    exampleLabelRun.setText(exampleLabel + ": ");

                    String exampleText = req.getExample();
                    String translatedExample = translateText(exampleText, targetLanguage);
                    String[] exampleLines = translatedExample.split("\\\\r?\\\\n");
                    for (int i = 0; i < exampleLines.length; i++) {
                        XWPFRun run = examplePara.createRun();
                        run.setText(exampleLines[i]);
                        if (i < exampleLines.length - 1) {
                            run.addBreak();
                        }
                    }
                }

                // Norm Reference
                if (req.getNorm() != null && !req.getNorm().isEmpty()) {
                    XWPFParagraph normPara = document.createParagraph();
                    XWPFRun normRun = normPara.createRun();
                    normRun.setBold(true);
                    String normLabel = translateText("Norm", targetLanguage);
                    normRun.setText(normLabel + ": ");
                    normRun.setFontSize(8);
                    XWPFRun normValueRun = normPara.createRun();
                    normValueRun.setText(req.getNorm()); // Keep norm reference untranslated
                    normValueRun.setFontSize(8);
                }
                
                document.createParagraph().createRun().addBreak();
            }
        }

        return document;
    }

    // Helper method to translate text with fallback
    private String translateText(String text, String targetLanguage) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        
        try {
            String translated = translationService.translateText(text, targetLanguage).toCompletableFuture().join();
            return translated != null && !translated.trim().isEmpty() ? translated : text;
        } catch (Exception e) {
            logger.warn("Translation failed for text: '" + text + "', using original text", e);
            return text;
        }
    }

    // Helper method to add a basic ToC field instruction
    // This tells Word to insert a ToC and update it. Users will likely need to right-click > Update Field.
    private static void addTableOfContents(XWPFDocument document) {
        XWPFParagraph p = document.createParagraph();
        CTP ctP = p.getCTP();
        CTString PStyle = ctP.addNewPPr().addNewPStyle();
        PStyle.setVal("TOC1"); // Style for ToC entries, assuming TOC1, TOC2 etc. are defined in Word or default

        // Add begin field marker
        CTR run = ctP.addNewR();
        run.addNewFldChar().setFldCharType(STFldCharType.BEGIN);

        run = ctP.addNewR();
        run.addNewInstrText().setStringValue(" TOC \\o \"1-2\" \\h \\z \\u "); // ToC for Heading 1 and Heading 2

        // Add separate field marker
        run = ctP.addNewR();
        run.addNewFldChar().setFldCharType(STFldCharType.SEPARATE);

        // Add end field marker
        run = ctP.addNewR();
        run.addNewFldChar().setFldCharType(STFldCharType.END);
    }

    // Helper method to get current user from session
    private User getCurrentUser(Http.Request request) {
        try {
            String username = request.session().get("username").orElse(null);
            if (username != null) {
                return jpaApi.withTransaction(em -> {
                    try {
                        return em.createQuery("SELECT u FROM User u WHERE u.username = :username", User.class)
                            .setParameter("username", username)
                            .getSingleResult();
                    } catch (NoResultException e) {
                        return null;
                    }
                });
            }
        } catch (Exception e) {
            // Log error but don't fail the request
            logger.error("Error getting current user", e);
        }
        return null;
    }
}
