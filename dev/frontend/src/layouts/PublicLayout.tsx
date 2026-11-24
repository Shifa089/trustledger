import { useAuth } from 'react-oidc-context'
import { Link, Outlet } from 'react-router'
import type { SigninState } from '@/auth/oidc'
import { Button } from '@/components/ui/button'
import { Logo } from './Logo'

export function PublicLayout() {
  const auth = useAuth()
  return (
    <>
      <header className="border-b">
        <div className="mx-auto flex max-w-6xl items-center px-6 py-3">
          <Logo />
          <div className="ml-auto">
            {auth.isAuthenticated ? (
              <Button asChild>
                <Link to="/app">Open the app</Link>
              </Button>
            ) : (
              <Button onClick={() => void auth.signinRedirect({ state: { returnTo: '/app' } satisfies SigninState })}>
                Sign in
              </Button>
            )}
          </div>
        </div>
      </header>
      <Outlet />
    </>
  )
}
