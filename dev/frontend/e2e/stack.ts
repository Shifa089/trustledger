import { createHmac, randomBytes } from 'node:crypto'
import { expect, type APIRequestContext, type Browser, type Page } from '@playwright/test'

/**
 * Helpers for the `@stack` specs (Day 32): real Keycloak logins against the local stack, fresh fake customers taken
 * through KYC, and Razorpay TEST-mode webhooks signed like Razorpay does. LOCAL ONLY: the API base URL is fixed to
 * localhost, so a forged "payment" can never reach a shared or deployed environment (same rule as
 * scripts/aml-demo-webhooks.sh).
 */

export const password = process.env.TRUSTLEDGER_DEV_USER_PASSWORD ?? ''
const totpSecret = process.env.TRUSTLEDGER_DEV_TOTP_SECRET ?? ''
const webhookSecret = process.env.RAZORPAY_WEBHOOK_SECRET ?? ''
export const CORE_API = 'http://localhost:8080'

export const hasPassword = password !== '' && password !== 'change-me'
export const hasStaffSecrets = hasPassword && totpSecret !== '' && totpSecret !== 'change-me'
export const hasWebhookSecret = webhookSecret !== '' && webhookSecret !== 'change-me'

const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
export const digits = (n: number) => Array.from({ length: n }, () => Math.floor(Math.random() * 10)).join('')
const letters = (n: number) => Array.from({ length: n }, () => LETTERS[Math.floor(Math.random() * 26)]).join('')

/**
 * RFC 6238 TOTP as the realm is configured (HmacSHA1, 6 digits, 30 s). The realm stores the raw secret, so it is the
 * HMAC key as is (scripts/aml-demo-webhooks.sh totp does the same).
 */
export function totp(secret: string, counter: number): string {
  const message = Buffer.alloc(8)
  message.writeBigUInt64BE(BigInt(counter))
  const hmac = createHmac('sha1', secret).update(message).digest()
  const offset = (hmac[hmac.length - 1] ?? 0) & 0x0f
  const code = (hmac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000
  return code.toString().padStart(6, '0')
}

/** Keycloak refuses a code twice (otpPolicyCodeReusable=false): one login per user per 30 s window. */
const lastCounter = new Map<string, number>()

async function freshCode(username: string): Promise<string> {
  let counter = Math.floor(Date.now() / 30_000)
  if (lastCounter.get(username) === counter) {
    await new Promise((resolve) => setTimeout(resolve, 30_000 - (Date.now() % 30_000) + 500))
    counter = Math.floor(Date.now() / 30_000)
  }
  lastCounter.set(username, counter)
  return totp(totpSecret, counter)
}

/** Signs in through Keycloak in a fresh browser context; staff users also enter their TOTP code. */
export async function signIn(browser: Browser, username: string, landing: RegExp): Promise<Page> {
  const context = await browser.newContext({ locale: 'en-IN' })
  const page = await context.newPage()
  await page.goto('/')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password)
  await page.locator('#kc-login').click()
  if (!username.startsWith('customer')) {
    await page.locator('#otp').fill(await freshCode(username))
    await page.locator('#kc-login').click()
  }
  await expect(page).toHaveURL(landing)
  return page
}

export function nav(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name, exact: true })
}

/** A 1×1 PNG: a real image, so the backend's content check accepts it. Fake data only. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

export interface Customer {
  page: Page
  username: string
  password: string
  kycCaseId: string
}

/**
 * Registers a brand-new fake customer, completes minimum KYC, then submits full KYC with three fake documents.
 * Returns the SUBMITTED case's ID, read from the POST /kyc/full response.
 */
