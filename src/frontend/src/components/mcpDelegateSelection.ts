export interface DelegateUser {
  id: number;
  username: string;
  email: string;
}

export function delegationDomains(users: DelegateUser[], additional: string): string {
  const domains = additional.split(',').map(domain => domain.trim().toLowerCase()).filter(Boolean);
  for (const user of users) {
    const email = user.email.trim().toLowerCase();
    const at = email.lastIndexOf('@');
    if (at > 0) domains.push(email.slice(at));
  }
  return [...new Set(domains)].join(',');
}
