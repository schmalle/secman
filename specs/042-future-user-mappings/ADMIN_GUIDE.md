# Feature 042: Future User Mapping - Admin Quick Guide

**For**: System Administrators
**Purpose**: Upload and manage user access mappings before users exist

---

## What is Future User Mapping?

Future User Mapping allows you to configure AWS account and domain access for users **before they are created** in the system. When those users are later created (manually or via OAuth), they automatically receive the correct access permissions.

---

## Key Benefits

- ✅ **Pre-configure access** for new hires before day 1
- ✅ **Automatic provisioning** when users first login via OAuth
- ✅ **No manual intervention** needed after user creation
- ✅ **Full audit trail** of when mappings were applied
- ✅ **Conflict prevention** - existing mappings are never overwritten

---

## How to Use

### 1. Upload Future User Mappings

**Navigation:** User Mapping Management → Bulk Upload tab

**Steps:**
1. Download the Excel/CSV template
2. Fill in user mappings (including non-existent users)
   ```
   Email Address       | AWS Account ID | Domain
   new.hire@company.com| 123456789012  | company.com
   ```
3. Upload the file
4. Review the import results

**Result:** Mappings are created with "Future User" status (yellow badge)

### 2. View Current Mappings

**Navigation:** User Mapping Management → Manage Mappings → Current Mappings tab

**What you see:**
- **Future User** (🟡 yellow badge) - User doesn't exist yet, waiting for creation
- **Active** (🔵 blue badge) - User exists, mapping is active
- Row highlighting: Yellow background for future user mappings

**Actions available:**
- Create new mapping
- Edit existing mappings
- Delete mappings

### 3. View Applied History

**Navigation:** User Mapping Management → Manage Mappings → Applied History tab

**What you see:**
- **Applied** (🟢 green badge) - Historical record of when mapping was applied
- **Applied At** column - Timestamp when user was created and mapping applied
- Read-only view (no edit/delete)

**Use cases:**
- Audit trail
- Verify automatic application worked correctly
- Troubleshooting

### 4. Create Users (Manual or OAuth)

**Manual Creation:**
1. Navigate to User Management
2. Click "Create New User"
3. Enter email matching a future user mapping
4. Submit
5. ✨ Mapping automatically applied in background

**OAuth Auto-provisioning:**
1. User logs in via OAuth (Microsoft/GitHub/Google)
2. System creates user account automatically
3. ✨ Mapping automatically applied on first login
4. User immediately has correct AWS account access

---

## Understanding Status Badges

| Badge | Status | Meaning |
|-------|--------|---------|
| 🟡 **Future User** | Yellow | Mapping waiting for user to be created |
| 🔵 **Active** | Blue | User exists, mapping is currently active |
| 🟢 **Applied** | Green | Historical record - mapping was applied to user |

---

## Common Workflows

### Workflow 1: Onboarding New Hire

**Scenario:** New employee starting Monday, need AWS access ready

**Steps:**
1. **Friday:** Upload future user mapping with new.hire@company.com → AWS123
2. **Friday:** Verify mapping shows "Future User" badge
3. **Monday:** IT creates user account for new.hire@company.com
4. **Monday:** Mapping automatically applied, user has AWS access
5. **Verify:** Check Applied History tab for timestamp

### Workflow 2: Bulk OAuth Rollout

**Scenario:** Company rolling out OAuth, want to pre-configure access for 500 users

**Steps:**
1. Export user list from HR system
2. Create Excel with all 500 users + their AWS accounts
3. Upload via Bulk Upload tab
4. All 500 mappings created as "Future User"
5. Roll out OAuth to organization
6. As users login, mappings automatically applied
7. Monitor Applied History tab to track progress

### Workflow 3: Update Future User Mapping

**Scenario:** Future user mapping has wrong AWS account

**Steps:**
1. Go to Current Mappings tab
2. Find the mapping (look for yellow badge)
3. Click Edit button
4. Update AWS Account ID
5. Save
6. When user is created, updated mapping will be applied

---

## Conflict Resolution

### What happens if a conflict occurs?

**Scenario:**
- You upload: `user@company.com → AWS123` (future mapping)
- But user already exists with: `user@company.com → AWS999` (active mapping)

