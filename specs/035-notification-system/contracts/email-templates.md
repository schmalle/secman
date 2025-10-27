# Email Templates: Outdated Asset Notification System

**Feature**: [035-notification-system](../spec.md)
**Date**: 2025-10-26

## Overview

This document specifies the email templates for the notification system. Three templates are required: Level 1 outdated reminder, Level 2 outdated reminder, and new vulnerability notification.

## Template Storage

**Location**: `src/backendng/src/main/resources/email-templates/`

**Format**: Thymeleaf HTML templates with plain-text fallbacks

**Naming Convention**:
- HTML: `{template-name}.html`
- Plain-text: `{template-name}.txt`

## Template List

1. `outdated-reminder-level1.html` / `.txt`
2. `outdated-reminder-level2.html` / `.txt`
3. `new-vulnerabilities.html` / `.txt`

---

## Template 1: Level 1 Outdated Reminder

**Filename**: `outdated-reminder-level1.html`

**Tone**: Professional, informative, actionable

**Subject Line**: `Action Requested: [[${totalCount}]] Outdated Asset(s) Detected`

**Template Variables** (Thymeleaf context):

```kotlin
data class EmailContext(
    val recipientEmail: String,
    val recipientName: String?,
    val assets: List<AssetEmailData>,
    val totalCount: Int,
    val criticalCount: Int,
    val highCount: Int,
    val mediumCount: Int,
    val lowCount: Int,
    val dashboardUrl: String
)

data class AssetEmailData(
    val id: Long,
    val name: String,
    val type: String,
    val vulnerabilityCount: Int,
    val oldestVulnDays: Int,
    val oldestVulnId: String
)
```

