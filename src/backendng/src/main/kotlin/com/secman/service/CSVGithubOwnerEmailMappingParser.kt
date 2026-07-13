package com.secman.service

import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Parses a CSV of `owner,email` rows and upserts them via
 * [GithubOwnerEmailMappingService], mirroring [CSVUserMappingParser]'s
 * encoding/delimiter detection and per-row error handling. Existing owners
 * in the file are skipped as duplicates (use the update endpoint to change
 * an existing mapping's email).
 */
@Singleton
open class CSVGithubOwnerEmailMappingParser(
    private val mappingService: GithubOwnerEmailMappingService
) {
    private val log = LoggerFactory.getLogger(CSVGithubOwnerEmailMappingParser::class.java)

    companion object {
        private val REQUIRED_HEADERS = listOf("owner", "email")
        private const val MAX_ERRORS_RETURNED = 50
    }

    @Serdeable
    data class ImportError(val line: Int, val reason: String, val value: String?)

    @Serdeable
    data class ImportResult(
        val imported: Int,
        val skipped: Int,
        val errors: List<ImportError> = emptyList()
    )

    open fun parse(file: File, actor: String): ImportResult {
        log.info("Starting GitHub owner email mapping CSV parse: file={}, size={}", file.name, file.length())

        val errors = mutableListOf<ImportError>()
        val seenOwners = mutableSetOf<String>()
        var imported = 0
        var skipped = 0
        var lineNumber = 1

        val reader = detectEncodingAndRead(file)
        val firstLine = reader.readLine()
            ?: throw IllegalArgumentException("Empty file uploaded")
        val delimiter = detectDelimiter(firstLine)
        reader.close()

        val csvFormat = CSVFormat.RFC4180.builder()
            .setDelimiter(delimiter)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build()

        val parser = CSVParser(detectEncodingAndRead(file), csvFormat)
        val headerMap = parser.headerMap
        val lowerHeaders = headerMap.keys.map { it.lowercase() }
        val missing = REQUIRED_HEADERS.filter { !lowerHeaders.contains(it) }
        if (missing.isNotEmpty()) {
            parser.close()
            throw IllegalArgumentException("Missing required columns: ${missing.joinToString(", ")}")
        }

        for (record in parser) {
            lineNumber++
            try {
                val owner = getColumnValue(record, headerMap, "owner")?.trim()
                val email = getColumnValue(record, headerMap, "email")?.trim()

                if (owner.isNullOrBlank()) throw IllegalArgumentException("owner is required")
                if (email.isNullOrBlank()) throw IllegalArgumentException("email is required")

                val ownerKey = owner.lowercase()
                if (seenOwners.contains(ownerKey)) {
                    skipped++
                    errors.add(ImportError(lineNumber, "Duplicate owner within file", owner))
                    continue
                }
                seenOwners.add(ownerKey)

                mappingService.create(owner, email, actor)
                imported++
            } catch (e: GithubOwnerEmailMappingService.DuplicateOwnerException) {
                skipped++
                errors.add(ImportError(lineNumber, e.message ?: "Duplicate owner", null))
            } catch (e: Exception) {
                skipped++
                errors.add(ImportError(lineNumber, e.message ?: "Unknown error", null))
            }
        }
        parser.close()

        log.info("GitHub owner email mapping CSV parse complete: imported={}, skipped={}, errors={}",
            imported, skipped, errors.size)

        return ImportResult(imported = imported, skipped = skipped, errors = errors.take(MAX_ERRORS_RETURNED))
    }

    private fun detectEncodingAndRead(file: File): BufferedReader {
        val inputStream = FileInputStream(file)
        val bomBytes = ByteArray(3)
        val bytesRead = inputStream.read(bomBytes)

        if (bytesRead == 3 && bomBytes[0] == 0xEF.toByte() && bomBytes[1] == 0xBB.toByte() && bomBytes[2] == 0xBF.toByte()) {
            return InputStreamReader(inputStream, Charsets.UTF_8).buffered()
        }
        inputStream.close()
        return try {
            file.bufferedReader(Charsets.UTF_8)
        } catch (e: Exception) {
            file.bufferedReader(Charsets.ISO_8859_1)
        }
    }

    private fun detectDelimiter(firstLine: String): Char {
        val commaCount = firstLine.count { it == ',' }
        val semicolonCount = firstLine.count { it == ';' }
        val tabCount = firstLine.count { it == '\t' }
        return when {
            commaCount >= semicolonCount && commaCount >= tabCount -> ','
            semicolonCount >= commaCount && semicolonCount >= tabCount -> ';'
            tabCount >= commaCount && tabCount >= semicolonCount -> '\t'
            else -> ','
        }
    }

    private fun getColumnValue(record: org.apache.commons.csv.CSVRecord, headerMap: Map<String, Int>, headerName: String): String? {
        if (headerMap.containsKey(headerName)) {
            return record.get(headerName)?.trim()
        }
        val matchingKey = headerMap.keys.find { it.equals(headerName, ignoreCase = true) }
        return matchingKey?.let { record.get(it)?.trim() }
    }
}
