package com.secman.service

import com.secman.domain.Norm
import com.secman.repository.NormRepository
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import java.util.*

@MicronautTest
class NormParsingServiceTest {

    @Mock
    private lateinit var normRepository: NormRepository
    
    private lateinit var normParsingService: NormParsingService

    @BeforeEach
    fun setup() {
        MockitoAnnotations.openMocks(this)
        normParsingService = NormParsingService(normRepository)
    }

    @Test
    fun `should parse ISO norm with year and section`() {
        // Given
        val normString = "ISO 27001:2013: A.8.1.1"
        val expectedNorm = Norm(name = "ISO 27001: A.8.1.1", version = "2013", year = 2013)
        
        `when`(normRepository.findByName("ISO 27001: A.8.1.1")).thenReturn(Optional.empty())
        `when`(normRepository.save(any(Norm::class.java))).thenReturn(expectedNorm)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(1, result.size)
        val norm = result.first()
        assertEquals("ISO 27001: A.8.1.1", norm.name)
        assertEquals("2013", norm.version)
        assertEquals(2013, norm.year)
    }

    @Test
    fun `should parse ISO norm without spacing`() {
        // Given
        val normString = "ISO27001"
        val expectedNorm = Norm(name = "ISO 27001", version = "", year = null)
        
        `when`(normRepository.findByName("ISO 27001")).thenReturn(Optional.empty())
        `when`(normRepository.save(any(Norm::class.java))).thenReturn(expectedNorm)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(1, result.size)
        val norm = result.first()
        assertEquals("ISO 27001", norm.name)
        assertEquals("", norm.version)
        assertNull(norm.year)
    }

    @Test
    fun `should parse NIST norm with section`() {
        // Given
        val normString = "NIST SP 800-171: 3.4.1"
        val expectedNorm = Norm(name = "NIST SP 800-171: 3.4.1", version = "", year = null)
        
        `when`(normRepository.findByName("NIST SP 800-171: 3.4.1")).thenReturn(Optional.empty())
        `when`(normRepository.save(any(Norm::class.java))).thenReturn(expectedNorm)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(1, result.size)
        val norm = result.first()
        assertEquals("NIST SP 800-171: 3.4.1", norm.name)
        assertEquals("", norm.version)
        assertNull(norm.year)
    }

    @Test
    fun `should parse NIST CSF norm`() {
        // Given
        val normString = "NIST CSF"
        val expectedNorm = Norm(name = "NIST CSF", version = "", year = null)
        
        `when`(normRepository.findByName("NIST CSF")).thenReturn(Optional.empty())
        `when`(normRepository.save(any(Norm::class.java))).thenReturn(expectedNorm)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(1, result.size)
        val norm = result.first()
        assertEquals("NIST CSF", norm.name)
    }

    @Test
    fun `should parse IEC norm with year and section`() {
        // Given
        val normString = "IEC62443-2-1:2010 S2.3.3.6"
        val expectedNorm = Norm(name = "IEC62443-2-1 S2.3.3.6", version = "2010", year = 2010)
        
        `when`(normRepository.findByName("IEC62443-2-1 S2.3.3.6")).thenReturn(Optional.empty())
        `when`(normRepository.save(any(Norm::class.java))).thenReturn(expectedNorm)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(1, result.size)
        val norm = result.first()
        assertEquals("IEC62443-2-1 S2.3.3.6", norm.name)
        assertEquals("2010", norm.version)
        assertEquals(2010, norm.year)
    }

    @Test
    fun `should parse IEC norm with spacing`() {
        // Given
        val normString = "IEC 62443-3-3"
        val expectedNorm = Norm(name = "IEC 62443-3-3", version = "", year = null)
        
        `when`(normRepository.findByName("IEC 62443-3-3")).thenReturn(Optional.empty())
        `when`(normRepository.save(any(Norm::class.java))).thenReturn(expectedNorm)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(1, result.size)
        val norm = result.first()
        assertEquals("IEC 62443-3-3", norm.name)
    }