**HTML Structure** (pseudo-code):

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Outdated Assets - Action Requested</title>
    <style>
        /* Inline CSS (email clients don't support external stylesheets) */
        body { font-family: Arial, sans-serif; color: #333; background-color: #f5f5f5; }
        .container { max-width: 600px; margin: 20px auto; background: #fff; padding: 20px; border-radius: 8px; }
        .header { background-color: #FFA500; color: white; padding: 15px; border-radius: 4px; }
        .severity-badge { padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; }
        .critical { background-color: #dc3545; color: white; }
        .high { background-color: #fd7e14; color: white; }
        .medium { background-color: #ffc107; color: black; }
        .low { background-color: #28a745; color: white; }
        table { width: 100%; border-collapse: collapse; margin: 15px 0; }
        th, td { padding: 10px; text-align: left; border-bottom: 1px solid #ddd; }
        th { background-color: #f8f9fa; font-weight: bold; }
        .cta-button { display: inline-block; padding: 12px 24px; background-color: #007bff; color: white; text-decoration: none; border-radius: 4px; margin: 15px 0; }
        .footer { color: #666; font-size: 12px; margin-top: 20px; border-top: 1px solid #ddd; padding-top: 15px; }
    </style>
</head>
<body>
    <div class="container">
        <!-- Header -->
        <div class="header">
            <h2>⚠️ Action Requested: Outdated Assets Detected</h2>
        </div>

        <!-- Greeting -->
        <p>Hello <span th:text="${recipientName} ?: 'Asset Owner'">Asset Owner</span>,</p>

        <!-- Summary -->
        <p>
            We have identified <strong th:text="${totalCount}">5</strong> asset(s) under your ownership that have
            vulnerabilities exceeding the acceptable remediation timeframe. Please review and address these
            vulnerabilities at your earliest convenience.
        </p>

        <!-- Severity Breakdown -->
        <div style="background-color: #f8f9fa; padding: 15px; border-radius: 4px; margin: 15px 0;">
            <h3>Severity Breakdown</h3>
            <p>
                <span class="severity-badge critical" th:if="${criticalCount > 0}">
                    <span th:text="${criticalCount}">2</span> Critical
                </span>
                <span class="severity-badge high" th:if="${highCount > 0}">
                    <span th:text="${highCount}">3</span> High
                </span>
                <span class="severity-badge medium" th:if="${mediumCount > 0}">
                    <span th:text="${mediumCount}">5</span> Medium
                </span>
                <span class="severity-badge low" th:if="${lowCount > 0}">
                    <span th:text="${lowCount}">1</span> Low
                </span>
            </p>
        </div>

        <!-- Asset Table -->
        <h3>Affected Assets</h3>
        <table>
            <thead>
                <tr>
                    <th>Asset Name</th>
                    <th>Type</th>
                    <th>Vulnerabilities</th>
                    <th>Oldest Vuln Age</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="asset : ${assets}">
                    <td th:text="${asset.name}">web-server-01</td>
                    <td th:text="${asset.type}">SERVER</td>
                    <td th:text="${asset.vulnerabilityCount}">8</td>
                    <td>
                        <span th:text="${asset.oldestVulnDays}">45</span> days
                        <span style="color: #666; font-size: 12px;" th:text="'(' + ${asset.oldestVulnId} + ')'">(CVE-2024-1234)</span>
                    </td>
                </tr>
            </tbody>
        </table>

        <!-- Call to Action -->
        <div style="text-align: center; margin: 25px 0;">
            <a th:href="${dashboardUrl}" class="cta-button">View Assets in Dashboard →</a>
        </div>

        <!-- Next Steps -->
        <div style="background-color: #e7f3ff; padding: 15px; border-left: 4px solid #007bff; margin: 15px 0;">
            <h4>Recommended Actions</h4>
            <ol>
                <li>Review the vulnerabilities for each asset in the dashboard</li>
                <li>Prioritize remediation based on severity and exploitability</li>
                <li>Apply patches or implement mitigations within 7 days</li>
                <li>Document any exceptions if remediation is not feasible</li>
            </ol>
        </div>

        <!-- Reminder -->
        <p style="color: #666; font-size: 14px;">
            <strong>Note:</strong> If these assets remain outdated after 7 days, you will receive an escalated
            reminder. Please address these vulnerabilities promptly to maintain compliance.
        </p>

        <!-- Footer -->
        <div class="footer">
            <p>
                This is an automated notification from the Security Management System.<br>
                If you have questions, please contact the security team.<br>
                <em>Do not reply to this email.</em>
            </p>
        </div>
    </div>
</body>
</html>
```

**Plain-Text Version** (`outdated-reminder-level1.txt`):

```
ACTION REQUESTED: OUTDATED ASSETS DETECTED
===========================================

Hello [[${recipientName} ?: 'Asset Owner']],

We have identified [[${totalCount}]] asset(s) under your ownership that have vulnerabilities
exceeding the acceptable remediation timeframe. Please review and address these vulnerabilities
at your earliest convenience.

SEVERITY BREAKDOWN
------------------
Critical: [[${criticalCount}]]
High: [[${highCount}]]
Medium: [[${mediumCount}]]
Low: [[${lowCount}]]

AFFECTED ASSETS
---------------
[# th:each="asset : ${assets}"]
- [[${asset.name}]] ([[${asset.type}]]) - [[${asset.vulnerabilityCount}]] vulnerabilities,
  oldest: [[${asset.oldestVulnDays}]] days ([[${asset.oldestVulnId}]])
[/]

RECOMMENDED ACTIONS
-------------------
1. Review the vulnerabilities for each asset in the dashboard: [[${dashboardUrl}]]
2. Prioritize remediation based on severity and exploitability
3. Apply patches or implement mitigations within 7 days
4. Document any exceptions if remediation is not feasible

NOTE: If these assets remain outdated after 7 days, you will receive an escalated reminder.
Please address these vulnerabilities promptly to maintain compliance.

---
This is an automated notification from the Security Management System.
If you have questions, please contact the security team.
Do not reply to this email.
```

---

## Template 2: Level 2 Outdated Reminder

**Filename**: `outdated-reminder-level2.html`

**Tone**: Urgent, escalation, compliance-focused

**Subject Line**: `⚠️ URGENT: [[${totalCount}]] Outdated Asset(s) Require Immediate Action`

**Template Variables**: Same as Level 1

**Key Differences from Level 1**:
- **Header Color**: Red (#dc3545) instead of orange
- **Subject Emoji**: ⚠️ (warning sign)
- **Urgency Language**: "URGENT", "Immediate Action Required", "Compliance Risk"
- **Escalation Notice**: "This is a second reminder - these assets have been outdated for 7+ days"
- **Consequences**: Mention potential compliance violations, security risks

**HTML Structure Highlights** (differences from Level 1):

```html
<!-- Header: Red instead of orange -->
<div class="header" style="background-color: #dc3545;">
    <h2>⚠️ URGENT: Immediate Action Required - Outdated Assets</h2>
</div>

<!-- Escalation Notice -->
<div style="background-color: #f8d7da; border-left: 4px solid #dc3545; padding: 15px; margin: 15px 0;">
    <h4 style="color: #721c24;">⚠️ This is an Escalated Reminder</h4>
    <p style="color: #721c24;">
        These assets have been outdated for <strong>7+ days</strong> without remediation.
        Immediate action is required to maintain security compliance and reduce organizational risk.
    </p>
</div>

<!-- Updated CTA -->
<div style="text-align: center; margin: 25px 0;">
    <a th:href="${dashboardUrl}" class="cta-button" style="background-color: #dc3545;">
        Take Action Now →
    </a>
</div>

<!-- Consequences Section -->
<div style="background-color: #fff3cd; border-left: 4px solid #ffc107; padding: 15px; margin: 15px 0;">
    <h4 style="color: #856404;">Potential Consequences</h4>
    <ul>
        <li>Increased risk of security breach or data compromise</li>
        <li>Non-compliance with security policies and regulatory requirements</li>
        <li>Possible escalation to senior management and security leadership</li>
        <li>Audit findings and remediation mandates</li>
    </ul>
</div>

<!-- Updated Note -->
<p style="color: #dc3545; font-weight: bold; font-size: 14px;">
    IMPORTANT: Continued delays in addressing these vulnerabilities may result in formal
    escalation and mandatory remediation actions.
</p>
```

---

## Template 3: New Vulnerabilities Notification

**Filename**: `new-vulnerabilities.html`

**Tone**: Informational, FYI, awareness

**Subject Line**: `New Vulnerabilities Detected: [[${totalCount}]] Vulnerability(ies) on Your Assets`

**Template Variables**: Similar to Level 1, but grouped by asset

**HTML Structure**:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>New Vulnerabilities Detected</title>
    <style>
        /* Same base styles as Level 1, but header color is blue (#007bff) */
    </style>
</head>
<body>
    <div class="container">
        <!-- Header -->
        <div class="header" style="background-color: #007bff;">
            <h2>🔔 New Vulnerabilities Detected on Your Assets</h2>
        </div>

        <!-- Greeting -->
        <p>Hello <span th:text="${recipientName} ?: 'Asset Owner'">Asset Owner</span>,</p>

        <!-- Summary -->
        <p>
            Our security scanning has identified <strong th:text="${totalCount}">12</strong> new
            vulnerability(ies) on assets under your ownership. This notification is for your awareness;
            please review and prioritize remediation based on severity.
        </p>

        <!-- Severity Breakdown (same as Level 1) -->
        <div style="background-color: #f8f9fa; padding: 15px; border-radius: 4px; margin: 15px 0;">
            <h3>Severity Breakdown</h3>
            <!-- Same as Level 1 -->
        </div>

        <!-- Asset-Grouped Vulnerability List -->
        <h3>New Vulnerabilities by Asset</h3>
        <div th:each="asset : ${assets}" style="margin-bottom: 20px;">
            <h4 style="color: #007bff;">
                <span th:text="${asset.name}">web-server-01</span>
                (<span th:text="${asset.type}">SERVER</span>)
            </h4>
            <table>
                <thead>
                    <tr>
                        <th>CVE ID</th>
                        <th>Severity</th>
                        <th>Detected On</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="vuln : ${asset.vulnerabilities}">
                        <td th:text="${vuln.cveId}">CVE-2025-1234</td>
                        <td>
                            <span class="severity-badge"
                                  th:classappend="${vuln.severity.toLowerCase()}"
                                  th:text="${vuln.severity}">HIGH</span>
                        </td>
                        <td th:text="${#temporals.format(vuln.detectedAt, 'yyyy-MM-dd')}">2025-10-26</td>
                    </tr>
                </tbody>
            </table>
        </div>

        <!-- Call to Action -->
        <div style="text-align: center; margin: 25px 0;">
            <a th:href="${dashboardUrl}" class="cta-button">Review in Dashboard →</a>
        </div>

        <!-- Information Box -->
        <div style="background-color: #e7f3ff; padding: 15px; border-left: 4px solid #007bff; margin: 15px 0;">
            <h4>What This Means</h4>
            <p>
                These vulnerabilities were detected in the most recent security scan. While immediate
                action is not always required, we recommend reviewing each vulnerability to determine
                if patches or mitigations are necessary based on your risk tolerance and asset criticality.
            </p>
        </div>

        <!-- Preference Management -->
        <p style="color: #666; font-size: 14px;">
            <strong>Note:</strong> You are receiving this notification because you opted in to new
            vulnerability alerts. You can manage your notification preferences at:
            <a th:href="${preferencesUrl}">Notification Settings</a>
        </p>

        <!-- Footer (same as Level 1) -->
        <div class="footer">
            <!-- Same as Level 1 -->
        </div>
    </div>
</body>
</html>
```

---

## Common Email Settings

### SMTP Configuration

**From Address**: `noreply@secman.example.com`
**From Name**: `Security Management System`
**Reply-To**: `security-team@secman.example.com`

### Email Headers

```
X-Mailer: Secman Notification System
X-Priority: 3 (Normal) for Level 1 and New Vuln
X-Priority: 1 (High) for Level 2
Auto-Submitted: auto-generated
```

### Responsive Design

All templates must:
- Be mobile-responsive (viewable on smartphones)
- Use inline CSS (email clients don't support external stylesheets)
- Include plain-text fallback for email clients that don't support HTML
- Limit width to 600px for optimal rendering

### Testing

Test templates with:
- Gmail (web and mobile app)
- Outlook (desktop and web)
- Apple Mail (macOS and iOS)
- Thunderbird

### Accessibility

- Use semantic HTML (`<h2>`, `<table>`, `<th>`, etc.)
- Provide alt text for any images (if added in future)
- Ensure sufficient color contrast (WCAG AA compliant)
- Plain-text version for screen readers

---

## Template Localization (Future Enhancement)

**Out of Scope**: This feature supports English only. Future enhancements may add:
- Multi-language templates (EN, DE, ES, FR)
- Locale-based template selection
- Externalized message strings

---

## Template Variables Reference

### EmailContext

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `recipientEmail` | String | Owner email address | `john.doe@example.com` |
| `recipientName` | String? | Owner name (optional) | `John Doe` |
| `assets` | List<AssetEmailData> | List of affected assets | See below |
| `notificationType` | NotificationType | Type of notification | `OUTDATED_LEVEL1` |
| `reminderLevel` | Int? | Reminder level (1 or 2, for outdated only) | `1` |
| `totalCount` | Int | Total number of assets or vulnerabilities | `5` |
| `criticalCount` | Int | Count of critical severity items | `2` |
| `highCount` | Int | Count of high severity items | `3` |
| `mediumCount` | Int | Count of medium severity items | `5` |
| `lowCount` | Int | Count of low severity items | `1` |
| `dashboardUrl` | String | Link to asset management dashboard | `https://secman.example.com/assets` |

### AssetEmailData

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `id` | Long | Asset ID | `42` |
| `name` | String | Asset name | `web-server-01` |
| `type` | String | Asset type | `SERVER` |
| `vulnerabilityCount` | Int | Total vulnerabilities on asset | `8` |
| `oldestVulnDays` | Int | Age of oldest vulnerability in days | `45` |
| `oldestVulnId` | String | CVE ID of oldest vulnerability | `CVE-2024-1234` |

---

## Email Size Limits

- **HTML Email**: <100 KB (to avoid spam filters)
- **Attachment**: None (link to dashboard instead)
- **Asset Limit per Email**: If owner has >100 assets, truncate list and add "View full list in dashboard"

---

## Deliverability Best Practices

1. **SPF/DKIM/DMARC**: Ensure SMTP server has proper email authentication configured
2. **Avoid Spam Triggers**: Don't use excessive capitalization, multiple exclamation marks, or "buy now" language
3. **Unsubscribe Link**: Not required (outdated reminders are mandatory; new vuln notifications are opt-in via preferences)
4. **List-Unsubscribe Header**: Add for new vulnerability notifications:
   ```
   List-Unsubscribe: <https://secman.example.com/notification-preferences>
   ```

---

## Error Handling

If email rendering fails:
1. Log error to NotificationLog with status=FAILED
2. Include error message (e.g., "Template rendering error: unknown variable")
3. Fallback: Send plain-text email with basic information
4. Do not retry (template errors require code fix, not retry)
