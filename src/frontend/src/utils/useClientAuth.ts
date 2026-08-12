import { useEffect, useState } from 'react';
import { getUser, hasRole, hasVulnAccess } from './auth';

/**
 * Auth state that is safe to read *during render* inside an Astro island.
 *
 * `getUser()` / `hasRole()` read `sessionStorage`, which does not exist during
 * Astro's server render — there they return `null` / `false`. Calling them
 * directly in a component body therefore makes the server HTML disagree with the
 * client's very first render, and React 19 answers a hydration mismatch by
 * throwing and discarding the whole island (state resets, content flashes).
 *
 * `typeof window !== 'undefined'` does NOT fix that: it makes the code *run* on
 * both sides while still producing *different output*, which is exactly what
 * hydration compares. React's own error text names it as the first cause.
 *
 * These hooks return the SSR-safe value on the first render — so both sides
 * agree — and the real value from the render immediately after mount, so
 * role-gated UI appears a tick later instead of blowing up the island.
 */

/** Roles of the signed-in user; `[]` on the server and until mounted. */
export function useClientRoles(): string[] {
    const [roles, setRoles] = useState<string[]>([]);
    useEffect(() => {
        setRoles(getUser()?.roles ?? []);
    }, []);
    return roles;
}

/** The signed-in user; `null` on the server and until mounted. */
export function useClientUser(): ReturnType<typeof getUser> {
    const [user, setUser] = useState<ReturnType<typeof getUser>>(null);
    useEffect(() => {
        setUser(getUser());
    }, []);
    return user;
}

/**
 * `hasRole` as a hook; `false` on the server and until mounted.
 *
 * The dependency is the joined role list rather than the argument itself: call
 * sites pass array literals, which are a fresh reference on every render and
 * would re-run the effect forever.
 */
export function useClientHasRole(role: string | string[]): boolean {
    const [allowed, setAllowed] = useState(false);
    const key = Array.isArray(role) ? role.join(',') : role;
    useEffect(() => {
        setAllowed(hasRole(role));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [key]);
    return allowed;
}

/**
 * `hasRole` semantics against an already-resolved role list.
 *
 * For call sites that test a *dynamic* role (one per row of a list), where a hook
 * cannot be called — pair it with `useClientRoles()`.
 */
export function matchesRole(roles: string[], required: string | string[]): boolean {
    return Array.isArray(required)
        ? required.some((r) => roles.includes(r))
        : roles.includes(required);
}

/**
 * `hasVulnAccess` as a hook; `false` on the server and until mounted.
 *
 * Delegates rather than re-listing ADMIN/VULN/SECCHAMPION, so the role set stays
 * defined in exactly one place (utils/auth).
 */
export function useClientHasVulnAccess(): boolean {
    const [allowed, setAllowed] = useState(false);
    useEffect(() => {
        setAllowed(hasVulnAccess());
    }, []);
    return allowed;
}

/** True from the first render after mount. For non-role client-only branches. */
export function useIsMounted(): boolean {
    const [mounted, setMounted] = useState(false);
    useEffect(() => setMounted(true), []);
    return mounted;
}
