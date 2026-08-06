package com.secman.cli.service

import jakarta.inject.Singleton

@Singleton
class UserMappingValidator {
    private val emailRegex = Regex("^[^@]+@[^@]+\\.[^@]+$")
    private val awsAccountIdRegex = Regex("^\\d{12}$")
    private val domainRegex = Regex("^[a-zA-Z0-9.-]+$")

    fun normalizeEmail(email: String): String = email.lowercase().trim()
    fun normalizeDomain(domain: String): String = domain.lowercase().trim()
    fun normalizeAccountId(accountId: String): String = accountId.trim()

    fun validateEmail(email: String): Boolean = emailRegex.matches(email)
    fun validateDomain(domain: String): Boolean = domainRegex.matches(domain)
    fun validateAwsAccountId(accountId: String): Boolean = awsAccountIdRegex.matches(accountId)

    fun validateEmails(emails: List<String>) {
        val invalid = emails.filter { !validateEmail(it) }
        if (invalid.isNotEmpty()) {
            throw IllegalArgumentException("Invalid email format: ${invalid.joinToString()}")
        }
    }

    fun validateDomains(domains: List<String>) {
        val invalid = domains.filter { !validateDomain(it) }
        if (invalid.isNotEmpty()) {
            throw IllegalArgumentException("Invalid domain format: ${invalid.joinToString()}")
        }
    }

    fun validateAwsAccountIds(accountIds: List<String>) {
        val invalid = accountIds.filter { !validateAwsAccountId(it) }
        if (invalid.isNotEmpty()) {
            throw IllegalArgumentException("Invalid AWS account ID (must be 12 digits): ${invalid.joinToString()}")
        }
    }
}
