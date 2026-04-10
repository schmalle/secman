package com.secman.cli.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.client.DefaultHttpClientConfiguration
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.netty.DefaultHttpClient
import io.micronaut.http.ssl.AbstractClientSslConfiguration
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.net.URI
import java.time.Duration
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser

/**
 * CLI service for user mapping operations (Feature 049)
 *
 * All operations are performed via HTTP calls to the backend REST API.
 * The backend handles validation, duplicate detection, and persistence.
 * The HTTP client is created dynamically to support runtime SSL configuration.
 */
@Singleton
class UserMappingCliService {
    private val log = LoggerFactory.getLogger(UserMappingCliService::class.java)
    private val objectMapper = jacksonObjectMapper()

    private var httpClient: HttpClient? = null
    private var insecureMode: Boolean = false

    /**
     * Initialize the HTTP client for the given backend URL.
     * Must be called before any HTTP operations.
     *
     * @param backendUrl Backend API base URL
     * @param insecure If true, disable SSL certificate verification
     */
    fun initHttpClient(backendUrl: String, insecure: Boolean) {
        // Insecure mode can be triggered via --insecure flag, SECMAN_INSECURE env var,
        // or -Dsecman.ssl.insecure=true JVM flag (set by bin/secmanng wrapper).
        val jvmInsecure = System.getProperty("secman.ssl.insecure")?.lowercase() == "true"
        this.insecureMode = insecure || jvmInsecure
        val config = DefaultHttpClientConfiguration().apply {
            setReadTimeout(Duration.ofSeconds(120))
            setConnectTimeout(Duration.ofSeconds(30))
            maxContentLength = 104_857_600 // 100MB
        }
        if (this.insecureMode) {
            log.warn("SSL certificate verification is DISABLED (--insecure mode)")
            (config.sslConfiguration as AbstractClientSslConfiguration).isInsecureTrustAllCertificates = true
        }
        // Always use the 2-argument constructor — the 3-arg variant with
        // ClientSslBuilder has a scheme-initialization bug in Micronaut 4.10.x
        @Suppress("DEPRECATION")
        httpClient = DefaultHttpClient(URI.create(backendUrl), config)
    }

