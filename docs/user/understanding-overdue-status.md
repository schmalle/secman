# Understanding Overdue Status - User Guide

## Overview

This guide helps you understand the overdue status indicators on vulnerabilities and how to interpret them effectively.

---

## What is Overdue Status?

Overdue status indicates how long a vulnerability has been present in your system and whether it requires immediate attention.

### The Three Status Types

| Status | Visual | Meaning |
|--------|--------|---------|
| **OK** | ✅ Green badge | Vulnerability is within acceptable timeframe |
| **OVERDUE** | 🔴 Red badge | Vulnerability needs immediate remediation |
| **EXCEPTED** | 🛡️ Blue badge | Vulnerability has a documented exception |

---

## How It Works

### 1. Age Calculation

The system tracks how long each vulnerability has been present:
- **Start Date**: When the vulnerability was first detected
- **Current Age**: Days elapsed since first detection

### 2. Threshold Comparison

Your administrator sets a "Reminder One" threshold (typically 30 days). The system compares the vulnerability age against this threshold.

### 3. Status Determination

```
IF age ≤ threshold:
    Status = OK ✅

IF age > threshold AND no exception:
    Status = OVERDUE 🔴

IF age > threshold AND has exception:
    Status = EXCEPTED 🛡️
```

---

## Interpreting Badges

### ✅ OK Status

**What it means:**
- Vulnerability is relatively recent
- Within acceptable remediation timeframe
- No immediate action required

**What to do:**
- Continue monitoring
- Plan remediation according to severity
- No urgency unless high/critical severity

**Example:**
```
Vulnerability detected: 15 days ago
Threshold: 30 days
Status: OK ✅
```

---

### 🔴 OVERDUE Status

**What it means:**
- Vulnerability has been present too long
- Exceeds your organization's acceptable timeframe
- Requires immediate attention

**What to do:**
1. Prioritize for remediation
2. Check if patching is possible
3. If remediation isn't immediate, consider requesting an exception
4. Document why it's overdue

**Example:**
```
Vulnerability detected: 45 days ago
Threshold: 30 days
Days overdue: 15 days
Status: OVERDUE 🔴
```

---

### 🛡️ EXCEPTED Status

**What it means:**
- Vulnerability is overdue BUT has a documented exception
- Someone (usually admin) has acknowledged it
- There's a valid reason it can't be fixed immediately

**What to do:**
- Review the exception reason (hover over badge for details)
- Check exception expiration date
- Understand why it's excepted
- Monitor for exception expiry

**Example:**
```
Vulnerability detected: 60 days ago
Threshold: 30 days
Exception: "Scheduled for maintenance window on 2025-11-01"
Expires: 2025-11-15
Status: EXCEPTED 🛡️
```

---

## Viewing Overdue Information

### On the Vulnerabilities Page

1. Navigate to **Vulnerabilities → Current**
2. Look for the **Overdue Status** column
3. Each vulnerability shows its status badge

### Tooltip Details

Hover over any status badge to see:
- Current age of the vulnerability
- Threshold value
- Days overdue (if applicable)
- Exception reason (if applicable)
- Exception expiration (if applicable)

### Filtering by Status

Use the status filter dropdown to:
- **Show all**: See all vulnerabilities
- **OK only**: Focus on recent vulnerabilities
- **OVERDUE only**: See what needs immediate attention
- **EXCEPTED only**: Review exceptions

### Sorting by Status

Click the **Overdue Status** column header to:
- Group vulnerabilities by status
- See all OVERDUE items together
- Prioritize your work

---

## Common Scenarios

### Scenario 1: Newly Discovered Vulnerability

```
Discovery Date: Today
Age: 0 days
Threshold: 30 days
Status: OK ✅
Action: Plan remediation, no immediate urgency
```

### Scenario 2: Approaching Threshold

```
Discovery Date: 25 days ago
Age: 25 days
Threshold: 30 days
Status: OK ✅ (but close)
Action: Remediate soon before it becomes overdue
```

### Scenario 3: Just Became Overdue

```
Discovery Date: 31 days ago
Age: 31 days
Threshold: 30 days
Days Overdue: 1 day
Status: OVERDUE 🔴
Action: Immediate remediation or request exception
```

### Scenario 4: Long Overdue

