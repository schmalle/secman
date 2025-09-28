package com.secman.controller

import com.secman.domain.TestEmailAccount
import com.secman.domain.enums.EmailProvider
import com.secman.domain.enums.TestAccountStatus
import com.secman.service.TestEmailAccountService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * Controller for test email account management
 */
@Controller("/api/test-email-accounts")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.IO)
open class TestEmailAccountController(
    private val testEmailAccountService: TestEmailAccountService
) {

    private val logger = LoggerFactory.getLogger(TestEmailAccountController::class.java)

    /**
     * Get all test email accounts
     * GET /api/test-email-accounts
     */
    @Get
    open fun getTestEmailAccounts(
        @QueryValue status: String?,
        @QueryValue provider: String?
    ): HttpResponse<List<TestEmailAccount>> {
        return runBlocking {
            try {
                val accounts = when {
                    status != null -> {
                        val accountStatus = TestAccountStatus.valueOf(status.uppercase())
                        testEmailAccountService.getAccountsByStatus(accountStatus)
                    }
                    provider != null -> {
                        val emailProvider = EmailProvider.valueOf(provider.uppercase())
                        testEmailAccountService.getAccountsByProvider(emailProvider)
                    }
                    else -> {
                        testEmailAccountService.getAllAccounts()
                    }
                }

                // Return safe versions (with masked credentials)
                val safeAccounts: List<TestEmailAccount> = accounts.map { it.toSafeResponse() }
                HttpResponse.ok<List<TestEmailAccount>>(safeAccounts)

            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid parameter in test accounts request: ${e.message}")
                HttpResponse.badRequest()
            } catch (e: Exception) {
                logger.error("Failed to retrieve test email accounts", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Get test email account by ID
     * GET /api/test-email-accounts/{id}
     */
    @Get("/{id}")
    open fun getTestEmailAccount(@PathVariable id: Long): HttpResponse<TestEmailAccount> {
        return runBlocking {
            try {
                val account = testEmailAccountService.getAccountById(id)
                    ?: return@runBlocking HttpResponse.notFound<TestEmailAccount>()

                // Return safe version (with masked credentials)
                HttpResponse.ok(account.toSafeResponse())

            } catch (e: Exception) {
                logger.error("Failed to retrieve test email account: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Create new test email account
     * POST /api/test-email-accounts
     */
    @Post
    open fun createTestEmailAccount(@Body @Valid request: CreateTestEmailAccountRequest): HttpResponse<TestEmailAccount> {
        return runBlocking {
            try {
                val account = testEmailAccountService.createTestAccount(
                    name = request.name,
                    email = request.email,
                    provider = request.provider,
                    credentials = request.credentials,
                    smtpHost = request.smtpHost,
                    smtpPort = request.smtpPort,
                    imapHost = request.imapHost,
                    imapPort = request.imapPort,
                    description = request.description
                )

                logger.info("Created test email account: ${account.name}")

                // Return safe version (with masked credentials)
                HttpResponse.created(account.toSafeResponse())

            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid test email account request: ${e.message}")
                HttpResponse.badRequest<TestEmailAccount>()
            } catch (e: Exception) {
                logger.error("Failed to create test email account", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Update test email account
     * PUT /api/test-email-accounts/{id}
     */
    @Put("/{id}")
    open fun updateTestEmailAccount(
        @PathVariable id: Long,
        @Body @Valid request: UpdateTestEmailAccountRequest
    ): HttpResponse<TestEmailAccount> {
        return runBlocking {
            try {
                val account = testEmailAccountService.updateAccount(
                    id = id,
                    name = request.name,
                    email = request.email,
                    provider = request.provider,
                    credentials = request.credentials,
                    smtpHost = request.smtpHost,
                    smtpPort = request.smtpPort,
                    imapHost = request.imapHost,
                    imapPort = request.imapPort,
                    description = request.description
                ) ?: return@runBlocking HttpResponse.notFound<TestEmailAccount>()

                logger.info("Updated test email account: $id")

                // Return safe version (with masked credentials)
                HttpResponse.ok(account.toSafeResponse())

            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid test email account update: ${e.message}")
                HttpResponse.badRequest<TestEmailAccount>()
            } catch (e: Exception) {
                logger.error("Failed to update test email account: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Delete test email account
     * DELETE /api/test-email-accounts/{id}
     */
    @Delete("/{id}")
    open fun deleteTestEmailAccount(@PathVariable id: Long): HttpResponse<Map<String, Any>> {
        return runBlocking {
            try {
                val deleted = testEmailAccountService.deleteAccount(id)
                if (deleted) {
                    logger.info("Deleted test email account: $id")
                    HttpResponse.ok(mapOf("message" to "Test account deleted successfully"))
                } else {
                    HttpResponse.notFound<Map<String, Any>>()
                }
            } catch (e: Exception) {
                logger.error("Failed to delete test email account: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Test email account connectivity
     * POST /api/test-email-accounts/{id}/test
     */
    @Post("/{id}/test")
    open fun testEmailAccountConnectivity(@PathVariable id: Long): HttpResponse<Map<String, Any>> {
        return try {
            logger.info("Testing connectivity for test email account: $id")

            val future = testEmailAccountService.testAccountConnectivity(id)
            val success = future.get()

            val message = if (success) {
                "Test account connectivity successful"
            } else {
                "Test account connectivity failed"
            }

            logger.info("Test account connectivity result for {}: {}", id, success)

            HttpResponse.ok(mapOf(
                "accountId" to id,
                "success" to success,
                "message" to message,
                "testedAt" to System.currentTimeMillis()
            ))

        } catch (e: Exception) {
            logger.error("Failed to test account connectivity: $id", e)
            HttpResponse.serverError()
        }
    }

    /**
     * Send test email from account
     * POST /api/test-email-accounts/{id}/send-test
     */
    @Post("/{id}/send-test")
    open fun sendTestEmail(
        @PathVariable id: Long,
        @Body @Valid request: SendTestEmailRequest
    ): HttpResponse<Map<String, Any>> {
        return try {
            logger.info("Sending test email from account {} to {}", id, request.toEmail)

            val future = testEmailAccountService.sendTestEmail(
                id = id,
                toEmail = request.toEmail,
                subject = request.subject ?: "Test Email from SecMan"
            )
            val success = future.get()

            val message = if (success) {
                "Test email sent successfully"
            } else {
                "Failed to send test email"
            }

            logger.info("Test email result for account {} to {}: {}", id, request.toEmail, success)

            HttpResponse.ok(mapOf(
                "accountId" to id,
                "recipient" to request.toEmail,
                "success" to success,
                "message" to message,
                "sentAt" to System.currentTimeMillis()
            ))

        } catch (e: Exception) {
            logger.error("Failed to send test email from account: $id", e)
            HttpResponse.serverError()
        }
    }

    /**
     * Get active test email accounts
     * GET /api/test-email-accounts/active
     */
    @Get("/active")
    open fun getActiveTestEmailAccounts(): HttpResponse<List<TestEmailAccount>> {
        return runBlocking {
            try {
                val accounts = testEmailAccountService.getActiveAccounts()

                // Return safe versions (with masked credentials)
                val safeAccounts = accounts.map { it.toSafeResponse() }
                HttpResponse.ok(safeAccounts)

            } catch (e: Exception) {
                logger.error("Failed to retrieve active test email accounts", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Get test email account statistics
     * GET /api/test-email-accounts/stats
     */
    @Get("/stats")
    open fun getTestEmailAccountStatistics(): HttpResponse<Map<String, Any>> {
        return runBlocking {
            try {
                val stats = testEmailAccountService.getAccountStatistics()
                HttpResponse.ok(stats)

            } catch (e: Exception) {
                logger.error("Failed to retrieve test email account statistics", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Search test email accounts
     * GET /api/test-email-accounts/search
     */
    @Get("/search")
    open fun searchTestEmailAccounts(@QueryValue q: String): HttpResponse<List<TestEmailAccount>> {
        return runBlocking {
            try {
                val accounts = testEmailAccountService.searchAccounts(q)

                // Return safe versions (with masked credentials)
                val safeAccounts = accounts.map { it.toSafeResponse() }
                HttpResponse.ok(safeAccounts)

            } catch (e: Exception) {
                logger.error("Failed to search test email accounts", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Validate test email account configuration
     * POST /api/test-email-accounts/{id}/validate
     */
    @Post("/{id}/validate")
    open fun validateTestEmailAccount(@PathVariable id: Long): HttpResponse<Map<String, Any>> {
        return runBlocking {
            try {
                val account = testEmailAccountService.getAccountById(id)
                    ?: return@runBlocking HttpResponse.notFound<Map<String, Any>>()

                val validationErrors = testEmailAccountService.validateAccountConfiguration(account)

                val isValid = validationErrors.isEmpty()
                val message = if (isValid) {
                    "Account configuration is valid"
                } else {
                    "Account configuration has errors"
                }

                HttpResponse.ok(mapOf(
                    "accountId" to id,
                    "isValid" to isValid,
                    "message" to message,
                    "errors" to validationErrors,
                    "validatedAt" to System.currentTimeMillis()
                ))

            } catch (e: Exception) {
                logger.error("Failed to validate test email account: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Bulk test multiple accounts
     * POST /api/test-email-accounts/bulk-test
     */
    @Post("/bulk-test")
    open fun bulkTestAccounts(@Body @Valid request: BulkTestAccountsRequest): HttpResponse<Map<String, Any>> {
        return try {
            logger.info("Bulk testing {} accounts", request.accountIds.size)

            val future = testEmailAccountService.bulkTestAccounts(request.accountIds)
            val results = future.get()

            val successCount = results.values.count { it }

            logger.info("Bulk test completed: {} successful out of {} accounts", successCount, request.accountIds.size)

            HttpResponse.ok(mapOf(
                "message" to "Bulk test completed",
                "total" to request.accountIds.size,
                "successful" to successCount,
                "results" to results,
                "testedAt" to System.currentTimeMillis()
            ))

        } catch (e: Exception) {
            logger.error("Failed to perform bulk test", e)
            HttpResponse.serverError()
        }
    }

    /**
     * Mark account for verification
     * POST /api/test-email-accounts/{id}/mark-for-verification
     */
    @Post("/{id}/mark-for-verification")
    open fun markAccountForVerification(@PathVariable id: Long): HttpResponse<Map<String, Any>> {
        return runBlocking {
            try {
                val marked = testEmailAccountService.markAccountForVerification(id)
                if (marked) {
                    logger.info("Marked test email account for verification: $id")
                    HttpResponse.ok(mapOf("message" to "Account marked for verification"))
                } else {
                    HttpResponse.notFound<Map<String, Any>>()
                }
            } catch (e: Exception) {
                logger.error("Failed to mark account for verification: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Get accounts requiring verification
     * GET /api/test-email-accounts/requiring-verification
     */
    @Get("/requiring-verification")
    open fun getAccountsRequiringVerification(): HttpResponse<List<TestEmailAccount>> {
        return runBlocking {
            try {
                val accounts = testEmailAccountService.getAccountsRequiringVerification()

                // Return safe versions (with masked credentials)
                val safeAccounts = accounts.map { it.toSafeResponse() }
                HttpResponse.ok(safeAccounts)

            } catch (e: Exception) {
                logger.error("Failed to retrieve accounts requiring verification", e)
                HttpResponse.serverError()
            }
        }
    }

    // Request/Response DTOs

    data class CreateTestEmailAccountRequest(
        @field:NotBlank val name: String,
        @field:NotBlank @field:Email val email: String,
        @field:NotNull val provider: EmailProvider,
        @field:NotBlank val credentials: String,
        val smtpHost: String? = null,
        val smtpPort: Int? = null,
        val imapHost: String? = null,
        val imapPort: Int? = null,
        val description: String? = null
    )

    data class UpdateTestEmailAccountRequest(
        val name: String? = null,
        @field:Email val email: String? = null,
        val provider: EmailProvider? = null,
        val credentials: String? = null,
        val smtpHost: String? = null,
        val smtpPort: Int? = null,
        val imapHost: String? = null,
        val imapPort: Int? = null,
        val description: String? = null
    )

    data class SendTestEmailRequest(
        @field:NotBlank @field:Email val toEmail: String,
        val subject: String? = null
    )

    data class BulkTestAccountsRequest(
        @field:NotNull val accountIds: List<Long>
    )
}