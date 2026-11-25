import { expect, test } from '@playwright/test'
import type { Topup } from '../src/api/types'
import {
  approveKyc,
  eventually,
  hasStaffSecrets,
  hasWebhookSecret,
  nav,
  newCustomerWithSubmittedKyc,
  sendCapturedWebhook,
  signIn,
  stubRazorpayCheckout,
} from './stack'

/**
 * Day 32 check: AML R2 (structuring) end to end. A fresh FULL customer adds ₹46,000 three times on the real Add money
 * screen (Razorpay Checkout stubbed, each payment confirmed by a signed TEST-mode webhook). The AML service raises a
 * HIGH R2 alert, asks core-app to freeze the wallet (FR-AML-3), and the freeze shows on the customer dashboard and in
 * the compliance console. Local stack only; needs the dev password, TOTP secret and Razorpay TEST webhook secret.
 */
test.describe('@stack aml', () => {
  test.skip(!hasStaffSecrets || !hasWebhookSecret, 'Set the dev password, TOTP secret and RAZORPAY_WEBHOOK_SECRET')
  test.setTimeout(240_000)

  test('three ₹46,000 top-ups freeze the wallet, visible to the customer and the officer', async ({ browser, request }) => {
    const customer = await newCustomerWithSubmittedKyc(browser)
    const officer1 = await signIn(browser, 'officer1', /\/compliance$/)
    const officer2 = await signIn(browser, 'officer2', /\/compliance$/)
    await approveKyc(officer1, officer2, customer.kycCaseId)

    const page = customer.page
    await page.goto('/customer')
    await eventually(page, async () => {
      await expect(page.getByTestId('wallet-tier')).toHaveText('Full KYC', { timeout: 2_000 })
    })

    // The customer's own wallet ID, so the officer can filter to exactly this wallet.
    await nav(page, 'Send money').click()
    const walletId = (await page.getByTestId('my-wallet-id').textContent())?.trim() ?? ''
    expect(walletId).toMatch(/^[0-9a-f-]{36}$/)

    // Three top-ups through the real screen; each is captured by a signed webhook.
    await stubRazorpayCheckout(page)
    await nav(page, 'Add money').click()
    for (let i = 0; i < 3; i++) {
      if (i > 0) {
        await page.getByRole('button', { name: 'Add more money' }).click()
      }
      await page.getByLabel('Amount (₹)').fill('46000')
      const created = page.waitForResponse(
        (response) => response.request().method() === 'POST' && response.url().endsWith('/api/v1/topups'),
      )
      await page.getByRole('button', { name: 'Add money' }).click()
      const topup = (await (await created).json()) as Topup
      expect(topup.gatewayOrderId).toBeTruthy()
      await sendCapturedWebhook(request, topup.gatewayOrderId ?? '', topup.amount)
      const added = page.getByText('₹46,000.00 was added to your wallet.')
      // After the third, the AML freeze can land within a second, and the page then shows "Wallet frozen" instead.
      await expect(i < 2 ? added : added.or(page.getByText('Wallet frozen'))).toBeVisible({ timeout: 70_000 })
    }

    // The customer sees the freeze (never the reason).
    await page.goto('/customer')
    await eventually(
      page,
      async () => {
        await expect(page.getByText('Wallet frozen')).toBeVisible({ timeout: 2_000 })
      },
      60_000,
    )
    await expect(page.getByText('R2')).toHaveCount(0)

    // The officer finds the HIGH R2 alert, its case and the wallet frozen by the AML service.
    await nav(officer1, 'AML alerts').click()
    await officer1.getByLabel('Rule').selectOption('R2')
    await officer1.getByLabel('Severity').selectOption('HIGH')
    await officer1.getByLabel('Wallet ID').fill(walletId)
    const row = officer1.getByRole('row').filter({ hasText: 'R2 Structuring' }).first()
    await expect(row).toBeVisible()
    await row.getByRole('link', { name: /Open case/ }).click()
    await expect(officer1.getByRole('heading', { level: 1, name: 'AML case' })).toBeVisible()
    await expect(officer1.getByRole('list', { name: 'Case timeline' })).toContainText('Top-up ₹46,000.00')
    await expect(officer1.getByRole('list', { name: 'Case timeline' })).toContainText('Freeze requested (R2 Structuring)')
    await expect(officer1.getByTestId('linked-wallet-status')).toContainText('Frozen by the AML service')

    await officer1.getByRole('link', { name: 'Open the wallet to freeze or unfreeze it' }).click()
    await expect(officer1.getByTestId('wallet-status')).toHaveText('Frozen')
    await expect(officer1.getByTestId('freeze-source')).toContainText('AML service (automatic)')
  })
})
