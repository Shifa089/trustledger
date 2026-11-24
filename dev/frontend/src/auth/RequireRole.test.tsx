import { screen } from '@testing-library/react'
import type { RouteObject } from 'react-router'
import { fakeAuth, renderRoutes } from '@/test/render'
import type { Role } from './roles'
import { RequireRole } from './RequireRole'

const routes: RouteObject[] = [
  {
    path: '/compliance',
    element: <RequireRole roles={['COMPLIANCE_OFFICER']} />,
    children: [{ index: true, element: <h1>Compliance area</h1> }],
  },
  { path: '/audit', element: <RequireRole roles={['AUDITOR', 'ADMIN']}>Audit area</RequireRole> },
  { path: '/forbidden', element: <h1>Forbidden page</h1> },
]

describe('RequireRole', () => {
  it.each<Role[]>([['CUSTOMER'], ['ADMIN'], ['AUDITOR']])('redirects a %s to /forbidden', async (role) => {
    const { router } = renderRoutes(routes, { path: '/compliance', auth: fakeAuth([role]).auth })

    expect(await screen.findByRole('heading', { name: 'Forbidden page' })).toBeInTheDocument()
    expect(screen.queryByText('Compliance area')).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/forbidden')
  })

  it('redirects a user without any role', async () => {
    renderRoutes(routes, { path: '/compliance', auth: fakeAuth([]).auth })
    expect(await screen.findByRole('heading', { name: 'Forbidden page' })).toBeInTheDocument()
  })

  it('shows the nested route to the right role', async () => {
    renderRoutes(routes, { path: '/compliance', auth: fakeAuth(['COMPLIANCE_OFFICER']).auth })
    expect(await screen.findByRole('heading', { name: 'Compliance area' })).toBeInTheDocument()
  })

  it('accepts any one of several roles and renders children', async () => {
    renderRoutes(routes, { path: '/audit', auth: fakeAuth(['ADMIN']).auth })
    expect(await screen.findByText('Audit area')).toBeInTheDocument()
  })
})
