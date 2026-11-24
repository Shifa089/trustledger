import { useAuth } from 'react-oidc-context'
import { Link, Navigate } from 'react-router'
import type { SigninState } from '@/auth/oidc'
import { homePathFor } from '@/auth/roles'
import { useRoles } from '@/auth/useRoles'
import { FullPageMessage } from '@/components/FullPageMessage'
import { Button } from '@/components/ui/button'

/** /app: send a signed-in user to their role's home. */
export function AppHomeRedirect() {
  return <Navigate to={homePathFor(useRoles())} replace />
}

/**
 * /callback: react-oidc-context has already exchanged the code (with the PKCE verifier) for tokens and removed
 * `code` and `state` from the address bar; continue to the page the user first asked for.
 */
export function CallbackPage() {
  const auth = useAuth()
  if (auth.error) {
    return (
      <FullPageMessage title="Sign-in failed">
        <p>The sign-in could not be completed. Please try again.</p>
        <Button asChild>
          <Link to="/">Back to the home page</Link>
        </Button>
      </FullPageMessage>
    )
  }
  if (!auth.isAuthenticated) {
    return <FullPageMessage title="Signing you in…" />
  }
  const state = auth.user?.state as SigninState | undefined
  const returnTo = state?.returnTo?.startsWith('/') === true && !state.returnTo.startsWith('//') ? state.returnTo : '/app'
  return <Navigate to={returnTo} replace />
}

export function ForbiddenPage() {
  return (
    <FullPageMessage title="Not available for your role">
      <p className="text-muted-foreground">Your account doesn't have access to that page.</p>
      <Button asChild>
        <Link to="/app">Go to your home page</Link>
      </Button>
    </FullPageMessage>
  )
}

export function NotFoundPage() {
  return (
    <FullPageMessage title="Page not found">
      <Button asChild>
        <Link to="/">Back to the home page</Link>
      </Button>
    </FullPageMessage>
  )
}
