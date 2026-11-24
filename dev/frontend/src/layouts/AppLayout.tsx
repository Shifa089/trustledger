import { LogOut } from 'lucide-react'
import { useAuth } from 'react-oidc-context'
import { NavLink, Outlet } from 'react-router'
import { ROLE_LABELS } from '@/auth/roles'
import { useRoles } from '@/auth/useRoles'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { navFor } from '@/nav'
import { Logo } from './Logo'

/** The signed-in shell: navigation for the user's roles only, who is signed in, and sign out. */
export function AppLayout() {
  const auth = useAuth()
  const roles = useRoles()
  const username = auth.user?.profile.preferred_username ?? 'Signed in'

  return (
    <>
      <header className="border-b">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-4 px-6 py-3">
          <Logo to="/app" />
          <div className="ml-auto flex items-center gap-3">
            <span className="text-sm" data-testid="username">
              {username}
            </span>
            {roles.map((role) => (
              <Badge key={role} variant="secondary">
                {ROLE_LABELS[role]}
              </Badge>
            ))}
            <Button variant="outline" size="sm" onClick={() => void auth.signoutRedirect()}>
              <LogOut aria-hidden />
              Sign out
            </Button>
          </div>
        </div>
      </header>
      <div className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-6 py-6 md:flex-row">
        <nav aria-label="Main" className="md:w-56 md:shrink-0">
          <ul className="flex flex-wrap gap-1 md:flex-col">
            {navFor(roles).map((item) => (
              <li key={item.to}>
                {item.soon === true ? (
                  <span className="flex items-center justify-between gap-2 rounded-md px-3 py-2 text-sm text-muted-foreground">
                    {item.label}
                    <Badge variant="outline">soon</Badge>
                  </span>
                ) : (
                  <NavLink
                    to={item.to}
                    end
                    className={({ isActive }) =>
                      cn(
                        'block rounded-md px-3 py-2 text-sm font-medium hover:bg-accent',
                        isActive && 'bg-accent text-accent-foreground',
                      )
                    }
                  >
                    {item.label}
                  </NavLink>
                )}
              </li>
            ))}
          </ul>
        </nav>
        <main className="min-w-0 flex-1">
          <Outlet />
        </main>
      </div>
    </>
  )
}
