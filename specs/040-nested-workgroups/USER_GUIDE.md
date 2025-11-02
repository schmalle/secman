# Nested Workgroups - User Guide

## Overview

The Nested Workgroups feature allows you to organize workgroups in a hierarchical structure, similar to folders and subfolders on your computer. This enables better organization of assets and users by grouping related workgroups under parent workgroups.

**Key Benefits:**
- Organize workgroups by department, location, function, or any custom hierarchy
- Navigate workgroups using a visual tree structure
- Maintain clear parent-child relationships with automatic breadcrumb navigation
- Safely reorganize your structure with built-in validation

## Getting Started

### Accessing Workgroup Management

1. Log in to the SecMan application with ADMIN credentials
2. Navigate to **Workgroup Management** from the main menu
3. You'll see two view options: **Tree View** (default) and **Table View**

### Understanding the Interface

#### Tree View (Recommended for Hierarchy)

The Tree View displays your workgroups in a hierarchical structure:

- **Left Panel**: Hierarchical tree of all workgroups
  - Root-level workgroups (no parent) appear at the top
  - Child workgroups are indented under their parents
  - Click the arrow icon to expand/collapse children
  - Depth badges show the level (1-5) of each workgroup
  - Child count badges show how many direct children exist

- **Right Panel**: Details of the selected workgroup
  - Breadcrumb navigation showing the full path from root
  - Workgroup metadata (ID, name, description, hierarchy position)
  - Action buttons for hierarchy operations
  - Ancestor path visualization

#### Table View (Classic Flat View)

The Table View shows all workgroups in a flat table, preserving the traditional management interface. This view is useful for:
- Bulk operations on multiple workgroups
- Assigning users and assets to workgroups
- Managing workgroup criticality settings
- Viewing all workgroups at once without hierarchy

**Tip:** Switch between views using the toggle buttons in the top-right corner.

## Creating Child Workgroups

### Step-by-Step Instructions

1. **Select Parent Workgroup**
   - In Tree View, click on the workgroup that will be the parent
   - The workgroup details panel will appear on the right

2. **Check Depth Limit**
   - Ensure the parent workgroup is **not** at Level 5 (maximum depth)
   - The "Add Child" button will be disabled if the depth limit is reached

3. **Create Child**
   - Click the **"Add Child"** button in the Hierarchy Actions section
   - A modal dialog will appear

4. **Fill in Child Details**
   - **Name** (required): Enter a unique name for the child workgroup
     - Must be unique among siblings (other children of the same parent)
     - 3-100 characters allowed
   - **Description** (optional): Provide additional context
     - 0-500 characters allowed

5. **Submit**
   - Click **"Create Child Workgroup"**
   - The tree will automatically refresh and select the new child

### Validation Rules

- **Name uniqueness**: Child name must be unique among siblings at the same parent level
- **Depth limit**: Cannot create children beyond Level 5
- **Name length**: 3-100 characters
- **Description length**: 0-500 characters (optional)

### Example Hierarchy

```
Engineering (Level 1)
├── Backend Team (Level 2)
│   ├── API Services (Level 3)
│   ├── Database Team (Level 3)
│   └── Infrastructure (Level 3)
├── Frontend Team (Level 2)
│   ├── Web Apps (Level 3)
│   └── Mobile Apps (Level 3)
└── QA Team (Level 2)
```

## Moving Workgroups

### When to Move Workgroups

Move a workgroup when you need to:
- Reorganize your hierarchy structure
- Place a workgroup under a different parent
- Move a workgroup to root level (no parent)

### Step-by-Step Instructions

1. **Select Workgroup to Move**
   - In Tree View, click on the workgroup you want to move

2. **Open Move Dialog**
   - Click the **"Move"** button in the Hierarchy Actions section
   - The Move Workgroup modal will appear

3. **Review Current Location**
   - The modal shows the current location and depth of the workgroup
   - If the workgroup has children, they will move with it

4. **Select New Parent**
   - Choose a new parent from the dropdown list
   - Select **"-- Move to Root Level --"** to make it a root-level workgroup
   - The list shows only **valid parent options** (see validation rules below)

5. **Submit**
   - Click **"Move Workgroup"**
   - The tree will automatically refresh and select the moved workgroup

### Validation Rules

The system automatically filters out invalid parent options:

