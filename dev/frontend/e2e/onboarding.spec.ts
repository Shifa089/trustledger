import { expect, test, type Page } from '@playwright/test'

/**
 * Day 30 check: a new demo customer completes minimum KYC and sees a wallet. Runs against the local stack
 * (`npm run e2e:stack`): Keycloak on 8180 with self-registration on (default role CUSTOMER), core-app on 8080.
 * Every run registers a fresh user with random fake data, so the spec can run again and again (the mobile number and
 * the user name must be unique).
 */
test.use({ locale: 'en-IN' })

const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
const digits = (n: number) => Array.from({ length: n }, () => Math.floor(Math.random() * 10)).join('')
const letters = (n: number) => Array.from({ length: n }, () => LETTERS[Math.floor(Math.random() * 26)]).join('')

async function registerNewCustomer(page: Page, username: string) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.getByRole('link', { name: 'Register' }).click()
  await page.locator('#firstName').fill('Demo')
  await page.locator('#lastName').fill('Onboarding')
  await page.locator('#email').fill(`${username}@example.test`)
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(`Fake-${digits(8)}-pass`)
  await page.locator('#password-confirm').fill(await page.locator('#password').inputValue())
  await page.locator('input[type="submit"], button[type="submit"]').first().click()
}

test.describe('@stack onboarding', () => {
  test('a new customer completes minimum KYC with the keyboard and sees a MIN wallet', async ({ page }) => {
    const username = `demo-${Date.now().toString(36)}-${digits(4)}`
    await registerNewCustomer(page, username)

    // A brand-new customer has no wallet yet.
    await expect(page).toHaveURL(/\/customer$/)
    await expect(page.getByText('No wallet yet')).toBeVisible()
    await page.getByRole('link', { name: 'Complete KYC' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'KYC' })).toBeVisible()
    await expect(page.getByText('Use fake data, this is a demo')).toBeVisible()
    await page.getByRole('link', { name: 'Start minimum KYC' }).click()

    // Keyboard only: focus the first field, then Tab between labelled fields and submit with Enter.
    await page.getByLabel('Full name').focus()
    await page.keyboard.type('Demo Onboarding')
    await page.keyboard.press('Tab')
    await expect(page.getByLabel('Date of birth')).toBeFocused()
    await page.keyboard.type('15041995') // dd mm yyyy segments in an en-IN date input
    // In Chrome, Tab first walks the date input's own segments; keep tabbing until the next field has focus.
    const mobile = page.getByLabel('Mobile number')
    for (let i = 0; i < 4 && (await page.locator('#mobile:focus').count()) === 0; i++) {
      await page.keyboard.press('Tab')
    }
    await expect(mobile).toBeFocused()
    await expect(page.getByLabel('Date of birth')).toHaveValue('1995-04-15')
    await page.keyboard.type(`9${digits(9)}`)
    await page.keyboard.press('Tab')
    await expect(page.getByLabel('PAN')).toBeFocused()
    await page.keyboard.type(`${letters(3)}P${letters(1)}${digits(4)}${letters(1)}`.toLowerCase())
    await page.keyboard.press('Enter')

    // The wallet is created asynchronously after minimum KYC; the dashboard waits for it.
    await expect(page).toHaveURL(/\/customer$/)
    await expect(page.getByTestId('wallet-tier')).toHaveText('Minimum KYC', { timeout: 20_000 })
    await expect(page.getByTestId('amount-balance')).toHaveText('₹0.00')
    await expect(page.getByRole('progressbar', { name: 'Wallet balance' })).toHaveAttribute(
      'aria-valuetext',
      '₹0.00 of ₹10,000.00, 0%',
    )
    await expect(page.getByText('Sending money needs full KYC.')).toBeVisible()
    await expect(page.getByText('No money moved in the last 30 days.')).toBeVisible()

    // KYC status now offers full KYC.
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'KYC' }).click()
    await expect(page.getByText('Minimum KYC', { exact: true }).first()).toBeVisible()
    await expect(page.getByRole('link', { name: 'Start full KYC' })).toBeVisible()

    // History is empty but works.
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'History' }).click()
    await expect(page.getByText('No transactions yet.')).toBeVisible()
  })
})
