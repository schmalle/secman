package com.secman.service

import com.secman.domain.User
import jakarta.inject.Singleton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Email template generator for admin notifications
 * Feature: 027-admin-user-notifications
 *
 * Generates professionally formatted HTML email templates for notifying
 * ADMIN users when new users are created.
 */
@Singleton
class AdminNotificationEmailGenerator {

    private val dateFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss z")
        .withZone(ZoneId.systemDefault())

    /**
     * Generate email subject line for new user notification
     *
     * @param newUsername Username of the newly created user
     * @return Email subject line
     */
    fun generateSubject(newUsername: String): String {
        return "New User Registered: $newUsername"
    }

    /**
     * Generate HTML email body for manual user creation
     *
     * @param newUser The newly created user
     * @param createdByUsername Username of the admin who created the user
     * @param registrationTimestamp When the user was created
     * @return HTML email content
     */
    fun generateManualCreationEmail(
        newUser: User,
        createdByUsername: String,
        registrationTimestamp: Instant
    ): String {
        val formattedTimestamp = dateFormatter.format(registrationTimestamp)

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>New User Registered</title>
    <style>
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            line-height: 1.6;
            color: #333;
            max-width: 600px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f4f4f4;
        }
        .email-container {
            background-color: #ffffff;
            border-radius: 8px;
            padding: 30px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .header {
            border-bottom: 3px solid #0066cc;
            padding-bottom: 20px;
            margin-bottom: 30px;
        }
        .header h1 {
            color: #0066cc;
            margin: 0;
            font-size: 24px;
        }
        .badge {
            display: inline-block;
            padding: 4px 12px;
            border-radius: 4px;
            font-size: 12px;
            font-weight: bold;
            text-transform: uppercase;
            background-color: #0066cc;
            color: white;
        }
        .info-table {
            width: 100%;
            border-collapse: collapse;
            margin: 20px 0;
        }
        .info-table td {
            padding: 12px;
            border-bottom: 1px solid #eee;
        }
        .info-table td:first-child {
            font-weight: bold;
            color: #666;
            width: 40%;
        }
        .footer {
            margin-top: 30px;
            padding-top: 20px;
            border-top: 1px solid #eee;
            text-align: center;
            color: #999;
            font-size: 12px;
        }
        .footer p {
            margin: 5px 0;
        }
    </style>
</head>
<body>
    <div class="email-container">
        <div class="header">
            <h1>🔔 New User Registered</h1>
            <p style="margin: 10px 0 0 0; color: #666;">
                <span class="badge">Manual Creation</span>
            </p>
        </div>

        <p>A new user has been created in Secman through the "Manage Users" interface.</p>

        <table class="info-table">
            <tr>
                <td>Username</td>
                <td><strong>${escapeHtml(newUser.username)}</strong></td>
            </tr>
            <tr>
                <td>Email Address</td>
                <td>${escapeHtml(newUser.email)}</td>
            </tr>
            <tr>
                <td>Roles</td>
                <td>${newUser.roles.joinToString(", ") { it.name }}</td>
            </tr>
            <tr>
                <td>Registration Method</td>
                <td>Manual (Admin UI)</td>
            </tr>
            <tr>
                <td>Created By</td>
                <td>${escapeHtml(createdByUsername)}</td>
            </tr>
            <tr>
                <td>Registration Time</td>
                <td>$formattedTimestamp</td>
            </tr>
        </table>

        <p style="margin-top: 30px; color: #666;">
            <strong>Note:</strong> This is an automated notification sent to all users with the ADMIN role.
            If you believe this user should not have been created, please contact the administrator who created the account.
        </p>

        <div class="footer">
            <p><strong>Secman</strong> - Security Management Platform</p>
            <p>This is an automated message. Please do not reply to this email.</p>
        </div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Generate HTML email body for OAuth user registration
     *
     * @param newUser The newly created user
     * @param oauthProvider OAuth provider name (e.g., "GitHub", "Google")
     * @param registrationTimestamp When the user registered
     * @return HTML email content
     */
    fun generateOAuthRegistrationEmail(
        newUser: User,
        oauthProvider: String,
        registrationTimestamp: Instant
    ): String {
        val formattedTimestamp = dateFormatter.format(registrationTimestamp)

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>New User Registered</title>
    <style>
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            line-height: 1.6;
            color: #333;
            max-width: 600px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f4f4f4;
        }
        .email-container {
            background-color: #ffffff;
            border-radius: 8px;
            padding: 30px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .header {
            border-bottom: 3px solid #28a745;
            padding-bottom: 20px;
            margin-bottom: 30px;
        }
        .header h1 {
            color: #28a745;
            margin: 0;
            font-size: 24px;
        }
        .badge {
            display: inline-block;
            padding: 4px 12px;
            border-radius: 4px;
            font-size: 12px;
            font-weight: bold;
            text-transform: uppercase;
            background-color: #28a745;
            color: white;
        }
        .info-table {
            width: 100%;
            border-collapse: collapse;
            margin: 20px 0;
        }
        .info-table td {
            padding: 12px;
            border-bottom: 1px solid #eee;
        }
        .info-table td:first-child {
            font-weight: bold;
            color: #666;
            width: 40%;
        }
        .footer {
            margin-top: 30px;
            padding-top: 20px;
            border-top: 1px solid #eee;
            text-align: center;
            color: #999;
            font-size: 12px;
        }
        .footer p {
            margin: 5px 0;
        }
    </style>
</head>
<body>
    <div class="email-container">
        <div class="header">
            <h1>🔔 New User Registered</h1>
            <p style="margin: 10px 0 0 0; color: #666;">
                <span class="badge">OAuth Registration</span>
            </p>
        </div>

        <p>A new user has registered in Secman using OAuth authentication.</p>

        <table class="info-table">
            <tr>
                <td>Username</td>
                <td><strong>${escapeHtml(newUser.username)}</strong></td>
            </tr>
            <tr>
                <td>Email Address</td>
                <td>${escapeHtml(newUser.email)}</td>
            </tr>
            <tr>
                <td>Roles</td>
                <td>${newUser.roles.joinToString(", ") { it.name }}</td>
            </tr>
            <tr>
                <td>Registration Method</td>
                <td>OAuth ($oauthProvider)</td>
            </tr>
            <tr>
                <td>Provider</td>
                <td>${escapeHtml(oauthProvider)}</td>
            </tr>
            <tr>
                <td>Registration Time</td>
                <td>$formattedTimestamp</td>
            </tr>
        </table>

        <p style="margin-top: 30px; color: #666;">
            <strong>Note:</strong> This is an automated notification sent to all users with the ADMIN role.
            This user self-registered through OAuth authentication.
        </p>

        <div class="footer">
            <p><strong>Secman</strong> - Security Management Platform</p>
            <p>This is an automated message. Please do not reply to this email.</p>
        </div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Escape HTML special characters to prevent XSS
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