- **Cannot move to self**: A workgroup cannot be its own parent
- **Cannot move to descendant**: A workgroup cannot be moved under any of its children or descendants (prevents circular references)
- **Depth limit**: The resulting depth (parent depth + workgroup's subtree depth) must not exceed 5 levels
- **Name uniqueness**: The workgroup name must be unique among new siblings

### Example Move Operation

**Before:**
```
Operations (Level 1)
├── Security Team (Level 2)

Engineering (Level 1)
└── Backend Team (Level 2)
```

**Action:** Move "Security Team" from "Operations" to "Engineering"

**After:**
```
Operations (Level 1)

Engineering (Level 1)
├── Backend Team (Level 2)
└── Security Team (Level 2)
```

## Deleting Workgroups

### Understanding Deletion with Child Promotion

When you delete a workgroup that has children, the **child promotion** mechanism automatically moves children up one level:

- **If parent has a grandparent**: Children are promoted to grandparent level (become siblings of deleted workgroup)
- **If parent is root-level**: Children are promoted to root level

**Important:**
- User assignments to the deleted workgroup are removed
- Asset assignments to the deleted workgroup are removed
- Children workgroups are **preserved** and promoted
- All users and assets in child workgroups remain assigned to those children

### Step-by-Step Instructions

1. **Select Workgroup to Delete**
   - In Tree View, click on the workgroup you want to delete

2. **Open Delete Confirmation**
   - Click the **"Delete"** button in the Hierarchy Actions section
   - A confirmation modal will appear with detailed information

3. **Review Consequences**
   - Read the workgroup information carefully
   - Review the child promotion details (if applicable)
   - Review the list of consequences (user/asset removals, child promotion)

4. **Confirm Deletion**
   - Type the exact workgroup name in the confirmation field
   - This safety measure prevents accidental deletions
   - Click **"Delete Workgroup"**

5. **Result**
   - The workgroup is permanently deleted
   - Children are promoted to the parent's level
   - The tree refreshes automatically

### Deletion Examples

#### Example 1: Deleting with Grandparent

**Before:**
```
Engineering (Level 1)
└── Backend Team (Level 2)
    ├── API Services (Level 3)
    └── Database Team (Level 3)
```

**Action:** Delete "Backend Team"

**After:**
```
Engineering (Level 1)
├── API Services (Level 2)  ← Promoted
└── Database Team (Level 2)  ← Promoted
```

#### Example 2: Deleting Root-Level Workgroup

**Before:**
```
Engineering (Level 1)
├── Backend Team (Level 2)
└── Frontend Team (Level 2)

Operations (Level 1)
```

**Action:** Delete "Engineering"

**After:**
```
Backend Team (Level 1)   ← Promoted to root
Frontend Team (Level 1)  ← Promoted to root

Operations (Level 1)
```

## Navigating with Breadcrumbs

The breadcrumb navigation shows the full path from root to the currently selected workgroup.

### Using Breadcrumbs

1. **View Path**: The breadcrumb displays: `Home > Grandparent > Parent > Current`
2. **Navigate**: Click any ancestor name to jump to that workgroup
3. **Home Link**: Click "Home" to return to the root view
4. **Current Workgroup**: The rightmost item (in bold) shows the selected workgroup and its depth level

### Example Breadcrumb

```
Home > Engineering > Backend Team > API Services (Level 3)
```

Clicking "Backend Team" will navigate to and select that workgroup in the tree.

## Best Practices

### Organizing Your Hierarchy

1. **Plan Your Structure**
   - Sketch out your desired hierarchy before creating workgroups
   - Common patterns: Department → Team → Subteam, Location → Building → Floor, or Function → Capability → Service

2. **Use Meaningful Names**
   - Choose descriptive names that clearly indicate the workgroup's purpose
   - Avoid generic names like "Group 1" or "Team A"

3. **Leverage Depth Wisely**
   - You have 5 levels available - use them strategically
   - Avoid creating unnecessary depth if 2-3 levels suffice
   - Example: Company → Division → Department → Team → Project (5 levels)

4. **Document Your Hierarchy**
   - Add descriptions to workgroups explaining their purpose and scope
   - Helps new administrators understand the structure

### Managing Changes

1. **Test Moves Carefully**
   - Review the validation messages before moving workgroups
   - Consider the impact on children (they move with the parent)

2. **Deletion Planning**
   - Before deleting, understand where children will be promoted
   - Document which users/assets will lose their workgroup assignment
   - Consider moving children manually before deletion if promotion isn't desired

3. **Monitor Depth Limits**
   - Keep an eye on depth levels when creating or moving workgroups
   - If you hit the Level 5 limit, consider reorganizing your hierarchy

### Access Control Implications

1. **Workgroup-Based Access**
   - Remember that users see assets from their assigned workgroups
   - Hierarchy changes affect which users see which assets
   - Test access after major reorganizations

2. **User Assignments**
   - Assign users to workgroups at the appropriate level
   - Users assigned to parent workgroups may need access to child assets (verify with your security policy)

## Troubleshooting

### Common Issues

**Issue:** "Add Child" button is disabled
- **Cause:** Parent workgroup is at Level 5 (maximum depth)
- **Solution:** Move the parent up one level or create the new workgroup at a different location

**Issue:** Cannot move workgroup to desired parent
- **Cause:** Would exceed depth limit, create circular reference, or cause name conflict
- **Solution:** Check the validation hints in the Move dialog; reorganize hierarchy to accommodate the move

**Issue:** Workgroup name already exists error when creating child
- **Cause:** Another sibling (child of the same parent) has that name
- **Solution:** Choose a unique name or rename the existing sibling first

**Issue:** Accidentally deleted the wrong workgroup
- **Cause:** Deletion is permanent and cannot be undone
- **Solution:** Recreate the workgroup manually and reassign users/assets from backup or memory

### Getting Help

For additional assistance:
- Contact your system administrator
- Refer to the API_REFERENCE.md for technical details
- Review the application logs for error messages (ADMIN access required)

## Summary

The Nested Workgroups feature provides powerful organizational capabilities:

- **Tree View**: Visual hierarchy with expand/collapse navigation
- **Create Children**: Up to 5 levels deep with validation
- **Move Workgroups**: Flexible reorganization with safety checks
- **Delete with Promotion**: Children are preserved and promoted automatically
- **Breadcrumb Navigation**: Easy traversal of ancestor paths

Use this feature to create a logical, scalable organizational structure that fits your security management needs.

---

**Feature Version:** 1.0
**Last Updated:** 2025-11-02
**Minimum Required Role:** ADMIN (for hierarchy modifications)
**Read Access:** All authenticated users
