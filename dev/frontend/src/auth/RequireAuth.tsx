import { useEffect, type ReactNode } from 'react'
import { useAuth } from 'react-oidc-context'
import { Outlet, useLocation } from 'react-router'
import { FullPageMessage } from '@/components/FullPageMessage'
import { Button } from '@/components/ui/button'
import type { SigninState } from './oidc'

/**
 * Signed-in users only. Anyone else is sent to Keycloak, and comes back to the page they asked for.
 */
export function RequireAuth({ children }: { children?: ReactNode }) {
  const auth = useAuth()
  const location = useLocation()
  const returnTo = `${location.pathname}${location.search}`
  const mustSignIn = !auth.isLoading && !auth.isAuthenticated && auth.activeNavigator === undefined && !auth.error

  useEffect(() => {
    if (mustSignIn) {
      const state: SigninState = { returnTo }
      void auth.signinRedirect({ state })
    }
  }, [mustSignIn, returnTo, auth])

  if (auth.error) {
    return (
      <FullPageMessage title="Sign-in failed">
        <p>We couldn't sign you in. Please try again.</p>
        <Button onClick={() => void auth.signinRedirect({ state: { returnTo } satisfies SigninState })}>
          Sign in again
        </Button>
      </FullPageMessage>
    )
  }
  if (!auth.isAuthenticated) {
    return <FullPageMessage title="Redirecting to sign in…" />
  }
  return children ?? <Outlet />
}