    @Test
    fun `should parse multiple norms separated by semicolon`() {
        // Given
        val normString = "ISO 27001: A.8.1.1;NIST SP 800-171: 3.4.1;IEC62443-2-1 S2.3.3.6"
        
        val norm1 = Norm(name = "ISO 27001: A.8.1.1", version = "", year = null)
        val norm2 = Norm(name = "NIST SP 800-171: 3.4.1", version = "", year = null)
        val norm3 = Norm(name = "IEC62443-2-1 S2.3.3.6", version = "", year = null)
        
        `when`(normRepository.findByName("ISO 27001: A.8.1.1")).thenReturn(Optional.empty())
        `when`(normRepository.findByName("NIST SP 800-171: 3.4.1")).thenReturn(Optional.empty())
        `when`(normRepository.findByName("IEC62443-2-1 S2.3.3.6")).thenReturn(Optional.empty())
        
        `when`(normRepository.save(any(Norm::class.java)))
            .thenReturn(norm1, norm2, norm3)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(3, result.size)
        val names = result.map { it.name }.sorted()
        assertTrue(names.contains("ISO 27001: A.8.1.1"))
        assertTrue(names.contains("NIST SP 800-171: 3.4.1"))
        assertTrue(names.contains("IEC62443-2-1 S2.3.3.6"))
    }

    @Test
    fun `should parse multiple norms separated by bullet points`() {
        // Given
        val normString = "ISO 27001 • NIST CSF • IEC 62443-3-3"
        
        val norm1 = Norm(name = "ISO 27001", version = "", year = null)
        val norm2 = Norm(name = "NIST CSF", version = "", year = null)
        val norm3 = Norm(name = "IEC 62443-3-3", version = "", year = null)
        
        `when`(normRepository.findByName("ISO 27001")).thenReturn(Optional.empty())
        `when`(normRepository.findByName("NIST CSF")).thenReturn(Optional.empty())
        `when`(normRepository.findByName("IEC 62443-3-3")).thenReturn(Optional.empty())
        
        `when`(normRepository.save(any(Norm::class.java)))
            .thenReturn(norm1, norm2, norm3)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(3, result.size)
    }

    @Test
    fun `should handle trailing periods`() {
        // Given
        val normString = "ISO 27001."
        val expectedNorm = Norm(name = "ISO 27001", version = "", year = null)
        
        `when`(normRepository.findByName("ISO 27001")).thenReturn(Optional.empty())
        `when`(normRepository.save(any(Norm::class.java))).thenReturn(expectedNorm)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(1, result.size)
        assertEquals("ISO 27001", result.first().name)
    }

    @Test
    fun `should handle extra whitespace`() {
        // Given
        val normString = "  ISO   27001  :  2013  :  A.8.1.1  "
        val expectedNorm = Norm(name = "ISO 27001: A.8.1.1", version = "2013", year = 2013)
        
        `when`(normRepository.findByName("ISO 27001: A.8.1.1")).thenReturn(Optional.empty())
        `when`(normRepository.save(any(Norm::class.java))).thenReturn(expectedNorm)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(1, result.size)
        assertEquals("ISO 27001: A.8.1.1", result.first().name)
    }

    @Test
    fun `should return empty set for invalid norm format`() {
        // Given
        val normString = "INVALID NORM FORMAT"

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(0, result.size)
        verify(normRepository, never()).save(any(Norm::class.java))
    }

    @Test
    fun `should return empty set for null or blank input`() {
        // When
        val result1 = normParsingService.parseNorms(null)
        val result2 = normParsingService.parseNorms("")
        val result3 = normParsingService.parseNorms("   ")

        // Then
        assertEquals(0, result1.size)
        assertEquals(0, result2.size)
        assertEquals(0, result3.size)
        verify(normRepository, never()).save(any(Norm::class.java))
    }

    @Test
    fun `should find existing norm instead of creating duplicate`() {
        // Given
        val normString = "ISO 27001"
        val existingNorm = Norm(name = "ISO 27001", version = "", year = null)
        
        `when`(normRepository.findByName("ISO 27001")).thenReturn(Optional.of(existingNorm))

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(1, result.size)
        assertEquals(existingNorm, result.first())
        verify(normRepository, never()).save(any(Norm::class.java))
    }

    @Test
    fun `should reject invalid years`() {
        // Given
        val normString = "ISO 27001:1800" // Too old
        val expectedNorm = Norm(name = "ISO 27001", version = "1800", year = null) // Year should be null
        
        `when`(normRepository.findByName("ISO 27001")).thenReturn(Optional.empty())
        `when`(normRepository.save(any(Norm::class.java))).thenReturn(expectedNorm)

        // When
        val result = normParsingService.parseNorms(normString)

        // Then
        assertEquals(1, result.size)
        val norm = result.first()
        assertEquals("ISO 27001", norm.name)
        assertEquals("1800", norm.version)
        assertNull(norm.year) // Invalid year should be null
    }
}