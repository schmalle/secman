package services;

import models.Norm;
import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import play.db.jpa.JPAApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.NoResultException;

import java.util.Set;

public class NormParsingServiceTest {
    
    private NormParsingService normParsingService;
    private JPAApi mockJpaApi;
    private EntityManager mockEntityManager;
    private TypedQuery<Norm> mockQuery;
    
    @Before
    @SuppressWarnings("unchecked")
    public void setup() {
        mockJpaApi = mock(JPAApi.class);
        mockEntityManager = mock(EntityManager.class);
        mockQuery = mock(TypedQuery.class);
        
        normParsingService = new NormParsingService(mockJpaApi);
        
        // Setup mock behavior for JPA transaction
        when(mockJpaApi.withTransaction(any(java.util.function.Function.class))).thenAnswer(invocation -> {
            java.util.function.Function<EntityManager, Object> function = invocation.getArgument(0);
            return function.apply(mockEntityManager);
        });
        
        when(mockEntityManager.createQuery(anyString(), eq(Norm.class))).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenThrow(new NoResultException());
    }
    
    @Test
    public void testParseMultipleNormsFromExcelFormat() {
        // Test the exact format from the Excel file
        String excelNormString = "ISO 27001: A.8.1.1;NIST SP 800-171: 3.4.1;IEC62443-2-1 S2.3.3.6; IEC62443-3-2 S4.2.1";
        
        Set<Norm> norms = normParsingService.parseAndCreateNormsFromSingleString(excelNormString);
        
        // Should parse 4 distinct norms
        assertEquals("Should parse 4 norms from Excel string", 4, norms.size());
        
        // Verify that full references are preserved (not just base names)
        boolean hasIsoWithSection = norms.stream().anyMatch(n -> "ISO 27001: A.8.1.1".equals(n.getName()));
        boolean hasNistWithSection = norms.stream().anyMatch(n -> "NIST SP 800-171: 3.4.1".equals(n.getName()));
        boolean hasIec1WithSection = norms.stream().anyMatch(n -> "IEC62443-2-1 S2.3.3.6".equals(n.getName()));
        boolean hasIec2WithSection = norms.stream().anyMatch(n -> "IEC62443-3-2 S4.2.1".equals(n.getName()));
        
        assertTrue("Should preserve ISO 27001: A.8.1.1", hasIsoWithSection);
        assertTrue("Should preserve NIST SP 800-171: 3.4.1", hasNistWithSection);
        assertTrue("Should preserve IEC62443-2-1 S2.3.3.6", hasIec1WithSection);
        assertTrue("Should preserve IEC62443-3-2 S4.2.1", hasIec2WithSection);
    }
    
    @Test
    public void testParseExactScreenshotFormat() {
        // Test the exact format from the user's Excel screenshot
        String exactExcelString = "ISO27001: 10.1;IEC62443-3-3 S8.5.2 SR4.3";
        
        Set<Norm> norms = normParsingService.parseAndCreateNormsFromSingleString(exactExcelString);
        
        // Should parse 2 distinct norms
        assertEquals("Should parse 2 norms from screenshot string", 2, norms.size());
        
        // Verify that both norms are parsed correctly
        // Note: ISO27001 gets normalized to "ISO 27001" for consistency
        boolean hasIsoNormalized = norms.stream().anyMatch(n -> "ISO 27001: 10.1".equals(n.getName()));
        boolean hasIecComplex = norms.stream().anyMatch(n -> "IEC62443-3-3 S8.5.2 SR4.3".equals(n.getName()));
        
        assertTrue("Should normalize ISO27001: 10.1 to ISO 27001: 10.1", hasIsoNormalized);
        assertTrue("Should preserve IEC62443-3-3 S8.5.2 SR4.3", hasIecComplex);
    }
    
    @Test
    public void testParseNewScreenshotFormat() {
        // Test the new exact format from the user's latest Excel screenshot
        String newExcelString = "ISO27001: 8.0;IEC 62443-3-3 SR1.8; IEC62443-3-2 S4.3.3.";
        
        Set<Norm> norms = normParsingService.parseAndCreateNormsFromSingleString(newExcelString);
        
        // Should parse 3 distinct norms
        assertEquals("Should parse 3 norms from new screenshot string", 3, norms.size());
        
        // Verify that all three norms are parsed correctly
        boolean hasIso8 = norms.stream().anyMatch(n -> "ISO 27001: 8.0".equals(n.getName()));
        boolean hasIecWithSpace = norms.stream().anyMatch(n -> "IEC 62443-3-3 SR1.8".equals(n.getName()));
        boolean hasIecWithPeriod = norms.stream().anyMatch(n -> "IEC62443-3-2 S4.3.3".equals(n.getName())); // period should be stripped
        
        assertTrue("Should normalize ISO27001: 8.0 to ISO 27001: 8.0", hasIso8);
        assertTrue("Should preserve IEC 62443-3-3 SR1.8 (with space)", hasIecWithSpace);
        assertTrue("Should preserve IEC62443-3-2 S4.3.3 (strip trailing period)", hasIecWithPeriod);
    }
    
    @Test
    public void testParseIndividualNormFormats() {
        // Test individual norm formats
        testSingleNorm("ISO 27001: A.8.1.1", "ISO 27001: A.8.1.1");
        testSingleNorm("NIST SP 800-171: 3.4.1", "NIST SP 800-171: 3.4.1");
        testSingleNorm("IEC62443-2-1 S2.3.3.6", "IEC62443-2-1 S2.3.3.6");
        testSingleNorm("IEC62443-3-2 S4.2.1", "IEC62443-3-2 S4.2.1");
    }
    
    @Test
    public void testParseNormWithYear() {
        // Test norm with year format
        testSingleNorm("ISO 27001:2013 A.8.1.1", "ISO 27001:2013: A.8.1.1");
    }
    
    @Test
    public void testParseBaseNormWithoutSection() {
        // Test base norm without section
        testSingleNorm("ISO 27001", "ISO 27001");
        testSingleNorm("ISO27001", "ISO 27001"); // Test no-space variant
        testSingleNorm("NIST CSF", "NIST CSF");
        testSingleNorm("IEC62443-3-2", "IEC62443-3-2");
    }
    
    @Test
    public void testInvalidNormFormats() {
        // Test that invalid formats are rejected
        Set<Norm> norms = normParsingService.parseAndCreateNormsFromSingleString("INVALID NORM; ANOTHER BAD ONE");
        assertEquals("Should reject invalid norm formats", 0, norms.size());
    }
    
    private void testSingleNorm(String input, String expectedName) {
        Set<Norm> norms = normParsingService.parseAndCreateNormsFromSingleString(input);
        assertEquals("Should parse exactly one norm", 1, norms.size());
        
        Norm norm = norms.iterator().next();
        assertEquals("Should preserve full norm name", expectedName, norm.getName());
    }
}