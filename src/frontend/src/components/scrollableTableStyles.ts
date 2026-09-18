import type React from 'react';

export const scrollContainerStyle: React.CSSProperties = {
  minHeight: 0,
  overflow: 'auto',
  overscrollBehavior: 'contain',
  WebkitOverflowScrolling: 'touch',
  isolation: 'isolate',
};

// WebKit paints scrolled rows through a sticky <thead>. Keeping the row group
// static and making each opaque header cell sticky avoids that compositor bug.
export const stickyHeaderCellStyle: React.CSSProperties = {
  position: 'sticky',
  top: 0,
  zIndex: 2,
  backgroundColor: 'var(--bs-table-bg, #f8f9fa)',
  backgroundClip: 'padding-box',
  boxShadow: 'inset 0 -1px 0 var(--bs-border-color, #dee2e6)',
};
