export function canNotifyProductUsers(roles: string[] | undefined | null): boolean {
    return Boolean(roles?.includes('ADMIN') || roles?.includes('SECCHAMPION'));
}
