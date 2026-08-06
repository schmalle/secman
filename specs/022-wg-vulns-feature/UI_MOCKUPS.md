# WG Vulns UI Mockups

This document provides visual mockups and UI specifications for the WG Vulns feature.

## Navigation: Sidebar Menu Item

### Location
Under "Vuln Management" section, after "Account vulns"

### States

#### 1. Active User (has workgroups, not admin)
```
Vuln Management ▼
  ├─ Vuln overview
  ├─ Domain vulns
  ├─ System vulns
  ├─ Account vulns
  ├─ WG vulns              ← NEW (clickable, blue text)
  └─ Exceptions
```

#### 2. Disabled (admin user)
```
Vuln Management ▼
  ├─ Vuln overview
  ├─ Domain vulns
  ├─ System vulns
  ├─ Account vulns
  ├─ WG vulns              ← NEW (grayed out, tooltip: "Admins should use System Vulns view")
  └─ Exceptions
```

#### 3. Disabled (no workgroups)
```
Vuln Management ▼
  ├─ Vuln overview
  ├─ Domain vulns
  ├─ System vulns
  ├─ Account vulns
  ├─ WG vulns              ← NEW (grayed out, tooltip: "You are not a member of any workgroups")
  └─ Exceptions
```

### Icon
- Bootstrap Icon: `bi-people-fill`
- Color: Primary blue (when active), Gray (when disabled)

---

## Main View: WG Vulns Page

### Header Section
```
┌────────────────────────────────────────────────────────────────┐
│ 👥 Workgroup Vulnerabilities                [🔄 Refresh]       │
└────────────────────────────────────────────────────────────────┘
```

**Components:**
- H2 heading with people icon
- Refresh button (primary outline style)

---

## Summary Statistics Cards

### Layout (4-column grid)
```
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│  Workgroups     │ │  Total Assets   │ │ Total Vulns     │ │  By Severity    │
│                 │ │                 │ │                 │ │                 │
│      3          │ │      25         │ │     450         │ │ 🔴 Critical: 50 │
│                 │ │                 │ │                 │ │ 🟠 High: 150    │
│                 │ │                 │ │                 │ │ 🟡 Medium: 250  │
└─────────────────┘ └─────────────────┘ └─────────────────┘ └─────────────────┘
```

**Styling:**
- Card: White background, subtle shadow, no border
- Title: Small, muted gray text
- Value: Large (h3), dark text
- Severity badges: Custom component with colored backgrounds

---

## Workgroup Group Cards