export async function newCustomerWithSubmittedKyc(browser: Browser): Promise<Customer> {
  const username = `e2e-${Date.now().toString(36)}-${digits(4)}`
  const customerPassword = `Fake-${digits(8)}-pass`
  const context = await browser.newContext({ locale: 'en-IN' })
  const page = await context.newPage()

  await page.goto('/')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.getByRole('link', { name: 'Register' }).click()
  await page.locator('#firstName').fill('Demo')
  await page.locator('#lastName').fill('Staff')
  await page.locator('#email').fill(`${username}@example.test`)
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(customerPassword)
  await page.locator('#password-confirm').fill(customerPassword)
  await page.locator('input[type="submit"], button[type="submit"]').first().click()
  await expect(page).toHaveURL(/\/customer$/)

  // Minimum KYC (fake data).
  await page.goto('/customer/kyc/min')
  await page.getByLabel('Full name').fill('Demo Staff')
  await page.getByLabel('Date of birth').fill('1994-06-15')
  await page.getByLabel('Mobile number').fill(`9${digits(9)}`)
  await page.getByLabel('PAN').fill(`${letters(3)}P${letters(1)}${digits(4)}${letters(1)}`)
  await page.getByLabel('PAN').press('Enter')
  await expect(page.getByTestId('wallet-tier')).toHaveText('Minimum KYC', { timeout: 20_000 })

  // Full KYC: the address creates the SUBMITTED case, then the documents.
  await page.goto('/customer/kyc/full')
  await page.getByRole('textbox', { name: 'Address' }).fill('12 Fake Street, Testpur 560001')
  const created = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/api/v1/kyc/full'),
  )
  await page.getByRole('button', { name: 'Continue to documents' }).click()
  const body = (await (await created).json()) as { id?: string; caseId?: string }
  const kycCaseId = body.caseId ?? body.id ?? ''
  expect(kycCaseId).toMatch(/^[0-9a-f-]{36}$/)
  for (const type of ['PAN_CARD', 'ADDRESS_PROOF', 'PHOTO']) {
    await page.locator(`#document-${type}`).setInputFiles({ name: `${type.toLowerCase()}.png`, mimeType: 'image/png', buffer: PNG })
    await expect(page.locator(`#document-${type}-status`)).toHaveText(/Uploaded/, { timeout: 15_000 })
  }
  return { page, username, password: customerPassword, kycCaseId }
}

/** officer1 verifies, officer2 approves: the full maker-checker path (FR-KYC-3). */
export async function approveKyc(officer1: Page, officer2: Page, caseId: string) {
  await officer1.goto(`/compliance/kyc/${caseId}`)
  await officer1.getByRole('button', { name: 'Verify' }).click()
  await expect(officer1.getByText('Case verified. A different officer must now approve it.')).toBeVisible()
  await officer2.goto(`/compliance/kyc/${caseId}`)
  await officer2.getByRole('button', { name: 'Approve' }).click()
  await expect(officer2.getByText('Case approved. The customer is now FULL KYC.')).toBeVisible()
}

/** Reloads until the condition holds: wallet changes arrive through events, a little after the request. */
export async function eventually(page: Page, check: () => Promise<void>, timeoutMs = 30_000) {
  const until = Date.now() + timeoutMs
  for (;;) {
    try {
      await check()
      return
    } catch (error) {
      if (Date.now() > until) throw error
      await page.waitForTimeout(1500)
      await page.reload()
    }
  }
}

/**
 * Stands in for "the customer pays on Razorpay and Razorpay sends the webhook": a payment.captured event for the
 * order, signed with the TEST webhook secret (hex HMAC-SHA256 over the exact body bytes), posted to localhost.
 */
export async function sendCapturedWebhook(request: APIRequestContext, orderId: string, amount: string) {
  const match = /^(\d+)\.(\d{2})$/.exec(amount)
  if (match === null) throw new Error(`unexpected amount ${amount}`)
  const paise = Number(match[1]) * 100 + Number(match[2]) // integers only; ₹46,000 is far below 2^53
  const now = Math.floor(Date.now() / 1000)
  const paymentId = `pay_E2e${randomBytes(7).toString('hex')}`
  const body = JSON.stringify({
    entity: 'event',
    account_id: 'acc_Demo',
    event: 'payment.captured',
    contains: ['payment'],
    payload: {
      payment: {
        entity: {
          id: paymentId,
          entity: 'payment',
          amount: paise,
          currency: 'INR',
          status: 'captured',
          order_id: orderId,
          method: 'upi',
          captured: true,
          created_at: now,
        },
      },
    },
    created_at: now,
  })
  const signature = createHmac('sha256', webhookSecret).update(body).digest('hex')
  const response = await request.post(`${CORE_API}/api/v1/webhooks/razorpay`, {
    data: body,
    headers: {
      'Content-Type': 'application/json',
      'X-Razorpay-Signature': signature,
      'X-Razorpay-Event-Id': `evt_e2e_${randomBytes(8).toString('hex')}`,
    },
  })
  expect(response.status()).toBe(200)
  return paymentId
}

/**
 * Replaces Razorpay's hosted Checkout script with a stub that reports a payment at once, so the real Add money
 * screen runs without the external widget. The money still only arrives through the signed webhook.
 */
export async function stubRazorpayCheckout(page: Page) {
  await page.route('https://checkout.razorpay.com/v1/checkout.js', (route) =>
    route.fulfill({
      contentType: 'application/javascript',
      body: `window.Razorpay = class {
        constructor(options) { this.options = options }
        on() {}
        open() { setTimeout(() => this.options.handler({ razorpay_payment_id: 'pay_E2eStub' }), 50) }
      }`,
    }),
  )
}
