package controllers;

import models.Requirement;
import models.UseCase; // Added import for UseCase
import models.Norm;
import services.NormParsingService;
import play.db.jpa.JPAApi;
import play.mvc.*;
import play.libs.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.inject.Inject;
import jakarta.persistence.EntityManager; // Added for direct EntityManager use
import jakarta.persistence.NoResultException; // Added for query handling
import jakarta.persistence.TypedQuery; // Added for typed queries
import java.io.InputStream;
import java.util.*;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Path;       // ADDED for Path
import java.nio.file.Files;      // ADDED for Files.newInputStream
import play.libs.Files.TemporaryFile; // ADDED for Play's TemporaryFile

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import actions.Secured; // Import the Secured action
import actions.SecurityAuthenticator; // Import the proper Security.Authenticator

@With(Secured.class) // Use this instead of Security.Authenticated for proper typing
public class ImportController extends Controller {

    private final JPAApi jpaApi;
    private final NormParsingService normParsingService;
    private static final Logger log = LoggerFactory.getLogger(ImportController.class);
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final String REQUIRED_SHEET_NAME = "Reqs";
    // Add "Chapter" to the list of required headers
    private static final List<String> REQUIRED_HEADERS = Arrays.asList(
            "Chapter", "Norm", "Short req", "DetailsEN", "MotivationEN", "ExampleEN", "UseCase"
    );

    @Inject
    public ImportController(JPAApi jpaApi, NormParsingService normParsingService) {
        this.jpaApi = jpaApi;
        this.normParsingService = normParsingService;
    }

    @BodyParser.Of(BodyParser.MultipartFormData.class)
    // We don't use @RequireCSRFCheck here, which effectively disables CSRF for this method
    public Result uploadXlsx(Http.Request request) {
        log.info("Received request to upload XLSX file");
        
        Http.MultipartFormData<TemporaryFile> body = request.body().asMultipartFormData(); // Use TemporaryFile
        Http.MultipartFormData.FilePart<TemporaryFile> filePart = body.getFile("xlsxFile"); // Use TemporaryFile

        if (filePart == null) {
            log.warn("No file found in the request part 'xlsxFile'");
            return badRequest(Json.newObject().put("error", "Missing file part 'xlsxFile'"));
        }

        String fileName = filePart.getFilename();
        long fileSize = filePart.getFileSize();
        
        String contentType = filePart.getContentType();
        if (contentType == null) contentType = "";
        
        TemporaryFile temporaryFile = filePart.getRef(); // This is a TemporaryFile
        Path filePath = temporaryFile.path();            // Get Path from TemporaryFile

        log.info("File received: name={}, size={}, contentType={}", fileName, fileSize, contentType);

        // 1. File Validation
        if (!contentType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") && !fileName.toLowerCase().endsWith(".xlsx")) {
             log.warn("Invalid file type: {}", contentType);
            return badRequest(Json.newObject().put("error", "Invalid file type. Only .xlsx files are allowed."));
        }
        if (fileSize > MAX_FILE_SIZE) {
             log.warn("File size exceeds limit: {}", fileSize);
            return badRequest(Json.newObject().put("error", "File size exceeds the 10MB limit."));
        }
        if (fileSize == 0) {
             log.warn("File is empty");
            return badRequest(Json.newObject().put("error", "File is empty."));
        }

        List<Requirement> requirements = new ArrayList<>();
        try (InputStream fis = Files.newInputStream(filePath); // Use java.nio.file.Files.newInputStream with Path
             Workbook workbook = new XSSFWorkbook(fis)) {

            // 2. Sheet Validation
            Sheet sheet = workbook.getSheet(REQUIRED_SHEET_NAME);
            if (sheet == null) {
                log.warn("Sheet '{}' not found in the workbook.", REQUIRED_SHEET_NAME);
                return badRequest(Json.newObject().put("error", "Sheet '" + REQUIRED_SHEET_NAME + "' not found in the workbook."));
            }

            // 3. Header Validation
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                 log.warn("Header row is missing in sheet '{}'", REQUIRED_SHEET_NAME);
                return badRequest(Json.newObject().put("error", "Header row is missing in sheet '" + REQUIRED_SHEET_NAME + "'."));
            }

            Map<String, Integer> headerMap = getHeaderMap(headerRow);
            for (String requiredHeader : REQUIRED_HEADERS) {
                if (!headerMap.containsKey(requiredHeader.toLowerCase())) { // Case-insensitive check
                    log.warn("Missing required column: {}", requiredHeader);
                    return badRequest(Json.newObject().put("error", "Missing required column: " + requiredHeader));
                }
            }

            // 4. Row Parsing
            int requirementsProcessed = 0;
            List<Requirement> successfullyParsedRequirements = new ArrayList<>(); // Store successfully parsed reqs
            for (int i = 1; i <= sheet.getLastRowNum(); i++) { // Start from row 1 (after header)
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row)) {
                    log.debug("Skipping empty row {}", i + 1);
                    continue; // Skip empty rows
                }

