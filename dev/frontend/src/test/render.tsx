import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, type RenderResult } from '@testing-library/react'
import type { User } from 'oidc-client-ts'
import { AuthContext, type AuthContextProps } from 'react-oidc-context'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import type { Role } from '@/auth/roles'

/** An unsigned JWT with Keycloak's realm_access claim: enough for the UI, which never verifies tokens. */
export function fakeAccessToken(roles: readonly string[], username = 'customer1'): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${encode({ alg: 'none' })}.${encode({ preferred_username: username, realm_access: { roles } })}.sig`
}

export interface FakeAuth {
  auth: AuthContextProps
  signinRedirect: ReturnType<typeof vi.fn>
  signoutRedirect: ReturnType<typeof vi.fn>
}

/** A react-oidc-context value: signed in with `roles`, or signed out when roles is undefined. */
export function fakeAuth(roles?: readonly Role[], username = 'customer1'): FakeAuth {
  const signinRedirect = vi.fn(() => Promise.resolve())
  const signoutRedirect = vi.fn(() => Promise.resolve())
  const user =
    roles === undefined
      ? undefined
      : ({
          access_token: fakeAccessToken(roles, username),
          profile: { sub: 'user-1', preferred_username: username },
          expired: false,
          state: undefined,
        } as unknown as User)
  const auth = {
    user,
    isLoading: false,
    isAuthenticated: user !== undefined,
    activeNavigator: undefined,
    error: undefined,
    settings: {},
    events: {},
    signinRedirect,
    signoutRedirect,
  } as unknown as AuthContextProps
  return { auth, signinRedirect, signoutRedirect }
}

export function renderRoutes(
  routes: RouteObject[],
  { path, auth }: { path: string; auth: AuthContextProps },
): RenderResult & { router: ReturnType<typeof createMemoryRouter> } {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const result = render(
    <AuthContext.Provider value={auth}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthContext.Provider>,
  )
  return { ...result, router }
}
