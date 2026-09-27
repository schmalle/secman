interface Participant {
  id?: number | null;
  username: string;
  email: string;
}

/** Only a unique exact identity can be selected without an explicit click. */
export function exactParticipantId(users: Participant[], query: string): number | undefined {
  const normalized = query.trim().toLowerCase();
  if (!normalized) return undefined;
  const matches = users.filter(user => user.id != null &&
    (user.username.toLowerCase() === normalized || user.email.toLowerCase() === normalized));
  return matches.length === 1 ? matches[0].id ?? undefined : undefined;
}
