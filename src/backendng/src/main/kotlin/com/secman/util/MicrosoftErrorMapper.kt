package com.secman.util

import jakarta.inject.Singleton

/**
 * Maps Microsoft Azure AD error codes (AADSTS) to user-friendly messages
 */
@Singleton
class MicrosoftErrorMapper {

    private val errorMap = mapOf(
        "AADSTS50020" to "User account not found in this tenant. Please contact your administrator.",
        "AADSTS50034" to "User account does not exist. Please contact your administrator.",
        "AADSTS50053" to "Account is locked. Please contact your administrator.",
        "AADSTS50055" to "Password expired. Please reset your password.",
        "AADSTS50056" to "Invalid or null password. Please enter your password.",
        "AADSTS50057" to "User disabled. Please contact your administrator.",
        "AADSTS50058" to "Silent sign-in failed. Please try again.",
        "AADSTS50105" to "User not assigned to application. Please contact your administrator.",
        "AADSTS50126" to "Invalid username or password.",
        "AADSTS50128" to "Invalid tenant. Please verify configuration.",
        "AADSTS50173" to "Fresh authentication required. Please sign in again.",
        "AADSTS65001" to "User has not consented to application. Please grant permissions.",
        "AADSTS70000" to "Invalid grant. Please try again.",
        "AADSTS700016" to "Application not found in tenant. Please verify configuration."
    )

    /**
     * Map AADSTS error code to user-friendly message
     * @param errorCode The Microsoft error code (e.g., "AADSTS50020")
     * @param errorDescription Optional error description from Microsoft
     * @return User-friendly error message
     */
    fun mapError(errorCode: String?, errorDescription: String? = null): String {
        if (errorCode.isNullOrBlank()) {
            return errorDescription ?: "Authentication failed. Please try again."
        }

        // Extract AADSTS code if present in error description
        val aadstsCode = extractAadstsCode(errorCode) ?: extractAadstsCode(errorDescription)

        return if (aadstsCode != null && errorMap.containsKey(aadstsCode)) {
            errorMap[aadstsCode]!!
        } else {
            // Return original description or generic message
            errorDescription ?: "Authentication failed. Please try again or contact your administrator."
        }
    }

    /**
     * Extract AADSTS error code from text
     * @param text Text that may contain an AADSTS code
     * @return Extracted AADSTS code or null
     */
    private fun extractAadstsCode(text: String?): String? {
        if (text.isNullOrBlank()) return null

        val aadstsRegex = Regex("AADSTS\\d{5,6}")
        val match = aadstsRegex.find(text)
        return match?.value
    }

    /**
     * Check if error is a tenant mismatch error
     * @param errorCode The error code
     * @return True if the error indicates a tenant mismatch
     */
    fun isTenantMismatchError(errorCode: String?): Boolean {
        return errorCode?.contains("AADSTS50128") == true ||
               errorCode?.contains("AADSTS700016") == true
    }
}
