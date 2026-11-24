import type { ReactNode } from 'react'
import { Navigate, Outlet } from 'react-router'
import type { Role } from './roles'
import { useRoles } from './useRoles'

/**
 * Renders its children only for a user holding at least one of `roles`; anyone else goes to /forbidden.
 * Place it inside {@link RequireAuth}. It hides screens, it does not protect data: the API checks the role
 * and ownership of every request again.
 */
export function RequireRole({ roles, children }: { roles: readonly Role[]; children?: ReactNode }) {
  const userRoles = useRoles()
  if (!roles.some((role) => userRoles.includes(role))) {
    return <Navigate to="/forbidden" replace />
  }
  return children ?? <Outlet />
}
