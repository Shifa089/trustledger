import { expect, test, type Browser, type Page } from '@playwright/test'

/**
 * Day 31 check: customer1 sends ₹100 to customer2, and customer2 sees it in their history. Runs against the local
 * stack with the demo seed (`npm run e2e:stack`; core-app with `local,demo`). The seeded customers have no mobile
 * number, so the transfer goes by wallet ID, read from customer2's Send money page. Each run uses a unique note, so
 * it can run again and again (customer1 starts with ₹50,000).
 */
const password = process.env.TRUSTLEDGER_DEV_USER_PASSWORD

async function signedIn(browser: Browser, username: string): Promise<Page> {
  const context = await browser.newContext({ locale: 'en-IN' })
  const page = await context.newPage()
  await page.goto('/')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password ?? '')
  await page.locator('#kc-login').click()
  await expect(page).toHaveURL(/\/customer$/)
  return page
}

function nav(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name })
}

test.describe('@stack transfer', () => {
  test.skip(password === undefined || password === '', 'Set TRUSTLEDGER_DEV_USER_PASSWORD (from dev/.env)')

  test('customer1 sends ₹100 to customer2, who sees it in history', async ({ browser }) => {
    const note = `e2e ${Date.now().toString(36)}`

    // customer2 shares their wallet ID.
    const receiver = await signedIn(browser, 'customer2')
    await nav(receiver, 'Send money').click()
    const walletId = (await receiver.getByTestId('my-wallet-id').textContent())?.trim() ?? ''
    expect(walletId).toMatch(/^[0-9a-f-]{36}$/)

    // customer1 sends ₹100 by wallet ID, with a confirm step.
    const sender = await signedIn(browser, 'customer1')
    await nav(sender, 'Send money').click()
    await sender.getByLabel('Send to').selectOption('walletId')
    await sender.getByLabel('Recipient wallet ID').fill(walletId)
    await sender.getByLabel('Amount (₹)').fill('100')
    await sender.getByLabel('Note (optional)').fill(note)
    await sender.getByRole('button', { name: 'Review' }).click()
    await expect(sender.getByTestId('confirm-amount')).toHaveText('₹100.00')

    const requests: string[] = []
    sender.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith('/api/v1/transfers')) {
        requests.push(request.headers()['idempotency-key'] ?? '')
      }
    })
    await sender.getByRole('button', { name: 'Send ₹100.00' }).dblclick()
    await expect(sender.getByText('Money sent')).toBeVisible()
    expect(requests).toHaveLength(1)
    expect(requests[0]).toMatch(/^[0-9a-f-]{36}$/)

    // customer2 sees the incoming transfer.
    await nav(receiver, 'History').click()
    const row = receiver.getByRole('row').filter({ hasText: note })
    await expect(row).toBeVisible({ timeout: 10_000 })
    await expect(row).toContainText('Received')
    await expect(row).toContainText('₹100.00')
  })
})
