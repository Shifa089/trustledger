/**
 * Keycloak realm roles (SPEC §2 actors). The UI uses them only to choose routes and navigation; every endpoint
 * enforces its own role rule on the server, so hiding a link is convenience, not security.
 */
export const ROLES = ['CUSTOMER', 'COMPLIANCE_OFFICER', 'ADMIN', 'AUDITOR'] as const
export type Role = (typeof ROLES)[number]

export const ROLE_LABELS: Record<Role, string> = {
  CUSTOMER: 'Customer',
  COMPLIANCE_OFFICER: 'Compliance officer',
  ADMIN: 'Admin',
  AUDITOR: 'Auditor',
}

/** Each role's home; a user with several roles lands on the first in {@link ROLES} order. */
export const ROLE_HOME: Record<Role, string> = {
  CUSTOMER: '/customer',
  COMPLIANCE_OFFICER: '/compliance',
  ADMIN: '/admin',
  AUDITOR: '/audit',
}

function isRole(value: unknown): value is Role {
  return typeof value === 'string' && (ROLES as readonly string[]).includes(value)
}

/**
 * The JWT payload, decoded without verifying the signature. That is fine here: the token came straight from
 * Keycloak over TLS into memory, and the backend verifies it on every call. Never trust this for security.
 */
export function decodeJwtPayload(token: string): Record<string, unknown> | undefined {
  const part = token.split('.')[1]
  if (part === undefined) {
    return undefined
  }
  try {
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '=')
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
    const payload: unknown = JSON.parse(new TextDecoder().decode(bytes))
    return typeof payload === 'object' && payload !== null ? (payload as Record<string, unknown>) : undefined
  } catch {
    return undefined
  }
}

/** `realm_access.roles` from a Keycloak access token, the same claim the backend's JwtRoleConverter reads. */
export function rolesFromAccessToken(accessToken: string | undefined): Role[] {
  if (accessToken === undefined) {
    return []
  }
  const realmAccess = decodeJwtPayload(accessToken)?.realm_access
  if (typeof realmAccess !== 'object' || realmAccess === null) {
    return []
  }
  const roles = (realmAccess as { roles?: unknown }).roles
  return Array.isArray(roles) ? ROLES.filter((role) => roles.some((r) => isRole(r) && r === role)) : []
}

export function homePathFor(roles: readonly Role[]): string {
  const first = ROLES.find((role) => roles.includes(role))
  return first === undefined ? '/forbidden' : ROLE_HOME[first]
}