**Result:**
- ✅ **Pre-existing mapping wins** - User keeps AWS999 access
- ⚠️ Future mapping marked as "Applied" but NOT linked to user
- 📋 Conflict logged in Applied History for audit

**Why this matters:**
- Prevents accidental privilege escalation
- Protects manually configured access
- Ensures predictable behavior

---

## Troubleshooting

### Issue: Mapping not applying automatically

**Check:**
1. Verify user email matches mapping email (case-insensitive)
2. Check Current Mappings tab - mapping should disappear after user creation
3. Check Applied History tab - should appear there with timestamp
4. Check backend logs for errors

**Common causes:**
- Typo in email address
- Duplicate mapping already exists
- Backend service not running

### Issue: Mapping stuck in "Future User" status

**Diagnosis:**
1. Verify user actually exists in User Management
2. Check if email addresses match exactly (case-insensitive)

**Resolution:**
- If user exists but mapping not applied: Contact system admin
- May require manual database fix or user recreation

### Issue: Cannot find mapping in Applied History

**Check:**
- Mapping might still be in Current Mappings (not yet applied)
- Check if user was actually created
- Verify you're looking at correct tab

---

## Best Practices

### DO ✅

- ✅ Upload future mappings before user creation
- ✅ Use Applied History tab for auditing
- ✅ Verify mappings in Current Mappings tab after upload
- ✅ Update future mappings if requirements change
- ✅ Use consistent email format (lowercase recommended)

### DON'T ❌

- ❌ Don't delete future mappings if you're unsure - they don't hurt anything
- ❌ Don't try to edit Applied History records (read-only)
- ❌ Don't upload duplicate mappings (system will skip them)
- ❌ Don't expect instant application (async, usually <5 seconds)

---

## FAQ

**Q: Can I upload mappings for users who already exist?**
A: Yes! The mapping will be immediately active (blue badge), no waiting period.

**Q: What happens if I upload the same mapping twice?**
A: System detects duplicates and skips them during import.

**Q: How long does automatic application take?**
A: Usually <5 seconds. Processing happens asynchronously in background.

**Q: Can I undo a future user mapping?**
A: Yes, go to Current Mappings tab and delete it (before user is created).

**Q: Can I undo an applied mapping?**
A: Yes, but you delete the active mapping, not the historical record. Applied History is read-only.

**Q: Will this work with both manual user creation and OAuth?**
A: Yes! Works seamlessly with both methods.

**Q: What if I upload a mapping for a user who will never be created?**
A: No problem! It stays in "Future User" status indefinitely. Clean it up when convenient.

**Q: Can I have multiple mappings for the same future user?**
A: Yes! One user can have multiple AWS accounts/domains. All will be applied when user is created.

---

## Technical Details (For Advanced Admins)

### Database Schema
```sql
user_mapping table:
- user_id (nullable FK) - Links to user after creation
- applied_at (nullable timestamp) - When mapping was applied
```

### Event Flow
```
User Created → UserCreatedEvent published →
  Async Listener triggered → Find matching mappings →
    Apply to user → Update user_id & applied_at
```

### Performance
- Mapping application: <5 seconds
- Query performance: <50ms for 10,000 mappings
- Non-blocking: User creation not delayed

---

## Monitoring & Alerts

### What to Monitor

1. **Applied History Growth**
   - Steady growth indicates successful OAuth rollout
   - Sudden spike may indicate bulk user import

2. **Future User Count**
   - Track number of mappings waiting for users
   - High count may indicate inactive/cancelled users

3. **Backend Logs**
   - Check for event processing errors
   - Monitor "User created event received" log entries

### Sample Queries

**Count future user mappings:**
```sql
SELECT COUNT(*) FROM user_mapping
WHERE user_id IS NULL AND applied_at IS NULL;
```

**Count applied mappings today:**
```sql
SELECT COUNT(*) FROM user_mapping
WHERE DATE(applied_at) = CURDATE();
```

---

## Support Contacts

- **Technical Issues**: Check backend logs at `logs/application.log`
- **Feature Requests**: Submit via GitHub issues
- **Training**: This guide + IMPLEMENTATION_SUMMARY.md

---

**Last Updated:** 2025-11-07
**Feature Version:** 042-future-user-mappings MVP