### Single Workgroup Card
```
┌─────────────────────────────────────────────────────────────────────────┐
│ 👥 Security Team                                                        │
│ Team responsible for security infrastructure                            │
│                                                                          │
│ 10 assets    🔴 20  🟠 60  🟡 120                                       │
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │ Asset Name        Type        Vulns    🔴 Crit  🟠 High  🟡 Med    │ │
│ ├─────────────────────────────────────────────────────────────────────┤ │
│ │ web-server-01     SERVER        50        5       15       30      │ │
│ │ db-server-01      DATABASE      30        2       10       18      │ │
│ │ app-server-01     SERVER        20        1        5       14      │ │
│ │ cache-server-01   SERVER        15        0        3       12      │ │
│ │ api-server-01     SERVER        10        0        2        8      │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

**Components:**

1. **Card Header (light gray background)**
   - Workgroup name (H5, dark text, people icon)
   - Workgroup description (small, muted text, below name)
   - Asset count badge (secondary style)
   - Severity badges (right-aligned)

2. **Card Body**
   - Asset table (reused `AssetVulnTable` component)
   - Sortable columns
   - Clickable asset names (link to asset detail)

---

## Asset Table Specifications

### Columns
| Column | Width | Alignment | Sortable | Description |
|--------|-------|-----------|----------|-------------|
| Asset Name | 30% | Left | Yes | Clickable link to asset detail |
| Type | 15% | Left | Yes | Asset type (SERVER, DATABASE, etc.) |
| Vulns | 15% | Right | Yes (default desc) | Total vulnerability count |
| Critical | 13% | Right | No | Critical severity count with badge |
| High | 13% | Right | No | High severity count with badge |
| Medium | 14% | Right | No | Medium severity count with badge |

### Row Styling
- Hover: Light gray background
- Link: Primary blue color
- Severity badges: Colored pills with white text
  - Critical: Red background (#dc3545)
  - High: Orange background (#fd7e14)
  - Medium: Yellow background (#ffc107) with dark text

---

## Loading State

```
┌────────────────────────────────────────────────┐
│                                                │
│                                                │
│              ⟳ (spinning)                      │
│                                                │
│      Loading workgroup vulnerabilities...     │
│                                                │
│                                                │
└────────────────────────────────────────────────┘
```

**Components:**
- Centered spinner (Bootstrap primary color)
- Loading text (muted)
- Min height: 400px

---

## Error States

### 1. Admin Redirect
```
┌─────────────────────────────────────────────────────────────┐
│ ⚠ Admin Access Notice                                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│ Admin users should use the System Vulns view to see all    │
│ vulnerabilities.                                            │
│                                                             │
│ ───────────────────────────────────────────────────────    │
│                                                             │
│ [→ Go to System Vulns]  [🏠 Back to Home]                  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Styling:**
- Alert: Warning style (yellow background)
- Buttons: Primary (System Vulns), Secondary outline (Home)

### 2. No Workgroups
```
┌─────────────────────────────────────────────────────────────┐
│ ℹ No Workgroups Found                                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│ You are not a member of any workgroups, or your            │
│ workgroups have no assets.                                  │
│                                                             │
│ Please contact your administrator to add you to            │
│ workgroups.                                                 │
│                                                             │
│ ───────────────────────────────────────────────────────    │
│                                                             │
│ [🏠 Back to Home]                                           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Styling:**
- Alert: Info style (blue background)
- Button: Secondary style

### 3. General Error
```
┌─────────────────────────────────────────────────────────────┐
│ ⚠ Error Loading Workgroup Vulnerabilities                   │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│ [Error message from server or generic message]              │
│                                                             │
│ ───────────────────────────────────────────────────────    │
│                                                             │
│ [🔄 Try Again]  [🏠 Back to Home]                           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Styling:**
- Alert: Danger style (red background)
- Buttons: Primary (Try Again), Secondary outline (Home)

---

## Empty Workgroup State

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 👥 Empty Workgroup                                                      │
│ This workgroup currently has no assets                                   │
│                                                                          │
│ 0 assets    🔴 0  🟠 0  🟡 0                                            │
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │ No assets found in this workgroup                                   │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

**Styling:**
- Same card structure as workgroups with assets
- Empty state message in table area (centered, muted text)

---

## Responsive Design

### Desktop (>992px)
- 4-column grid for summary cards
- Full table with all columns
- Side-by-side layout

### Tablet (768px - 991px)
- 2-column grid for summary cards (2 rows)
- Full table with all columns
- Reduced padding

### Mobile (<768px)
- 1-column grid for summary cards (4 rows)
- Simplified table (hide Type column)
- Stack severity badges vertically
- Increased touch targets

---

## Color Palette

### Severity Colors
- **Critical**: #dc3545 (Bootstrap danger red)
- **High**: #fd7e14 (Bootstrap warning orange)
- **Medium**: #ffc107 (Bootstrap warning yellow)
- **Low**: #6c757d (Bootstrap secondary gray) - not shown in summary
- **Unknown**: #6c757d (Bootstrap secondary gray) - not shown in summary

### UI Colors
- **Primary**: #0d6efd (Bootstrap primary blue)
- **Secondary**: #6c757d (Bootstrap secondary gray)
- **Success**: #198754 (Bootstrap success green)
- **Info**: #0dcaf0 (Bootstrap info cyan)
- **Warning**: #ffc107 (Bootstrap warning yellow)
- **Danger**: #dc3545 (Bootstrap danger red)
- **Light**: #f8f9fa (Bootstrap light gray)
- **Dark**: #212529 (Bootstrap dark)

