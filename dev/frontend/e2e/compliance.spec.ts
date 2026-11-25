import { expect, test } from '@playwright/test'
import { eventually, hasStaffSecrets, nav, newCustomerWithSubmittedKyc, signIn } from './stack'

/**
 * Day 32 check: maker-checker end to end (FR-KYC-3). A fresh fake customer submits full KYC; officer1 verifies and is
 * refused when trying to approve their own verification; officer2 approves; the customer's wallet becomes FULL.
 * Also checks that typed URLs of the other side are refused both ways. Local stack only (`npm run e2e:stack`), needs
 * TRUSTLEDGER_DEV_USER_PASSWORD and TRUSTLEDGER_DEV_TOTP_SECRET (from dev/.env).
 */
test.describe('@stack compliance', () => {
  test.skip(!hasStaffSecrets, 'Set TRUSTLEDGER_DEV_USER_PASSWORD and TRUSTLEDGER_DEV_TOTP_SECRET (from dev/.env)')
  test.setTimeout(180_000)

  test('officer1 verifies, officer2 approves, and the customer becomes FULL', async ({ browser }) => {
    const customer = await newCustomerWithSubmittedKyc(browser)

    // Maker: officer1 finds the case in the queue and verifies it.
    const officer1 = await signIn(browser, 'officer1', /\/compliance$/)
    await nav(officer1, 'KYC queue').click()
    await expect(officer1.getByRole('link', { name: `Review case ${customer.kycCaseId}` })).toBeVisible()
    await officer1.goto(`/compliance/kyc/${customer.kycCaseId}`)
    await expect(officer1.getByText('12 Fake Street, Testpur 560001')).toBeVisible()

    // The document viewer gets a presigned link for each document.
    await officer1.getByRole('button', { name: 'View PAN card' }).click()
    await expect(officer1.getByRole('img', { name: 'PAN card uploaded by the customer' })).toBeVisible()
    await expect(officer1.getByTestId('link-expires')).toBeVisible()

    await officer1.getByRole('button', { name: 'Verify' }).click()
    await expect(officer1.getByText('Case verified. A different officer must now approve it.')).toBeVisible()

    // Checker step by the maker: the UI warns, the server refuses with 409.
    await expect(officer1.getByText('You verified this case')).toBeVisible()
    await officer1.getByRole('button', { name: 'Approve' }).click()
    await expect(officer1.getByTestId('maker-checker-error')).toContainText('Maker-checker: approval refused')
    await expect(officer1.getByTestId('case-status')).toHaveText('Verified')

    // Checker: officer2 approves.
    const officer2 = await signIn(browser, 'officer2', /\/compliance$/)
    await officer2.goto(`/compliance/kyc/${customer.kycCaseId}`)
    await expect(officer2.getByText('You verified this case')).toHaveCount(0)
    await officer2.getByRole('button', { name: 'Approve' }).click()
    await expect(officer2.getByText('Case approved. The customer is now FULL KYC.')).toBeVisible()

    // The wallet tier follows through the KycLevelUpgraded event.
    await customer.page.goto('/customer')
    await eventually(customer.page, async () => {
      await expect(customer.page.getByTestId('wallet-tier')).toHaveText('Full KYC', { timeout: 2_000 })
    })

    // Typed URLs of the other side are refused, both ways.
    await customer.page.goto('/compliance/kyc')
    await expect(customer.page).toHaveURL(/\/forbidden$/)
    await officer2.goto('/customer/send')
    await expect(officer2).toHaveURL(/\/forbidden$/)
    await officer2.goto('/audit/events')
    await expect(officer2).toHaveURL(/\/forbidden$/)
  })
})