                try {
                    // Parse the row, including the new chapter field
                    Requirement req = parseRowToRequirement(row, headerMap);
                    if (req != null) { // Only add if parsing was successful
                        successfullyParsedRequirements.add(req);
                        requirementsProcessed++;
                    } else {
                         log.warn("Skipping row {} due to missing mandatory data (e.g., 'Short req').", i + 1);
                    }
                } catch (Exception e) {
                    log.error("Error parsing row {}: {}", i + 1, e.getMessage(), e);
                    return internalServerError(Json.newObject().put("error", "Error processing row " + (i + 1) + ": " + e.getMessage()));
                }
            }
             log.info("Successfully parsed {} requirements from the file.", requirementsProcessed);


            // 5. Persistence (within a transaction) - New approach using database-level conflict resolution
            try {
                 final int finalProcessedCount = requirementsProcessed;
                 
                 jpaApi.withTransaction(entityManager -> {
                     // First, process each requirement individually
                     for (Requirement req : successfullyParsedRequirements) {
                         try {
                             // Save requirement first without relationships
                             Requirement savedReq = saveOrUpdateRequirement(entityManager, req);
                             
                             // Then handle relationships separately with conflict resolution
                             handleRequirementRelationships(entityManager, savedReq, req);
                             
                             // Flush after each requirement to catch conflicts early
                             entityManager.flush();
                             
                         } catch (Exception e) {
                             log.warn("Failed to process requirement '{}': {}. Continuing with next requirement.", 
                                 req.getShortreq(), e.getMessage());
                             // Continue with next requirement instead of failing entire import
                         }
                     }
                     log.info("Successfully processed {} requirements with use cases and norms.", finalProcessedCount);
                 });

                 ObjectNode resultJson = Json.newObject();
                 resultJson.put("message", "File processed successfully.");
                 resultJson.put("requirementsProcessed", requirementsProcessed);
                 return ok(resultJson);

            } catch (Exception e) {
                 log.error("Error persisting requirements: {}", e.getMessage(), e);
                 return internalServerError(Json.newObject().put("error", "Database error while saving requirements: " + e.getMessage()));
            }

        } catch (Exception e) {
            log.error("Error processing XLSX file: {}", e.getMessage(), e);
            // Check for specific POI exceptions if needed
            return internalServerError(Json.newObject().put("error", "Failed to process XLSX file: " + e.getMessage()));
        } 
        // The 'finally' block for deleting the temporary file is removed
        // as Play's TemporaryFile mechanism handles this.
    }

    private Map<String, Integer> getHeaderMap(Row headerRow) {
        Map<String, Integer> headerMap = new HashMap<>();
        for (Cell cell : headerRow) {
            if (cell != null && cell.getCellType() == CellType.STRING) {
                headerMap.put(cell.getStringCellValue().trim().toLowerCase(), cell.getColumnIndex());
            }
        }
        return headerMap;
    }

    private Requirement parseRowToRequirement(Row row, Map<String, Integer> headerMap) {
        Requirement req = new Requirement();
        String shortReq = getCellStringValue(row, headerMap, "short req");

        if (shortReq == null || shortReq.trim().isEmpty()) {
            // Short req is mandatory, if it's missing, we might decide not to create the requirement
            log.warn("Mandatory field 'Short req' is missing or empty in row {}. Skipping this requirement.", row.getRowNum() + 1);
            return null; // Indicate that this row should not be processed further as a valid requirement
        }
        req.setShortreq(shortReq);

        // Read the new "Chapter" field
        String chapter = getCellStringValue(row, headerMap, "chapter");
        if (chapter != null && !chapter.trim().isEmpty()) {
            req.setChapter(chapter.trim());
        } else {
            // Optionally, derive chapter from Norm if Chapter column is empty
            String normValue = getCellStringValue(row, headerMap, "norm");
            if (normValue != null && !normValue.trim().isEmpty()) {
                int firstSpace = normValue.indexOf(' ');
                req.setChapter(firstSpace != -1 ? normValue.substring(0, firstSpace).trim() : normValue.trim());
            }
        }

        // Set the norm text field (for backward compatibility)
        String normString = getCellStringValue(row, headerMap, "norm");
        req.setNorm(normString);
        
        // Parse and link norm entities (optimized for multiple norms with • and ; separators)
        if (normString != null && !normString.trim().isEmpty()) {
            try {
                Set<Norm> norms = normParsingService.parseAndCreateNormsFromSingleString(normString);
                if (!norms.isEmpty()) {
                    req.setNorms(norms);
                    log.info("Linked requirement '{}' to {} norms: {}", 
                        req.getShortreq(), 
                        norms.size(),
                        norms.stream()
                            .map(n -> n.getName() + (n.getVersion() != null && !n.getVersion().isEmpty() ? " (" + n.getVersion() + ")" : ""))
                            .collect(java.util.stream.Collectors.joining(", ")));
                } else {
                    log.warn("No valid norms found in string '{}' for requirement '{}'", 
                        normString, req.getShortreq());
                }
            } catch (Exception e) {
                log.warn("Failed to parse norms from '{}' for requirement '{}': {}", 
                    normString, req.getShortreq(), e.getMessage());
                // Continue with import even if norm parsing fails
            }
        }
        
        req.setDetails(getCellStringValue(row, headerMap, "detailsen"));
        req.setMotivation(getCellStringValue(row, headerMap, "motivationen"));
        req.setExample(getCellStringValue(row, headerMap, "exampleen"));
        req.setUseCase(getCellStringValue(row, headerMap, "usecase")); // This is the raw string, will be processed later

        // Set default values or handle nulls as needed for other fields if any
        return req;
    }

    private String getCellStringValue(Row row, Map<String, Integer> headerMap, String headerName) {
        Integer columnIndex = headerMap.get(headerName.toLowerCase());
        if (columnIndex == null) {
            // log.debug("Header '{}' not found, skipping cell.", headerName);
            return null;
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                // Handle numeric cells, convert to string. You might need specific formatting.
                // For now, using DataFormatter to get the string representation as seen in Excel.
                DataFormatter formatter = new DataFormatter();
                return formatter.formatCellValue(cell).trim();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue()).trim();
            case FORMULA:
                // Handle formula cells: evaluate and get the result type
                // This is a simplified example; robust formula handling can be complex.
                try {
                    switch (cell.getCachedFormulaResultType()) {
                        case STRING:
                            return cell.getStringCellValue().trim();
                        case NUMERIC:
                            DataFormatter formulaFormatter = new DataFormatter();
                            return formulaFormatter.formatCellValue(cell).trim(); // Format as seen
                        case BOOLEAN:
                            return String.valueOf(cell.getBooleanCellValue()).trim();
                        default:
                            return null;
                    }
                } catch (IllegalStateException e) {
                    log.warn("Could not evaluate formula in cell ({},{}): {}", row.getRowNum(), columnIndex, e.getMessage());
                    return null; // Or try to get the cached formula string: cell.getCellFormula();
                }
            default:
                return null;
        }
    }

     private boolean isRowEmpty(Row row) {
        if (row == null) {
            return true;
        }
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Save or update a requirement without relationships to avoid conflicts
     */
    private Requirement saveOrUpdateRequirement(EntityManager entityManager, Requirement req) {
        try {
            // Try to find existing requirement
            TypedQuery<Requirement> query = entityManager.createQuery(
                "SELECT r FROM Requirement r WHERE r.shortreq = :shortreq", Requirement.class);
            query.setParameter("shortreq", req.getShortreq());
            Requirement existing = query.getSingleResult();
            
            // Update existing requirement (without relationships)
            existing.setDetails(req.getDetails());
            existing.setMotivation(req.getMotivation());
            existing.setExample(req.getExample());
            existing.setChapter(req.getChapter());
            existing.setNorm(req.getNorm());
            
            return entityManager.merge(existing);
            
        } catch (NoResultException e) {
            // Create new requirement (without relationships for now)
            Requirement newReq = new Requirement();
            newReq.setShortreq(req.getShortreq());
            newReq.setDetails(req.getDetails());
            newReq.setMotivation(req.getMotivation());
            newReq.setExample(req.getExample());
            newReq.setChapter(req.getChapter());
            newReq.setNorm(req.getNorm());
            newReq.setUseCase(req.getUseCase());
            
            entityManager.persist(newReq);
            entityManager.flush(); // Ensure ID is generated
            return newReq;
        }
    }
    
    /**
     * Handle requirement relationships with proper conflict resolution
     */
    private void handleRequirementRelationships(EntityManager entityManager, Requirement savedReq, Requirement originalReq) {
        // Clear existing relationships first
        entityManager.createNativeQuery(
            "DELETE FROM requirement_norm WHERE requirement_id = :reqId")
            .setParameter("reqId", savedReq.getId())
            .executeUpdate();
            
        entityManager.createNativeQuery(
            "DELETE FROM requirement_usecase WHERE requirement_id = :reqId")
            .setParameter("reqId", savedReq.getId())
            .executeUpdate();
        
        // Handle use cases
        if (originalReq.getUseCase() != null && !originalReq.getUseCase().trim().isEmpty()) {
            String[] useCaseNames = originalReq.getUseCase().split(",");
            for (String name : useCaseNames) {
                String trimmedName = name.trim();
                if (!trimmedName.isEmpty()) {
                    UseCase useCase = findOrCreateUseCase(entityManager, trimmedName);
                    // Insert relationship using native query to handle conflicts
                    try {
                        entityManager.createNativeQuery(
                            "INSERT IGNORE INTO requirement_usecase (requirement_id, usecase_id) VALUES (:reqId, :ucId)")
                            .setParameter("reqId", savedReq.getId())
                            .setParameter("ucId", useCase.getId())
                            .executeUpdate();
                    } catch (Exception e) {
                        log.debug("UseCase relationship already exists: {} -> {}", savedReq.getId(), useCase.getId());
                    }
                }
            }
        }
        
        // Handle norms
        if (originalReq.getNorms() != null && !originalReq.getNorms().isEmpty()) {
            for (Norm norm : originalReq.getNorms()) {
                try {
                    entityManager.createNativeQuery(
                        "INSERT IGNORE INTO requirement_norm (requirement_id, norm_id) VALUES (:reqId, :normId)")
                        .setParameter("reqId", savedReq.getId())
                        .setParameter("normId", norm.getId())
                        .executeUpdate();
                } catch (Exception e) {
                    log.debug("Norm relationship already exists: {} -> {}", savedReq.getId(), norm.getId());
                }
            }
        }
    }
    
    /**
     * Find existing or create new UseCase
     */
    private UseCase findOrCreateUseCase(EntityManager entityManager, String name) {
        try {
            TypedQuery<UseCase> query = entityManager.createQuery(
                "SELECT uc FROM UseCase uc WHERE uc.name = :name", UseCase.class);
            query.setParameter("name", name);
            return query.getSingleResult();
        } catch (NoResultException e) {
            UseCase useCase = new UseCase(name);
            entityManager.persist(useCase);
            entityManager.flush();
            return useCase;
        }
    }
}
