import type { Release } from "../types/release";

/**
 * A release is comparable only against another release of the same standard —
 * comparing across standards produces a diff of unrelated requirement sets,
 * which reads as "everything changed".
 */
export function comparableReleases(all: Release[], subject: Release): Release[] {
  return all.filter((r) => r.standardId === subject.standardId && r.id !== subject.id);
}

export function isEditable(release: Release): boolean {
  return release.status === "PREPARATION" || release.status === "ALIGNMENT";
}

/**
 * Sorts newest first. Releases without a date sort last rather than first:
 * an undated draft is almost always incomplete, and floating it to the top of
 * a picker makes it look like the current one.
 */
export function byRecency(releases: Release[]): Release[] {
  return [...releases].sort((a, b) => {
    if (!a.releaseDate) return 1;
    if (!b.releaseDate) return -1;
    return b.releaseDate.localeCompare(a.releaseDate);
  });
}

export function activeRelease(releases: Release[]): Release | undefined {
  return releases.find((r) => r.status === "ACTIVE");
}

/**
 * The picker shows archived releases only when one is already selected, so a
 * bookmarked link to an archived release still resolves instead of silently
 * falling back to the active one.
 */
export function pickerOptions(releases: Release[], selectedId?: number): Release[] {
  const visible = releases.filter(
    (r) => r.status !== "ARCHIVED" || r.id === selectedId
  );
  return byRecency(visible);
}
