package com.secman.service

import com.secman.domain.TestEmailAccount
import com.secman.domain.enums.EmailProvider
import com.secman.domain.enums.TestAccountStatus
import com.secman.repository.TestEmailAccountRepository
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.CompletableFuture
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

/**
 * Service for managing test email accounts
 */
@Singleton
class TestEmailAccountService(
    private val testEmailAccountRepository: TestEmailAccountRepository
) {

    private val logger = LoggerFactory.getLogger(TestEmailAccountService::class.java)

    /**
     * Create a new test email account
     */
    suspend fun createTestAccount(
        name: String,
        email: String,
        provider: EmailProvider,
        credentials: String,
        smtpHost: String? = null,
        smtpPort: Int? = null,
        imapHost: String? = null,
        imapPort: Int? = null,
        description: String? = null
    ): TestEmailAccount = withContext(Dispatchers.IO) {
        logger.info("Creating test email account: $name ($email)")

        // Build credentials map from provided parameters
        val credentialsMap = mutableMapOf<String, Any>()

        // Parse credentials JSON if provided
        if (credentials.isNotBlank()) {
            try {
                val parsedCredentials = kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(credentials)
                credentialsMap.putAll(parsedCredentials)
            } catch (e: Exception) {
                logger.warn("Failed to parse credentials JSON: ${e.message}")
            }
        }
        smtpHost?.let { credentialsMap["smtpHost"] = it }
        smtpPort?.let { credentialsMap["smtpPort"] = it }
        imapHost?.let { credentialsMap["imapHost"] = it }
        imapPort?.let { credentialsMap["imapPort"] = it }
        description?.let { credentialsMap["description"] = it }

        val account = TestEmailAccount.create(
            name = name,
            emailAddress = email,
            provider = provider,
            credentialsMap = credentialsMap
        )

        val savedAccount = testEmailAccountRepository.save(account)
        logger.info("Created test email account with ID: ${savedAccount.id}")

        savedAccount
    }

    /**
     * Get all test email accounts
     */
    suspend fun getAllAccounts(): List<TestEmailAccount> = withContext(Dispatchers.IO) {
        testEmailAccountRepository.findAll()
    }

    /**
     * Get test email account by ID
     */
    suspend fun getAccountById(id: Long): TestEmailAccount? = withContext(Dispatchers.IO) {
        testEmailAccountRepository.findById(id).orElse(null)
    }

    /**
     * Get test email accounts by status
     */
    suspend fun getAccountsByStatus(status: TestAccountStatus): List<TestEmailAccount> = withContext(Dispatchers.IO) {
        testEmailAccountRepository.findByStatus(status)
    }

    /**
     * Get test email accounts by provider
     */
    suspend fun getAccountsByProvider(provider: EmailProvider): List<TestEmailAccount> = withContext(Dispatchers.IO) {
        testEmailAccountRepository.findByProvider(provider)
    }

    /**
     * Get active test email accounts
     */
    suspend fun getActiveAccounts(): List<TestEmailAccount> = withContext(Dispatchers.IO) {
        testEmailAccountRepository.findByStatus(TestAccountStatus.ACTIVE)
    }

    /**
     * Update test email account
     */
    suspend fun updateAccount(
        id: Long,
        name: String? = null,
        email: String? = null,
        provider: EmailProvider? = null,
        credentials: String? = null,
        smtpHost: String? = null,
        smtpPort: Int? = null,
        imapHost: String? = null,
        imapPort: Int? = null,
        description: String? = null
    ): TestEmailAccount? = withContext(Dispatchers.IO) {
        val existingAccount = testEmailAccountRepository.findById(id).orElse(null)
            ?: return@withContext null

        logger.info("Updating test email account: $id")

        // Build updated credentials map if any parameter changed
        val currentCreds = existingAccount.getCredentialsMap().toMutableMap()

        // Parse credentials JSON if provided
        credentials?.let { credentialsJson ->
            if (credentialsJson.isNotBlank()) {
                try {
                    val parsedCredentials = kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(credentialsJson)
                    currentCreds.putAll(parsedCredentials)
                } catch (e: Exception) {
                    logger.warn("Failed to parse credentials JSON: ${e.message}")
                }
            }
        }
        smtpHost?.let { currentCreds["smtpHost"] = it }
        smtpPort?.let { currentCreds["smtpPort"] = it }
        imapHost?.let { currentCreds["imapHost"] = it }
        imapPort?.let { currentCreds["imapPort"] = it }
        description?.let { currentCreds["description"] = it }

        val hasCredentialsChanged = credentials != null || smtpHost != null || smtpPort != null ||
                                  imapHost != null || imapPort != null

        val updatedAccount = existingAccount.copy(
            name = name ?: existingAccount.name,
            emailAddress = email ?: existingAccount.emailAddress,
            provider = provider ?: existingAccount.provider,
            credentials = if (hasCredentialsChanged) {
                kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, Any>>(),
                    currentCreds
                )
            } else existingAccount.credentials,
            // Reset status to pending if credentials changed
            status = if (hasCredentialsChanged) TestAccountStatus.VERIFICATION_PENDING else existingAccount.status
        )

        val savedAccount = testEmailAccountRepository.update(updatedAccount)
        logger.info("Updated test email account: $id")

        savedAccount
    }

    /**
     * Delete test email account
     */
    suspend fun deleteAccount(id: Long): Boolean = withContext(Dispatchers.IO) {
        val account = testEmailAccountRepository.findById(id).orElse(null)
            ?: return@withContext false

        logger.info("Deleting test email account: $id (${account.name})")
        testEmailAccountRepository.deleteById(id)

        logger.info("Deleted test email account: $id")
        true
    }

    /**
     * Test email account connectivity
     */
    fun testAccountConnectivity(id: Long): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            try {
                val account = testEmailAccountRepository.findById(id).orElse(null)
                    ?: return@supplyAsync false

                logger.info("Testing connectivity for account: ${account.name}")

                // Update status to testing
                val testingAccount = account.copy(
                    status = TestAccountStatus.VERIFICATION_PENDING,
                    lastTestedAt = LocalDateTime.now()
                )
                testEmailAccountRepository.update(testingAccount)

                // Test SMTP connection
                val smtpSuccess = testSmtpConnection(account)

                // Test IMAP connection if configured
                val imapSuccess = if (account.provider == EmailProvider.IMAP_CUSTOM) {
                    testImapConnection(account)
                } else {
                    true // Not required for non-IMAP providers
                }

                val overallSuccess = smtpSuccess && imapSuccess

                // Update status based on test results
                val finalStatus = if (overallSuccess) {
                    TestAccountStatus.ACTIVE
                } else {
                    TestAccountStatus.FAILED
                }

                val finalAccount = testingAccount.copy(status = finalStatus)
                testEmailAccountRepository.update(finalAccount)

                logger.info("Connectivity test for account ${account.name}: success=$overallSuccess")
                overallSuccess

            } catch (e: Exception) {
                logger.error("Failed to test account connectivity for ID: $id", e)

                // Update status to failed
                try {
                    val account = testEmailAccountRepository.findById(id).orElse(null)
                    account?.let { acc ->
                        val failedAccount = acc.copy(
                            status = TestAccountStatus.FAILED,
                            lastTestedAt = LocalDateTime.now()
                        )
                        testEmailAccountRepository.update(failedAccount)
                    }
                } catch (updateEx: Exception) {
                    logger.error("Failed to update account status after test failure", updateEx)
                }

                false
            }
        }
    }

    /**
     * Test SMTP connection for account
     */
    private fun testSmtpConnection(account: TestEmailAccount): Boolean {
        return try {
            val props = Properties()
            val config = account.getProviderConfig()

            props["mail.smtp.host"] = config["host"] ?: config["smtpHost"] ?: "smtp.gmail.com"
            props["mail.smtp.port"] = config["port"] ?: config["smtpPort"] ?: "587"
            props["mail.smtp.auth"] = "true"
            props["mail.smtp.starttls.enable"] = config["tls"] ?: "true"
            props["mail.smtp.ssl.enable"] = config["ssl"] ?: "false"

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    val username = config["username"]?.toString() ?: ""
                    val password = config["password"]?.toString() ?: ""
                    return PasswordAuthentication(username, password)
                }
            })

            val transport = session.getTransport("smtp")
            transport.connect()
            transport.close()

            logger.debug("SMTP connection successful for account: ${account.name}")
            true

        } catch (e: Exception) {
            logger.warn("SMTP connection failed for account: ${account.name}", e)
            false
        }
    }

    /**
     * Test IMAP connection for account
     */
    private fun testImapConnection(account: TestEmailAccount): Boolean {
        return try {
            val props = Properties()
            val config = account.getProviderConfig()

            // Check if IMAP is configured
            val imapHost = config["imapHost"]?.toString()
            if (imapHost.isNullOrBlank()) return false

            props["mail.imap.host"] = imapHost
            props["mail.imap.port"] = config["imapPort"] ?: "993"
            props["mail.imap.ssl.enable"] = "true"
            props["mail.imap.auth"] = "true"

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    val username = config["username"]?.toString() ?: ""
                    val password = config["password"]?.toString() ?: ""
                    return PasswordAuthentication(username, password)
                }
            })

            val store = session.getStore("imap")
            store.connect()
            store.close()

            logger.debug("IMAP connection successful for account: ${account.name}")
            true

        } catch (e: Exception) {
            logger.warn("IMAP connection failed for account: ${account.name}", e)
            false
        }
    }

    /**
     * Send test email from account
     */
    fun sendTestEmail(id: Long, toEmail: String, subject: String = "Test Email"): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            try {
                val account = testEmailAccountRepository.findById(id).orElse(null)
                    ?: return@supplyAsync false

                if (account.status != TestAccountStatus.ACTIVE) {
                    logger.warn("Cannot send test email from unverified account: ${account.name}")
                    return@supplyAsync false
                }

                logger.info("Sending test email from account: ${account.name} to: $toEmail")

                val props = Properties()
                val config = account.getProviderConfig()

                props["mail.smtp.host"] = config["host"] ?: config["smtpHost"] ?: "smtp.gmail.com"
                props["mail.smtp.port"] = config["port"] ?: config["smtpPort"] ?: "587"
                props["mail.smtp.auth"] = "true"
                props["mail.smtp.starttls.enable"] = config["tls"] ?: "true"
                props["mail.smtp.ssl.enable"] = config["ssl"] ?: "false"

                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        val username = config["username"]?.toString() ?: ""
                        val password = config["password"]?.toString() ?: ""
                        return PasswordAuthentication(username, password)
                    }
                })

                val message = MimeMessage(session)
                message.setFrom(InternetAddress(account.emailAddress, account.name))
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                message.subject = subject
                message.sentDate = Date()

                val textContent = """
                    This is a test email from SecMan Test Email Account: ${account.name}

                    Account Details:
                    - Name: ${account.name}
                    - Email: ${account.emailAddress}
                    - Provider: ${account.provider}
                    - Test Date: ${LocalDateTime.now()}

                    If you received this email, the test account is working correctly.

                    ---
                    SecMan Security Management System
                """.trimIndent()

                message.setText(textContent)

                Transport.send(message)

                // Update last tested timestamp
                val updatedAccount = account.copy(lastTestedAt = LocalDateTime.now())
                testEmailAccountRepository.update(updatedAccount)

                logger.info("Test email sent successfully from account: ${account.name}")
                true

            } catch (e: Exception) {
                logger.error("Failed to send test email from account ID: $id", e)
                false
            }
        }
    }

    /**
     * Get account statistics
     */
    suspend fun getAccountStatistics(): Map<String, Any> = withContext(Dispatchers.IO) {
        val totalAccounts = testEmailAccountRepository.count()
        val verifiedAccounts = testEmailAccountRepository.countByStatus(TestAccountStatus.ACTIVE)
        val pendingAccounts = testEmailAccountRepository.countByStatus(TestAccountStatus.VERIFICATION_PENDING)
        val failedAccounts = testEmailAccountRepository.countByStatus(TestAccountStatus.FAILED)

        val providerDistribution = EmailProvider.values().associate { provider ->
            provider.name to testEmailAccountRepository.countByProvider(provider)
        }

        mapOf(
            "total" to totalAccounts,
            "verified" to verifiedAccounts,
            "pending" to pendingAccounts,
            "failed" to failedAccounts,
            "providerDistribution" to providerDistribution
        )
    }

    /**
     * Validate account configuration
     */
    suspend fun validateAccountConfiguration(account: TestEmailAccount): List<String> = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        // Basic validation
        errors.addAll(account.validate())

        // Provider-specific validation
        val config = account.getProviderConfig()
        when (account.provider) {
            EmailProvider.GMAIL -> {
                val host = config["host"]?.toString()
                val port = config["port"]?.toString()?.toIntOrNull()
                if (host != null && host != "smtp.gmail.com") {
                    errors.add("Gmail accounts should use smtp.gmail.com as SMTP host")
                }
                if (port != null && port != 587 && port != 465) {
                    errors.add("Gmail accounts should use port 587 (TLS) or 465 (SSL)")
                }
            }
            EmailProvider.OUTLOOK -> {
                val host = config["host"]?.toString()
                val port = config["port"]?.toString()?.toIntOrNull()
                if (host != null && host != "smtp-mail.outlook.com") {
                    errors.add("Outlook accounts should use smtp-mail.outlook.com as SMTP host")
                }
                if (port != null && port != 587) {
                    errors.add("Outlook accounts should use port 587")
                }
            }
            EmailProvider.YAHOO -> {
                val host = config["host"]?.toString()
                val port = config["port"]?.toString()?.toIntOrNull()
                if (host != null && host != "smtp.mail.yahoo.com") {
                    errors.add("Yahoo accounts should use smtp.mail.yahoo.com as SMTP host")
                }
                if (port != null && port != 587 && port != 465) {
                    errors.add("Yahoo accounts should use port 587 (TLS) or 465 (SSL)")
                }
            }
            EmailProvider.SMTP_CUSTOM -> {
                val host = config["host"]?.toString()
                val port = config["port"]?.toString()?.toIntOrNull()
                if (host.isNullOrBlank()) {
                    errors.add("Custom SMTP accounts must specify SMTP host")
                }
                if (port == null || port !in 1..65535) {
                    errors.add("Custom SMTP accounts must specify valid SMTP port")
                }
            }
            EmailProvider.IMAP_CUSTOM -> {
                val host = config["imapHost"]?.toString()
                val port = config["imapPort"]?.toString()?.toIntOrNull()
                if (host.isNullOrBlank()) {
                    errors.add("Custom IMAP accounts must specify IMAP host")
                }
                if (port == null || port !in 1..65535) {
                    errors.add("Custom IMAP accounts must specify valid IMAP port")
                }
            }
        }

        errors
    }

    /**
     * Bulk test accounts
     */
    fun bulkTestAccounts(accountIds: List<Long>): CompletableFuture<Map<Long, Boolean>> {
        return CompletableFuture.supplyAsync {
            val results = mutableMapOf<Long, Boolean>()

            accountIds.forEach { id ->
                try {
                    val result = testAccountConnectivity(id).get()
                    results[id] = result
                } catch (e: Exception) {
                    logger.error("Failed to test account $id in bulk operation", e)
                    results[id] = false
                }
            }

            results
        }
    }

    /**
     * Search accounts by name or email
     */
    suspend fun searchAccounts(query: String): List<TestEmailAccount> = withContext(Dispatchers.IO) {
        testEmailAccountRepository.findByNameContainingIgnoreCase(query)
    }

    /**
     * Get accounts requiring verification
     */
    suspend fun getAccountsRequiringVerification(): List<TestEmailAccount> = withContext(Dispatchers.IO) {
        testEmailAccountRepository.findByStatus(TestAccountStatus.VERIFICATION_PENDING)
    }

    /**
     * Mark account as needs verification
     */
    suspend fun markAccountForVerification(id: Long): Boolean = withContext(Dispatchers.IO) {
        val account = testEmailAccountRepository.findById(id).orElse(null)
            ?: return@withContext false

        val updatedAccount = account.copy(status = TestAccountStatus.VERIFICATION_PENDING)
        testEmailAccountRepository.update(updatedAccount)

        logger.info("Marked account for verification: ${account.name}")
        true
    }
}