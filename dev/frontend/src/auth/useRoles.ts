import { useMemo } from 'react'
import { useAuth } from 'react-oidc-context'
import { rolesFromAccessToken, type Role } from './roles'

/** The signed-in user's TrustLedger roles; empty when signed out. Re-read after every silent renew. */
export function useRoles(): Role[] {
  const accessToken = useAuth().user?.access_token
  return useMemo(() => rolesFromAccessToken(accessToken), [accessToken])
}
