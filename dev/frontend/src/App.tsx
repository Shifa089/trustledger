import { QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from 'react-oidc-context'
import { createBrowserRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { userManager } from '@/auth/oidc'
import { createQueryClient } from '@/queryClient'
import { routes } from '@/router'

const queryClient = createQueryClient()
const router = createBrowserRouter(routes)

/** Remove ?code=&state= from the address bar after the token exchange; CallbackPage then navigates on. */
function onSigninCallback(): void {
  window.history.replaceState({}, document.title, window.location.pathname)
}

export function App() {
  return (
    <AuthProvider userManager={userManager} onSigninCallback={onSigninCallback}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthProvider>
  )
}
