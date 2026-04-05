package com.secman.cli.service

import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.multipart.MultipartBody
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

/**
 * Service for CLI port-scan operations.
 * Fetches internet-facing assets, runs nmap, and uploads results.
 */
@Singleton
class PortScanCliService(
    @Client("\${secman.backend.base-url:http://localhost:8080}")
    private val httpClient: HttpClient,
    private val cliHttpClient: CliHttpClient
) {
    private val log = LoggerFactory.getLogger(PortScanCliService::class.java)

    data class AssetTarget(
        val id: Long,
        val name: String,
        val ip: String,
        val networkZone: String?
    )

    data class NmapScanResult(
        val success: Boolean,
        val exitCode: Int,
        val outputFile: File?,
        val errorOutput: String?
    )

    data class UploadResult(
        val success: Boolean,
        val message: String
    )

    /**
     * Fetch internet-facing assets from the backend API.
     */
    fun fetchInternetFacingAssets(backendUrl: String, authToken: String, namePattern: String?): List<AssetTarget> {
        val url = "$backendUrl/api/assets/internet-facing"
        val assets = cliHttpClient.getList(url, authToken) ?: return emptyList()

        return assets
            .filter { asset ->
                val ip = asset["ip"]?.toString()
                !ip.isNullOrBlank()
            }
            .filter { asset ->
                if (namePattern == null) return@filter true
                val name = asset["name"]?.toString() ?: return@filter false
                val regex = namePattern
                    .replace("*", ".*")
                    .replace("?", ".")
                    .let { Regex(it, RegexOption.IGNORE_CASE) }
                regex.matches(name)
            }
            .mapNotNull { asset ->
                val id = (asset["id"] as? Number)?.toLong() ?: return@mapNotNull null
                val name = asset["name"]?.toString() ?: return@mapNotNull null
                val ip = asset["ip"]?.toString() ?: return@mapNotNull null
                AssetTarget(id, name, ip, asset["networkZone"]?.toString())
            }
    }

    /**
     * Validate that nmap is installed and return its version.
     */
    fun validateNmap(nmapPath: String): String? {
        return try {
            val process = ProcessBuilder(nmapPath, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                output.lines().firstOrNull { it.contains("Nmap version") }?.trim() ?: output.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Run nmap scan against a single target IP.
     */
    fun runNmapScan(
        nmapPath: String,
        nmapArgs: List<String>,
        ip: String,
        ports: String?,
        outputFile: File
    ): NmapScanResult {
        val command = mutableListOf(nmapPath)
        command.add("-oX")
        command.add(outputFile.absolutePath)
        command.addAll(nmapArgs)
        if (ports != null) {
            command.add("-p")
            command.add(ports)
        }
        command.add(ip)

        return try {
            log.debug("Running nmap: {}", command.joinToString(" "))
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            val errorOutput = process.errorStream.bufferedReader().readText()
            // Consume stdout to prevent blocking
            process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            NmapScanResult(
                success = exitCode == 0 && outputFile.exists() && outputFile.length() > 0,
                exitCode = exitCode,
                outputFile = if (outputFile.exists()) outputFile else null,
                errorOutput = errorOutput.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            log.error("Failed to run nmap against {}: {}", ip, e.message)
            NmapScanResult(
                success = false,
                exitCode = -1,
                outputFile = null,
                errorOutput = e.message
            )
        }
    }

    /**
     * Upload nmap XML scan result to the backend.
     */
    fun uploadScanResult(backendUrl: String, authToken: String, xmlFile: File): UploadResult {
        return try {
            val body = MultipartBody.builder()
                .addPart("file", xmlFile.name, MediaType.APPLICATION_XML_TYPE, xmlFile)
                .build()

            val request: MutableHttpRequest<MultipartBody> = HttpRequest.POST(
                "$backendUrl/api/scan/upload-nmap",
                body
            )
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)

            val response = httpClient.toBlocking().exchange(request, String::class.java)
            if (response.status.code in 200..299) {
                UploadResult(true, "Upload successful for ${xmlFile.name}")
            } else {
                UploadResult(false, "Upload failed with status ${response.status.code}")
            }
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            log.error("Upload failed for {}: {} - {}", xmlFile.name, e.status.code, e.message)
            UploadResult(false, "Upload failed: ${e.status.code} - ${e.message}")
        } catch (e: Exception) {
            log.error("Upload error for {}: {}", xmlFile.name, e.message)
            UploadResult(false, "Upload error: ${e.message}")
        }
    }
}
