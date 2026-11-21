export function parseDaysOpen(value: unknown): number | null {
  const match = /^(\d+)(?:\s+days?)?$/i.exec(String(value ?? '').trim());
  if (!match) return null;
  const days = Number(match[1]);
  return Number.isSafeInteger(days) ? days : null;
}
