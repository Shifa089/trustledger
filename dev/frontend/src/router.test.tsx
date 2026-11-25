import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Role } from '@/auth/roles'
import { routes } from '@/router'
import { fakeAuth, renderRoutes } from '@/test/render'

function mainNav() {
  return within(screen.getByRole('navigation', { name: 'Main' }))
}

describe('routes', () => {
  it('shows the public landing page with the TEST MODE banner, demo accounts and architecture', async () => {
    renderRoutes(routes, { path: '/', auth: fakeAuth().auth })

    expect(await screen.findByRole('heading', { level: 1, name: /TrustLedger/ })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Test mode' })).toHaveTextContent('TEST MODE: no real money')
    expect(screen.getByRole('img', { name: /Architecture/ })).toHaveAttribute('src', '/architecture.svg')
    for (const user of ['customer1', 'customer2', 'officer1', 'officer2', 'admin1', 'auditor1']) {
      expect(screen.getByText(user)).toBeInTheDocument()
    }
    expect(screen.getByRole('link', { name: /GitHub/ })).toHaveAttribute('href', 'https://github.com/example/trustledger')
    expect(screen.queryByRole('link', { name: /video/ })).not.toBeInTheDocument() // VITE_VIDEO_URL empty in tests
  })

  it('starts a sign-in that returns to the requested page for a signed-out user', async () => {
    const { auth, signinRedirect } = fakeAuth()
    renderRoutes(routes, { path: '/customer?tab=1', auth })

    await waitFor(() => {
      expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/customer?tab=1' } })
    })
    expect(screen.getByText('Redirecting to sign in…')).toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Test mode' })).toBeInTheDocument()
  })

  it.each<[Role, string, string]>([
    ['CUSTOMER', '/customer', 'Customer dashboard'],
    ['COMPLIANCE_OFFICER', '/compliance', 'Compliance'],
    ['ADMIN', '/admin', 'Admin'],
    ['AUDITOR', '/audit', 'Audit'],
  ])('sends a %s from /app to %s', async (role, path, heading) => {
    const { router } = renderRoutes(routes, { path: '/app', auth: fakeAuth([role]).auth })

    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(path)
    expect(screen.getByRole('status', { name: 'Test mode' })).toBeInTheDocument()
  })

  it('gives each role its own navigation', async () => {
    const { unmount } = renderRoutes(routes, { path: '/customer', auth: fakeAuth(['CUSTOMER']).auth })
    await screen.findByRole('heading', { level: 1, name: 'Customer dashboard' })
    expect(mainNav().getByRole('link', { name: 'Dashboard' })).toBeInTheDocument()
    expect(mainNav().queryByText('KYC queue')).not.toBeInTheDocument()
    expect(mainNav().queryByText('Audit log')).not.toBeInTheDocument()
    unmount()

    renderRoutes(routes, { path: '/compliance', auth: fakeAuth(['COMPLIANCE_OFFICER'], 'officer1').auth })
    await screen.findByRole('heading', { level: 1, name: 'Compliance' })
    expect(mainNav().getByText('KYC queue')).toBeInTheDocument()
    expect(mainNav().getByText('AML cases')).toBeInTheDocument()
    expect(mainNav().queryByRole('link', { name: 'Dashboard' })).not.toBeInTheDocument()
    expect(screen.getByTestId('username')).toHaveTextContent('officer1')
  })

  it('sends a customer who opens a compliance URL to /forbidden', async () => {
    const { router } = renderRoutes(routes, { path: '/compliance', auth: fakeAuth(['CUSTOMER']).auth })

    expect(await screen.findByRole('heading', { name: 'Not available for your role' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/forbidden')
  })

  it.each([
    ['/customer/kyc', 'KYC'],
    ['/customer/kyc/min', 'KYC'],
    ['/customer/kyc/full', 'KYC'],
    ['/customer/history', 'History'],
    ['/customer/add-money', 'Add money'],
    ['/customer/send', 'Send money'],
    ['/customer/privacy', 'Privacy'],
  ])('links %s in the customer navigation and keeps it for customers only', async (path, navLabel) => {
    const { unmount } = renderRoutes(routes, { path: '/customer', auth: fakeAuth(['CUSTOMER']).auth })
    expect(await mainNav().findByRole('link', { name: navLabel })).toHaveAttribute('href', path.startsWith('/customer/kyc') ? '/customer/kyc' : path)
    unmount()

    const { router } = renderRoutes(routes, { path, auth: fakeAuth(['COMPLIANCE_OFFICER']).auth })
    expect(await screen.findByRole('heading', { name: 'Not available for your role' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/forbidden')
  })

  it.each([
    ['/customer/add-money', 'Add money'],
    ['/customer/send', 'Send money'],
    ['/customer/privacy', 'Privacy'],
  ])('opens %s for a customer', async (path, heading) => {
    renderRoutes(routes, { path, auth: fakeAuth(['CUSTOMER']).auth })
    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Test mode' })).toBeInTheDocument()
  })

  it('loads the profile from core-app and open cases from the AML service', async () => {
    renderRoutes(routes, { path: '/compliance', auth: fakeAuth(['COMPLIANCE_OFFICER']).auth })

    expect(await screen.findByText('Customer One')).toBeInTheDocument()
    expect(await screen.findByTestId('open-cases')).toHaveTextContent('3')
  })

  it('signs out through Keycloak', async () => {
    const { auth, signoutRedirect } = fakeAuth(['AUDITOR'])
    renderRoutes(routes, { path: '/audit', auth })

    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }))
    expect(signoutRedirect).toHaveBeenCalledOnce()
  })
})
