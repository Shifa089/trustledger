import { expect, test } from '@playwright/test'

/**
 * Real login against the running local stack (Day 29 check): Keycloak on 8180, core-app on 8080 with
 * `local,demo`, the AML service on 8081, and `npm run dev`. Run with `npm run e2e:stack`.
 * Only CUSTOMER and AUDITOR users here: officers and admin need an OTP.
 */
const password = process.env.TRUSTLEDGER_DEV_USER_PASSWORD

test.describe('@stack login', () => {
  test.skip(password === undefined || password === '', 'Set TRUSTLEDGER_DEV_USER_PASSWORD (from dev/.env)')

  async function login(page: import('@playwright/test').Page, username: string) {
    await page.goto('/')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await page.locator('#username').fill(username)
    await page.locator('#password').fill(password ?? '')
    await page.locator('#kc-login').click()
  }

  test('customer1 logs in and sees the customer dashboard shell', async ({ page }) => {
    await login(page, 'customer1')

    await expect(page).toHaveURL(/\/customer$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Customer dashboard' })).toBeVisible()
    await expect(page.getByTestId('username')).toHaveText('customer1')
    await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Dashboard' })).toBeVisible()
    await expect(page.getByRole('status', { name: 'Test mode' })).toBeVisible()
    // GET /me went through CORS with the bearer token.
    await expect(page.getByText('Your profile')).toBeVisible()
    await expect(page.getByText("Couldn't load your profile")).toHaveCount(0)

    // Tokens are in memory only.
    const storage = await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]))
    expect(storage).not.toMatch(/access_token|refresh_token|id_token/)
  })

  test('auditor1 sees different navigation and can sign out', async ({ page }) => {
    await login(page, 'auditor1')

    await expect(page).toHaveURL(/\/audit$/)
    const nav = page.getByRole('navigation', { name: 'Main' })
    await expect(nav.getByRole('link', { name: 'Audit' })).toBeVisible()
    await expect(nav.getByRole('link', { name: 'Dashboard' })).toHaveCount(0)

    await page.getByRole('button', { name: 'Sign out' }).click()
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
  })
})