```
Discovery Date: 90 days ago
Age: 90 days
Threshold: 30 days
Days Overdue: 60 days
Status: OVERDUE 🔴
Action: Critical - requires immediate attention and explanation
```

### Scenario 5: Excepted for Valid Reason

```
Discovery Date: 60 days ago
Age: 60 days
Threshold: 30 days
Exception: "Legacy system being decommissioned Q4 2025"
Expires: 2025-12-31
Status: EXCEPTED 🛡️
Action: Monitor exception expiration, track decommissioning progress
```

---

## Best Practices

### For Users

1. **Check Regularly**: Review the Current Vulnerabilities page weekly
2. **Filter Smart**: Use filters to focus on OVERDUE items
3. **Understand Context**: Read exception reasons to understand why things are excepted
4. **Communicate**: If you see something overdue you can't fix, talk to your admin
5. **Track Progress**: Monitor how quickly your team remediates vulnerabilities

### For Team Leads

1. **Weekly Reviews**: Hold weekly meetings to review OVERDUE vulnerabilities
2. **Assign Ownership**: Assign OVERDUE items to specific team members
3. **Set Goals**: Track metrics like "Average Days to Remediate"
4. **Request Exceptions**: If something can't be fixed quickly, work with admin to create an exception
5. **Report Up**: Keep management informed of overdue counts and trends

---

## Frequently Asked Questions

### Q: Why did a vulnerability suddenly become OVERDUE?

**A:** Either:
1. The threshold was recently reduced by an administrator
2. The vulnerability crossed the threshold date
3. An exception expired

### Q: Can I create an exception?

**A:** No, only users with ADMIN role can create exceptions. Contact your administrator if you believe a vulnerability should be excepted.

### Q: What does "Days Overdue" mean?

**A:** It's the number of days beyond the threshold. 
- Threshold: 30 days
- Current Age: 45 days
- Days Overdue: 15 days (45 - 30)

### Q: Why are some high-severity vulnerabilities marked OK?

**A:** Overdue status is based solely on age, not severity. A critical vulnerability discovered today is OK until it exceeds the threshold. Always prioritize by both severity AND overdue status.

### Q: What should I do if everything is OVERDUE?

**A:** This might mean:
1. Your threshold is too aggressive - talk to your admin
2. Your team needs more resources
3. You need a better remediation process

Contact your administrator to discuss adjusting the threshold or creating strategic exceptions.

### Q: How often does the status update?

**A:** Every time you load the page. Status is calculated in real-time based on the current date.

### Q: Can a vulnerability go from OVERDUE back to OK?

**A:** Only if:
1. The administrator increases the threshold
2. An exception is created (changes it to EXCEPTED, not OK)
3. The vulnerability is remediated (removed from the list)

---

## Understanding Exception Reasons

When you see an EXCEPTED status, hover over the badge to read the reason. Common valid reasons include:

### Acceptable Reasons
- "System scheduled for decommissioning on [date]"
- "Vendor patch not yet available, tracking under ticket #XXX"
- "Requires major version upgrade, planned for [date]"
- "False positive verified by security team"
- "Business-critical system with approved risk acceptance"

### Red Flags
- Vague reasons like "Can't fix" or "Too hard"
- Very old exceptions with no expiration
- Multiple exceptions for the same team/system without clear plan
- Exceptions that have been renewed many times

If you see red flag exceptions, discuss with your admin or security team.

---

## Getting Help

### For More Information
- **Admin Guide**: [Vulnerability Configuration](../admin/vulnerability-configuration.md)
- **Your Administrator**: Contact your system admin for threshold changes or exception requests
- **Security Team**: Consult your security team for remediation guidance

### Reporting Issues
If you notice:
- Incorrect overdue calculations
- Missing status badges
- Confusing exception reasons

Report to your system administrator or create a support ticket.

---

## Summary

**Remember the Three Keys:**

1. ✅ **OK** = Monitor and plan
2. 🔴 **OVERDUE** = Act now
3. 🛡️ **EXCEPTED** = Understand why and track

**Your Role:**
- Check overdue status regularly
- Prioritize OVERDUE vulnerabilities
- Communicate blockers early
- Help keep your systems secure

---

**Last Updated:** 2025-10-16  
**Version:** 1.0  
**Feature:** 021-vulnerability-overdue-exception-logic