    companion object {
        const val MAX_IMPORT_ROWS = 100_000

        init {
            // When -Dsecman.ssl.insecure=true is set by the secmanng wrapper script,
            // configure JVM-level SSL defaults before any HTTP client is created.
            // This ensures both Micronaut Netty client and Java HttpClient accept
            // self-signed certificates. Safe for CLI (short-lived, single-target process).
            if (System.getProperty("secman.ssl.insecure")?.lowercase() == "true") {
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                    init(null, trustAllCerts, java.security.SecureRandom())
                }
                javax.net.ssl.SSLContext.setDefault(sslContext)
                javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
                javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            }
        }
    }

    private fun getClient(): HttpClient {
        return httpClient ?: throw IllegalStateException(
            "HTTP client not initialized. Call initHttpClient() first."
        )
    }

    // Client-side validation regex (fast feedback before HTTP call)
    private val emailRegex = Regex("^[^@]+@[^@]+\\.[^@]+$")
    private val awsAccountIdRegex = Regex("^\\d{12}$")
    private val domainRegex = Regex("^[a-zA-Z0-9.-]+$")

    /**
     * Authenticate with backend API and get JWT token
     */
    fun authenticate(username: String, password: String, backendUrl: String): String? {
        try {
            val request = HttpRequest.POST("$backendUrl/api/auth/login", mapOf(
                "username" to username,
                "password" to password
            )).contentType(MediaType.APPLICATION_JSON)

            val response: HttpResponse<Map<*, *>> = getClient().toBlocking()
                .exchange(request, Map::class.java)

            if (response.status.code == 200) {
                // JWT is in Set-Cookie header as "secman_auth=<token>; ..."
                val setCookieHeaders = response.headers.getAll("Set-Cookie")
                val token = setCookieHeaders
                    ?.flatMap { it.split(";") }
                    ?.firstOrNull { it.trim().startsWith("secman_auth=") }
                    ?.substringAfter("=")
                    ?.trim()

                if (token != null && token.isNotBlank()) {
                    log.info("Successfully authenticated with backend")
                    return token
                }

                // Fallback: try response body (for future API changes)
                val body = response.body()
                val bodyToken = body?.get("access_token")?.toString()
                    ?: body?.get("token")?.toString()
                    ?: body?.get("accessToken")?.toString()

                if (bodyToken != null) {
                    log.info("Successfully authenticated with backend (body token)")
                    return bodyToken
                }

                log.error("Authentication succeeded but no JWT found in Set-Cookie or response body")
            }

            log.error("Authentication failed: status={}", response.status)
            return null
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            log.error("Authentication error: {} - {}", e.status.code, e.message)
            return null
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            log.error("SSL handshake failed during authentication: {}", e.message)
            throw IllegalArgumentException(
                "SSL certificate verification failed for $backendUrl. " +
                "If using a self-signed certificate, use --insecure flag or set SECMAN_INSECURE=true"
            )
        } catch (e: javax.net.ssl.SSLException) {
            log.error("SSL error during authentication: {}", e.message)
            throw IllegalArgumentException(
                "SSL error connecting to $backendUrl: ${e.message}. " +
                "If using a self-signed certificate, use --insecure flag or set SECMAN_INSECURE=true"
            )
        } catch (e: Exception) {
            log.error("Authentication error: {}", e.message, e)
            return null
        }
    }

    /**
     * Add domain-to-user mappings via bulk endpoint
     */
    fun addDomainMappings(
        emails: List<String>,
        domains: List<String>,
        backendUrl: String,
        authToken: String
    ): MappingResult {
        // Client-side validation
        val invalidEmails = emails.filter { !emailRegex.matches(it) }
        if (invalidEmails.isNotEmpty()) {
            throw IllegalArgumentException("Invalid email format: ${invalidEmails.joinToString()}")
        }
        val invalidDomains = domains.filter { !domainRegex.matches(it) }
        if (invalidDomains.isNotEmpty()) {
            throw IllegalArgumentException("Invalid domain format: ${invalidDomains.joinToString()}")
        }

        // Build cross-product entries
        val entries = emails.flatMap { email ->
            domains.map { domain ->
                mapOf("email" to email.lowercase().trim(), "domain" to domain.lowercase().trim())
            }
        }

        val bulkResponse = postBulk(entries, false, backendUrl, authToken)
        return bulkResponseToMappingResult(bulkResponse, entries)
    }

    /**
     * Add AWS-account-to-user mappings via bulk endpoint
     */
    fun addAwsAccountMappings(
        emails: List<String>,
        awsAccountIds: List<String>,
        backendUrl: String,
        authToken: String
    ): MappingResult {
        // Client-side validation
        val invalidEmails = emails.filter { !emailRegex.matches(it) }
        if (invalidEmails.isNotEmpty()) {
            throw IllegalArgumentException("Invalid email format: ${invalidEmails.joinToString()}")
        }
        val invalidAccounts = awsAccountIds.filter { !awsAccountIdRegex.matches(it) }
        if (invalidAccounts.isNotEmpty()) {
            throw IllegalArgumentException("Invalid AWS account ID (must be 12 digits): ${invalidAccounts.joinToString()}")
        }

        // Build cross-product entries
        val entries = emails.flatMap { email ->
            awsAccountIds.map { accountId ->
                mapOf("email" to email.lowercase().trim(), "awsAccountId" to accountId.trim())
            }
        }

        val bulkResponse = postBulk(entries, false, backendUrl, authToken)
        return bulkResponseToMappingResult(bulkResponse, entries)
    }

    /**
     * List user mappings via HTTP with optional filtering
     */
    fun listMappings(
        email: String? = null,
        status: String? = null,
        backendUrl: String,
        authToken: String
    ): List<UserMappingCliResponse> {
        val results = mutableListOf<UserMappingCliResponse>()
        var page = 0
        val pageSize = 1000

        do {
            val params = mutableListOf("page=$page", "size=$pageSize")
            if (email != null) params.add("email=${email.lowercase().trim()}")
            if (status != null) params.add("status=${status.uppercase()}")

            val request = HttpRequest.GET<Any>("$backendUrl/api/user-mappings?${params.joinToString("&")}")
                .header("Authorization", "Bearer $authToken")

            val response = getClient().toBlocking().exchange(request, Map::class.java)
            val body = response.body() ?: break

            @Suppress("UNCHECKED_CAST")
            val content = body["content"] as? List<Map<String, Any?>> ?: break
            val totalElements = (body["totalElements"] as? Number)?.toLong() ?: 0

            content.forEach { item ->
                results.add(UserMappingCliResponse(
                    id = (item["id"] as Number).toLong(),
                    email = item["email"] as String,
                    awsAccountId = item["awsAccountId"] as? String,
                    domain = item["domain"] as? String,
                    status = if (item["isFutureMapping"] == true) "PENDING" else "ACTIVE",
                    createdAt = item["createdAt"] as? String ?: "",
                    appliedAt = item["appliedAt"] as? String
                ))
            }

            page++
        } while (results.size < totalElements)

        return results
    }

    /**
     * Remove user mappings by finding via HTTP then deleting by ID
     */
    fun removeMappings(
        email: String,
        domain: String? = null,
        awsAccountId: String? = null,
        removeAll: Boolean = false,
        backendUrl: String,
        authToken: String
    ): Int {
        val normalizedEmail = email.lowercase().trim()

        // Fetch all mappings for this email
        val allMappings = listMappings(
            email = normalizedEmail,
            status = null,
            backendUrl = backendUrl,
            authToken = authToken
        )

        // Filter based on criteria
        val mappingsToDelete = when {
            removeAll -> allMappings
            domain != null -> allMappings.filter {
                it.domain?.lowercase()?.trim() == domain.lowercase().trim()
            }
            awsAccountId != null -> allMappings.filter {
                it.awsAccountId?.trim() == awsAccountId.trim()
            }
            else -> throw IllegalArgumentException(
                "Must specify either --domain, --account, or --all to indicate what to remove"
            )
        }

        if (mappingsToDelete.isEmpty()) {
            throw IllegalArgumentException("No mappings found matching the specified criteria")
        }

        // Delete each by ID
        var deletedCount = 0
        mappingsToDelete.forEach { mapping ->
            try {
                val request = HttpRequest.DELETE<Any>("$backendUrl/api/user-mappings/${mapping.id}")
                    .header("Authorization", "Bearer $authToken")

                getClient().toBlocking().exchange(request, Void::class.java)
                deletedCount++

                log.info("Deleted mapping id=${mapping.id}: ${mapping.email} -> ${mapping.domain ?: mapping.awsAccountId}")
            } catch (e: Exception) {
                log.error("Failed to delete mapping id=${mapping.id}: ${e.message}")
            }
        }

        return deletedCount
    }

    /**
     * Import user mappings from CSV or JSON file via HTTP bulk endpoint
     */
    fun importMappingsFromFile(
        filePath: String,
        format: String,
        dryRun: Boolean,
        backendUrl: String,
        authToken: String
    ): MappingResult {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $filePath")
        }

        // Auto-detect format if needed
        val detectedFormat = when {
            format.uppercase() != "AUTO" -> format.uppercase()
            filePath.endsWith(".csv", ignoreCase = true) -> "CSV"
            filePath.endsWith(".json", ignoreCase = true) -> "JSON"
            else -> {
                val content = file.readText()
                when {
                    content.trim().startsWith("[") || content.trim().startsWith("{") -> "JSON"
                    else -> "CSV"
                }
            }
        }

        log.info("Importing from $filePath (format: $detectedFormat, dryRun: $dryRun)")

        // Parse file into entries (CLI handles Cloud Custodian format)
        val parseResult = when (detectedFormat) {
            "CSV" -> parseFromCsv(file)
            "JSON" -> parseFromJson(file)
            else -> throw IllegalArgumentException("Unsupported format: $detectedFormat")
        }

        if (parseResult.entries.isEmpty() && parseResult.errors.isEmpty()) {
            return MappingResult(
                totalProcessed = 0,
                created = 0,
                createdPending = 0,
                skipped = 0,
                errors = listOf("No valid entries found in file"),
                operations = emptyList()
            )
        }

        // Send parsed entries to bulk endpoint
        val bulkResponse = postBulk(parseResult.entries, dryRun, backendUrl, authToken)

        // Merge parse errors with backend errors
        val allErrors = parseResult.errors + bulkResponse.errors
        val comparison = if (dryRun && bulkResponse.comparison != null) {
            MappingComparisonResult(
                dbMappingCount = bulkResponse.comparison.dbMappingCount,
                fileMappingCount = bulkResponse.comparison.fileMappingCount,
                newCount = bulkResponse.comparison.newCount,
                unchangedCount = bulkResponse.comparison.unchangedCount,
                removedCount = bulkResponse.comparison.removedCount,
                dbAvailable = true
            )
        } else null

        // Build operations list for display (simplified from bulk response)
        val operations = parseResult.entries.map { entry ->
            val email = (entry["email"] ?: "") as String
            MappingOperationResult(
                success = true,
                operation = if (dryRun) "WOULD_CREATE" else "CREATED",
                message = if (dryRun) "Would create mapping (dry-run)" else "Created",
                email = email,
                domain = entry["domain"] as? String,
                awsAccountId = entry["awsAccountId"] as? String
            )
        }

        return MappingResult(
            totalProcessed = bulkResponse.totalProcessed,
            created = bulkResponse.created,
            createdPending = bulkResponse.createdPending,
            skipped = bulkResponse.skipped,
            errors = allErrors,
            operations = operations,
            comparison = comparison
        )
    }

    // --- File parsing (kept in CLI for Cloud Custodian format support) ---

    private data class ParseResult(
        val entries: List<Map<String, Any?>>,
        val errors: List<String>
    )

    private fun parseFromCsv(file: File): ParseResult {
        val entries = mutableListOf<Map<String, Any?>>()
        val errors = mutableListOf<String>()
        var lineNumber = 1

        try {
            FileReader(file).use { reader ->
                val csvFormat = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .get()
                val csvParser = csvFormat.parse(reader)

                csvParser.forEach { record ->
                    lineNumber++
                    if (lineNumber - 1 > MAX_IMPORT_ROWS) {
                        throw IllegalArgumentException(
                            "File exceeds maximum of $MAX_IMPORT_ROWS records. Split the file into smaller batches."
                        )
                    }
                    try {
                        val email = record.get("email") ?: record.get("Email") ?: record.get("EMAIL")
                        val type = record.get("type") ?: record.get("Type") ?: record.get("TYPE")
                        val value = record.get("value") ?: record.get("Value") ?: record.get("VALUE")

                        if (email.isNullOrBlank() || type.isNullOrBlank() || value.isNullOrBlank()) {
                            errors.add("Line $lineNumber: Missing required fields (email, type, value)")
                            return@forEach
                        }

                        when (type.uppercase()) {
                            "DOMAIN" -> {
                                if (!emailRegex.matches(email.trim())) {
                                    errors.add("Line $lineNumber: Invalid email format")
                                } else if (!domainRegex.matches(value.trim())) {
                                    errors.add("Line $lineNumber: Invalid domain format")
                                } else {
                                    entries.add(mapOf("email" to email.trim(), "domain" to value.trim()))
                                }
                            }
                            "AWS_ACCOUNT" -> {
                                if (!emailRegex.matches(email.trim())) {
                                    errors.add("Line $lineNumber: Invalid email format")
                                } else if (!awsAccountIdRegex.matches(value.trim())) {
                                    errors.add("Line $lineNumber: Invalid AWS account ID (must be 12 digits)")
                                } else {
                                    entries.add(mapOf("email" to email.trim(), "awsAccountId" to value.trim()))
                                }
                            }
                            else -> errors.add("Line $lineNumber: Invalid type '$type' (must be DOMAIN or AWS_ACCOUNT)")
                        }
                    } catch (e: Exception) {
                        errors.add("Line $lineNumber: ${e.message}")
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse CSV file: ${e.message}", e)
        }

        return ParseResult(entries, errors)
    }

    private fun parseFromJson(file: File): ParseResult {
        val entries = mutableListOf<Map<String, Any?>>()
        val errors = mutableListOf<String>()

        try {
            val rootNode = objectMapper.readTree(file)
            val typeRef = object : TypeReference<List<Map<String, Any>>>() {}
            val mappingsData: List<Map<String, Any>> = when {
                rootNode.isArray -> objectMapper.convertValue(rootNode, typeRef)
                rootNode.isObject -> {
                    val arrayField = rootNode.properties().asSequence()
                        .firstOrNull { it.value.isArray }
                    if (arrayField != null) {
                        log.info("JSON root is an object; using '${arrayField.key}' array (${arrayField.value.size()} entries)")
                        objectMapper.convertValue(arrayField.value, typeRef)
                    } else {
                        listOf(objectMapper.convertValue(rootNode, object : TypeReference<Map<String, Any>>() {}))
                    }
                }
                else -> throw IllegalArgumentException("JSON root must be an array or object")
            }

            if (mappingsData.size > MAX_IMPORT_ROWS) {
                throw IllegalArgumentException(
                    "File contains ${mappingsData.size} records, exceeding maximum of $MAX_IMPORT_ROWS."
                )
            }

            mappingsData.forEach { mapping ->
                // Prefer vars["cov:owner"] (real owner email) over top-level "email"
                @Suppress("UNCHECKED_CAST")
                val vars = mapping["vars"] as? Map<String, Any>
                val covOwner = vars?.get("cov:owner")?.toString()?.trim()
                val rawEmail = (mapping["email"] ?: mapping["Email"]) as? String
                val email = if (!covOwner.isNullOrBlank()) covOwner else rawEmail

                val domains = (mapping["domains"] ?: mapping["Domains"]) as? List<*>
                val awsAccounts = (mapping["awsAccounts"] ?: mapping["AwsAccounts"]) as? List<*>
                    ?: mapping["account_id"]?.toString()?.let { listOf(it) }

                if (email.isNullOrBlank()) {
                    errors.add("Missing or invalid email field")
                    return@forEach
                }

                // Process domains
                domains?.forEach { domain ->
                    val domainStr = domain.toString()
                    if (!emailRegex.matches(email.trim())) {
                        errors.add("Invalid email format: $email")
                    } else if (!domainRegex.matches(domainStr.trim())) {
                        errors.add("Invalid domain format: $domainStr")
                    } else {
                        entries.add(mapOf("email" to email.trim(), "domain" to domainStr.trim()))
                    }
                }

                // Process AWS accounts
                awsAccounts?.forEach { account ->
                    val accountStr = account.toString()
                    if (!emailRegex.matches(email.trim())) {
                        errors.add("Invalid email format: $email")
                    } else if (!awsAccountIdRegex.matches(accountStr.trim())) {
                        errors.add("Invalid AWS account ID (must be 12 digits): $accountStr")
                    } else {
                        entries.add(mapOf("email" to email.trim(), "awsAccountId" to accountStr.trim()))
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse JSON file: ${e.message}", e)
        }

        return ParseResult(entries, errors)
    }

    // --- HTTP helpers ---

    /**
     * Post bulk mappings using Java's built-in HttpClient to bypass Micronaut Serde.
     *
     * Micronaut Serde cannot introspect Map<String, Any> with nested List<Map> generics
     * at compile time, producing malformed JSON that causes route-matching to fail (404).
     * Using java.net.http.HttpClient with Jackson serialization avoids Serde entirely.
     */
    private fun postBulk(
        entries: List<Map<String, Any?>>,
        dryRun: Boolean,
        backendUrl: String,
        authToken: String
    ): BulkResponse {
        val bodyMap = mapOf(
            "mappings" to entries.map { entry ->
                buildMap<String, Any> {
                    put("email", entry["email"] as String)
                    (entry["awsAccountId"] as? String)?.let { put("awsAccountId", it) }
                    (entry["domain"] as? String)?.let { put("domain", it) }
                }
            },
            "dryRun" to dryRun
        )
        val jsonBody = objectMapper.writeValueAsString(bodyMap)

        try {
            val clientBuilder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            if (insecureMode) {
                clientBuilder.sslContext(createTrustAllSslContext())
                // Disable hostname verification — empty string doesn't work on all
                // JDK versions, but null reliably disables the HTTPS endpoint check.
                val sslParams = javax.net.ssl.SSLParameters()
                sslParams.endpointIdentificationAlgorithm = null
                clientBuilder.sslParameters(sslParams)
            }
            val javaClient = clientBuilder.build()

            val httpRequest = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("$backendUrl/api/user-mappings/bulk"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $authToken")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(120))
                .build()

            val response = javaClient.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())

            when (response.statusCode()) {
                200 -> {
                    @Suppress("UNCHECKED_CAST")
                    val responseBody = objectMapper.readValue(response.body(), Map::class.java) as Map<String, Any?>

                    @Suppress("UNCHECKED_CAST")
                    val comparison = (responseBody["comparison"] as? Map<String, Any?>)?.let {
                        BulkComparisonResponse(
                            dbMappingCount = (it["dbMappingCount"] as? Number)?.toInt() ?: 0,
                            fileMappingCount = (it["fileMappingCount"] as? Number)?.toInt() ?: 0,
                            newCount = (it["newCount"] as? Number)?.toInt() ?: 0,
                            unchangedCount = (it["unchangedCount"] as? Number)?.toInt() ?: 0,
                            removedCount = (it["removedCount"] as? Number)?.toInt() ?: 0
                        )
                    }

                    @Suppress("UNCHECKED_CAST")
                    val errorsList = (responseBody["errors"] as? List<String>) ?: emptyList()
                    return BulkResponse(
                        totalProcessed = (responseBody["totalProcessed"] as? Number)?.toInt() ?: entries.size,
                        created = (responseBody["created"] as? Number)?.toInt() ?: 0,
                        createdPending = (responseBody["createdPending"] as? Number)?.toInt() ?: 0,
                        skipped = (responseBody["skipped"] as? Number)?.toInt() ?: 0,
                        errors = errorsList,
                        comparison = comparison
                    )
                }
                404 -> {
                    log.warn("Bulk endpoint not available (404), falling back to individual operations")
                    return if (dryRun) {
                        dryRunFallback(entries, backendUrl, authToken)
                    } else {
                        individualCreateFallback(entries, backendUrl, authToken)
                    }
                }
                401 -> throw IllegalArgumentException("Authentication required")
                403 -> throw IllegalArgumentException("Insufficient permissions (ADMIN role required)")
                400 -> {
                    val errorBody = try {
                        @Suppress("UNCHECKED_CAST")
                        objectMapper.readValue(response.body(), Map::class.java) as Map<String, Any?>
                    } catch (_: Exception) { null }
                    throw IllegalArgumentException(errorBody?.get("message")?.toString() ?: "Bad request")
                }
                else -> throw IllegalArgumentException("Backend API error: ${response.statusCode()}")
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            log.error("SSL handshake failed: {}", e.message)
            throw IllegalArgumentException(
                "SSL certificate verification failed for $backendUrl. " +
                "If using a self-signed certificate, use --insecure flag or set SECMAN_INSECURE=true"
            )
        } catch (e: javax.net.ssl.SSLException) {
            log.error("SSL error: {}", e.message)
            throw IllegalArgumentException(
                "SSL error connecting to $backendUrl: ${e.message}. " +
                "If using a self-signed certificate, use --insecure flag or set SECMAN_INSECURE=true"
            )
        } catch (e: java.net.ConnectException) {
            throw IllegalArgumentException("Cannot connect to backend at $backendUrl")
        } catch (e: Exception) {
            log.error("Bulk request failed: {}", e.message, e)
            throw IllegalArgumentException("Backend API error: ${e.message}")
        }
    }

    private fun createTrustAllSslContext(): javax.net.ssl.SSLContext {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        return javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }
    }

    /**
     * Fallback for dry-run when bulk endpoint is unavailable.
     * Fetches all DB mappings via the paginated list endpoint and compares locally.
     */
    private fun dryRunFallback(
        entries: List<Map<String, Any?>>,
        backendUrl: String,
        authToken: String
    ): BulkResponse {
        log.info("Performing local dry-run comparison...")
        val dbMappings = listMappings(email = null, status = null, backendUrl = backendUrl, authToken = authToken)

        // Build normalized key sets: Pair(email, awsAccountId-or-domain)
        val fileKeys = entries.map { entry ->
            val email = (entry["email"] as String).lowercase().trim()
            val identifier = (entry["awsAccountId"] as? String)?.trim()
                ?: (entry["domain"] as? String)?.lowercase()?.trim()
                ?: ""
            Pair(email, identifier)
        }.toSet()

        val dbKeys = dbMappings.map { m ->
            val identifier = m.awsAccountId?.trim() ?: m.domain?.lowercase()?.trim() ?: ""
            Pair(m.email.lowercase().trim(), identifier)
        }.toSet()

        val newCount = (fileKeys - dbKeys).size
        val unchangedCount = (fileKeys intersect dbKeys).size
        val removedCount = (dbKeys - fileKeys).size

        return BulkResponse(
            totalProcessed = entries.size,
            created = 0, createdPending = 0, skipped = 0,
            errors = emptyList(),
            comparison = BulkComparisonResponse(
                dbMappingCount = dbMappings.size,
                fileMappingCount = entries.size,
                newCount = newCount,
                unchangedCount = unchangedCount,
                removedCount = removedCount
            )
        )
    }

    /**
     * Fallback for non-dry-run when bulk endpoint is unavailable.
     * Creates each mapping individually via POST /api/user-mappings.
     */
    private fun individualCreateFallback(
        entries: List<Map<String, Any?>>,
        backendUrl: String,
        authToken: String
    ): BulkResponse {
        log.info("Creating {} mappings individually...", entries.size)
        var created = 0; var skipped = 0
        val errors = mutableListOf<String>()

        entries.forEachIndexed { index, entry ->
            try {
                val body = mapOf(
                    "email" to entry["email"],
                    "awsAccountId" to entry["awsAccountId"],
                    "domain" to entry["domain"],
                    "ipAddress" to null
                )
                val request = HttpRequest.POST("$backendUrl/api/user-mappings", body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer $authToken")
                getClient().toBlocking().exchange(request, Map::class.java)
                created++
            } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
                if (e.status.code == 400) skipped++  // duplicate
                else errors.add("${entry["email"]}: ${e.message}")
            } catch (e: Exception) {
                errors.add("${entry["email"]}: ${e.message}")
            }
            if ((index + 1) % 100 == 0) log.info("Progress: {}/{}", index + 1, entries.size)
        }

        return BulkResponse(
            totalProcessed = entries.size,
            created = created, createdPending = 0,
            skipped = skipped, errors = errors, comparison = null
        )
    }

    private fun bulkResponseToMappingResult(
        bulkResponse: BulkResponse,
        entries: List<Map<String, Any?>>
    ): MappingResult {
        val operations = entries.map { entry ->
            MappingOperationResult(
                success = true,
                operation = "CREATED",
                message = "Created",
                email = (entry["email"] ?: "") as String,
                domain = entry["domain"] as? String,
                awsAccountId = entry["awsAccountId"] as? String
            )
        }

        return MappingResult(
            totalProcessed = bulkResponse.totalProcessed,
            created = bulkResponse.created,
            createdPending = bulkResponse.createdPending,
            skipped = bulkResponse.skipped,
            errors = bulkResponse.errors,
            operations = operations
        )
    }

    // --- Feature 085: send-statistics-email ---

    /**
     * Call POST /api/cli/user-mappings/send-statistics-email on the backend.
     *
     * Uses java.net.http.HttpClient (matching [postBulk]) to bypass Micronaut
     * Serde generics issues with Map bodies. Returns a [StatisticsEmailResult]
     * wrapping the HTTP status code and parsed body so callers can distinguish
     * auth denials (403), validation errors (400), and server errors (5xx) to
     * map them to distinct CLI exit codes.
     *
     * Feature: 085-cli-mappings-email
     */
    fun sendStatisticsEmail(
        backendUrl: String,
        authToken: String,
        filterEmail: String?,
        filterStatus: String?,
        dryRun: Boolean,
        verbose: Boolean,
        importSummary: Map<String, Any?>? = null
    ): StatisticsEmailResult {
        val bodyMap: Map<String, Any?> = buildMap {
            if (!filterEmail.isNullOrBlank()) put("filterEmail", filterEmail)
            if (!filterStatus.isNullOrBlank()) put("filterStatus", filterStatus)
            put("dryRun", dryRun)
            put("verbose", verbose)
            if (importSummary != null) put("importSummary", importSummary)
        }
        val jsonBody = objectMapper.writeValueAsString(bodyMap)

        return try {
            val clientBuilder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            if (insecureMode) {
                clientBuilder.sslContext(createTrustAllSslContext())
                val sslParams = javax.net.ssl.SSLParameters()
                sslParams.endpointIdentificationAlgorithm = null
                clientBuilder.sslParameters(sslParams)
            }
            val javaClient = clientBuilder.build()

            val httpRequest = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("$backendUrl/api/cli/user-mappings/send-statistics-email"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $authToken")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(120))
                .build()

            val response = javaClient.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
            val statusCode = response.statusCode()

            @Suppress("UNCHECKED_CAST")
            val responseBody: Map<String, Any?>? = if (response.body().isNullOrBlank()) {
                null
            } else {
                try {
                    objectMapper.readValue(response.body(), Map::class.java) as Map<String, Any?>
                } catch (e: Exception) {
                    log.warn("Failed to parse statistics-email response body: {}", e.message)
                    null
                }
            }

            StatisticsEmailResult(statusCode, responseBody)
        } catch (e: Exception) {
            log.error("send-statistics-email HTTP call failed: {}", e.message)
            StatisticsEmailResult(-1, null)
        }
    }

    // --- Internal DTOs for HTTP responses ---

    private data class BulkResponse(
        val totalProcessed: Int,
        val created: Int,
        val createdPending: Int,
        val skipped: Int,
        val errors: List<String>,
        val comparison: BulkComparisonResponse?
    )

    private data class BulkComparisonResponse(
        val dbMappingCount: Int,
        val fileMappingCount: Int,
        val newCount: Int,
        val unchangedCount: Int,
        val removedCount: Int
    )
}

/**
 * CLI-local response model for user mappings (replaces JPA UserMapping entity)
 */
data class UserMappingCliResponse(
    val id: Long,
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val status: String,
    val createdAt: String,
    val appliedAt: String?
)

/**
 * Result of a single mapping operation
 */
data class MappingOperationResult(
    val success: Boolean,
    val operation: String,
    val message: String,
    val email: String,
    val domain: String? = null,
    val awsAccountId: String? = null,
    val isPending: Boolean = false
)

/**
 * Summary of multiple mapping operations
 */
data class MappingResult(
    val totalProcessed: Int,
    val created: Int,
    val createdPending: Int = 0,
    val skipped: Int,
    val errors: List<String>,
    val operations: List<MappingOperationResult>,
    val comparison: MappingComparisonResult? = null
)

/**
 * Result of comparing file mappings against the database (dry-run only)
 */
data class MappingComparisonResult(
    val dbMappingCount: Int,
    val fileMappingCount: Int,
    val newCount: Int,
    val unchangedCount: Int,
    val removedCount: Int,
    val dbAvailable: Boolean
)

/**
 * Wrapper returned by [UserMappingCliService.sendStatisticsEmail] carrying the
 * HTTP status code and the parsed response body so CLI callers can map distinct
 * failure modes to distinct exit codes.
 *
 * Feature: 085-cli-mappings-email
 */
data class StatisticsEmailResult(
    val statusCode: Int,
    val body: Map<String, Any?>?
)