### Text Colors
- **Primary text**: #212529 (dark)
- **Secondary text**: #6c757d (muted)
- **Link**: #0d6efd (primary blue)
- **Link hover**: #0a58ca (darker blue)

---

## Icons Reference

| Element | Icon | Class |
|---------|------|-------|
| Page title | People group | `bi-people-fill` |
| Workgroup card header | People group | `bi-people-fill` |
| Refresh button | Arrow clockwise | `bi-arrow-clockwise` |
| Back to home | House | `bi-house` |
| Admin notice | Shield lock | `bi-shield-lock` |
| Info notice | Info circle | `bi-info-circle` |
| Error notice | Exclamation triangle | `bi-exclamation-triangle` |
| Go to System Vulns | Arrow right | `bi-arrow-right` |

---

## Typography

### Headings
- **H2** (Page title): 32px, bold, dark
- **H3** (Summary stats): 28px, regular, dark
- **H5** (Workgroup name): 20px, regular, dark
- **H6** (Card title): 16px, regular, muted

### Body Text
- **Regular**: 16px, regular, dark
- **Small**: 14px, regular, muted
- **Badge text**: 14px, medium weight, color varies

### Table Text
- **Header**: 14px, medium weight, dark
- **Body**: 14px, regular, dark
- **Link**: 14px, medium weight, primary blue

---

## Spacing

### Page Layout
- Container padding: 1.5rem (24px)
- Section margin bottom: 1.5rem (24px)

### Cards
- Card padding: 1rem (16px)
- Card margin bottom: 1.5rem (24px)
- Card shadow: 0 0.125rem 0.25rem rgba(0,0,0,0.075)

### Summary Cards
- Card body padding: 1.5rem (24px)
- Gap between cards: 1rem (16px)

### Tables
- Cell padding: 0.75rem (12px)
- Row height: auto (min 48px for touch)

---

## Accessibility

### ARIA Labels
- Page heading: `role="heading" aria-level="2"`
- Refresh button: `aria-label="Refresh workgroup vulnerabilities"`
- Loading spinner: `role="status" aria-live="polite"`
- Error alerts: `role="alert"`

### Keyboard Navigation
- Tab order: Header → Summary cards → Workgroup cards → Asset links → Refresh button
- Enter/Space: Activate links and buttons
- Focus indicators: Blue outline (2px solid #0d6efd)

### Color Contrast
- Text on white: Minimum 4.5:1 ratio
- Severity badges: Ensure text is readable against badge color
- Links: Underline on hover for non-color indicators

### Screen Reader Support
- Alt text for all icons
- Descriptive labels for interactive elements
- Table headers properly associated with cells
- Live regions for dynamic content updates

---

## Animations

### Loading Spinner
- Animation: Continuous clockwise rotation
- Duration: 750ms
- Easing: Linear

### Hover Effects
- Transition: all 150ms ease-in-out
- Row hover: Background color change
- Button hover: Background color change, slight scale (1.02)

### Page Transitions
- Fade in: 200ms
- Slide up: 300ms ease-out (for error messages)

---

## Comparison with Account Vulns

### Similarities
- ✅ Same card layout and structure
- ✅ Same summary statistics pattern
- ✅ Same severity badge styling
- ✅ Same asset table layout
- ✅ Same error state patterns
- ✅ Same loading state

### Differences
- 🔄 Icon: `bi-people-fill` (vs `bi-cloud`)
- 🔄 Group label: "Workgroup" (vs "AWS Account")
- 🔄 Group identifier: Workgroup name + description (vs AWS Account ID)
- 🔄 Sorting: Alphabetical by name (vs numerical by account ID)

This ensures consistent UX while clearly differentiating the two features.
