export function componentCategoryLabel(category: string): string {
  const labels: Record<string, string> = {
    JAVASCRIPT_LIBRARY: 'JavaScript',
    CSS_LIBRARY: 'CSS',
    WEB_SERVER: 'Web server',
  };
  return labels[category] ?? category;
}

export function reachabilityClass(reachability: string): string {
  if (reachability === 'REACHABLE') return 'text-bg-success';
  if (reachability === 'UNREACHABLE') return 'text-bg-danger';
  return 'text-bg-secondary';
}
