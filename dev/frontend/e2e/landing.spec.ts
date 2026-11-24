import { expect, test } from '@playwright/test'

test('the landing page shows the TEST MODE banner, demo accounts and architecture', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('status', { name: 'Test mode' })).toContainText('TEST MODE: no real money')
  await expect(page.getByRole('heading', { level: 1 })).toContainText('TrustLedger')
  await expect(page.getByRole('img', { name: /Architecture/ })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'customer1', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'auditor1', exact: true })).toBeVisible()
})

test('a protected page sends a signed-out user to Keycloak with PKCE', async ({ page }) => {
  // No Keycloak in CI: capture the authorize request instead of following it.
  let authorizeUrl: URL | undefined
  await page.route('http://localhost:8180/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/.well-known/openid-configuration')) {
      await route.fulfill({
        json: {
          issuer: 'http://localhost:8180/realms/trustledger',
          authorization_endpoint: 'http://localhost:8180/realms/trustledger/protocol/openid-connect/auth',
          token_endpoint: 'http://localhost:8180/realms/trustledger/protocol/openid-connect/token',
          end_session_endpoint: 'http://localhost:8180/realms/trustledger/protocol/openid-connect/logout',
          jwks_uri: 'http://localhost:8180/realms/trustledger/protocol/openid-connect/certs',
        },
      })
      return
    }
    authorizeUrl = url
    await route.fulfill({ body: 'Keycloak login (stub)', contentType: 'text/plain' })
  })

  await page.goto('/customer')

  await expect.poll(() => authorizeUrl?.pathname).toContain('/protocol/openid-connect/auth')
  const params = authorizeUrl?.searchParams
  expect(params?.get('client_id')).toBe('trustledger-web')
  expect(params?.get('response_type')).toBe('code')
  expect(params?.get('code_challenge_method')).toBe('S256')
  expect(params?.get('code_challenge')).toBeTruthy()
  expect(params?.get('redirect_uri')).toBe('http://localhost:4173/callback')
})

test('the TEST MODE banner is also on unknown pages', async ({ page }) => {
  await page.goto('/no-such-page')
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible()
  await expect(page.getByRole('status', { name: 'Test mode' })).toBeVisible()
})
