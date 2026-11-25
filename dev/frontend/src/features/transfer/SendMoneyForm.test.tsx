import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { Transfer, TransferRequest } from '@/api/types'
import { fakeAuth, renderRoutes } from '@/test/render'
import { CORE, FULL_WALLET, problem, server } from '@/test/server'
import { SendMoneyForm } from './SendMoneyForm'
import { SendMoneyPage } from './SendMoneyPage'

const RECEIVER = '00000000-0000-7000-8000-00000000b002'

function transfer(body: TransferRequest, status: Transfer['status'] = 'COMPLETED'): Transfer {
  return {
    id: '00000000-0000-7000-8000-0000000000f1',
    status,
    senderWalletId: FULL_WALLET.walletId,
    receiverWalletId: RECEIVER,
    amount: body.amount,
    currency: 'INR',
    note: body.note ?? null,
    createdAt: '2026-11-25T06:00:00Z',
  }
}

interface Sent {
  body: TransferRequest
  key: string | null
}

/** Answers each POST /transfers with the next responder (the last one repeats), recording body and key. */
function onTransfer(...responders: ((body: TransferRequest) => Response | Promise<Response>)[]) {
  const sent: Sent[] = []
  server.use(
    http.post(`${CORE}/transfers`, async ({ request }) => {
      const body = (await request.json()) as TransferRequest
      sent.push({ body, key: request.headers.get('Idempotency-Key') })
      const respond = responders[Math.min(sent.length, responders.length) - 1]
      if (respond === undefined) throw new Error('no responder')
      return respond(body)
    }),
  )
  return sent
}

const created = (status: Transfer['status'] = 'COMPLETED') => (body: TransferRequest) =>
  HttpResponse.json(transfer(body, status), { status: 201 })

function setup() {
  const user = userEvent.setup()
  renderRoutes([{ path: '/send', element: <SendMoneyForm wallet={FULL_WALLET} /> }], {
    path: '/send',
    auth: fakeAuth(['CUSTOMER']).auth,
  })
  return user
}

async function fillAndReview(
  user: ReturnType<typeof userEvent.setup>,
  { mobile = '9876543210', amount = '100', note = '' }: { mobile?: string; amount?: string; note?: string } = {},
) {
  await user.type(screen.getByLabelText('Recipient mobile number'), mobile)
  await user.type(screen.getByLabelText('Amount (₹)'), amount)
  if (note !== '') await user.type(screen.getByLabelText('Note (optional)'), note)
  await user.click(screen.getByRole('button', { name: 'Review' }))
}

const sendButton = () => screen.getByRole('button', { name: /^(Send ₹|Try again|Sending)/ })

describe('SendMoneyForm', () => {
  it('sends the confirmed details with an Idempotency-Key and shows the result', async () => {
    const sent = onTransfer(created())
    const user = setup()
    await fillAndReview(user, { amount: '250.5', note: 'Dinner' })

    expect(screen.getByTestId('confirm-amount')).toHaveTextContent('₹250.50')
    expect(screen.getByTestId('confirm-recipient')).toHaveTextContent('Mobile 9876543210')
    expect(sent).toHaveLength(0) // nothing is sent before the confirm step

    await user.click(screen.getByRole('button', { name: 'Send ₹250.50' }))

    expect(await screen.findByText('Money sent')).toBeInTheDocument()
    expect(sent).toHaveLength(1)
    expect(sent[0]?.body).toEqual({ recipientMobile: '9876543210', amount: '250.50', note: 'Dinner' })
    expect(sent[0]?.key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(screen.getByTestId('transfer-result')).toHaveAttribute('data-status', 'COMPLETED')
  })

  it('sends by wallet ID, without a note when it is blank', async () => {
    const sent = onTransfer(created())
    const user = setup()
    await user.selectOptions(screen.getByLabelText('Send to'), 'walletId')
    await user.type(screen.getByLabelText('Recipient wallet ID'), RECEIVER.toUpperCase())
    await user.type(screen.getByLabelText('Amount (₹)'), '100')
    await user.click(screen.getByRole('button', { name: 'Review' }))
    await user.click(screen.getByRole('button', { name: 'Send ₹100.00' }))

    await screen.findByText('Money sent')
    expect(sent[0]?.body).toEqual({ recipientWalletId: RECEIVER, amount: '100.00' })
  })

  it('a double click sends one request', async () => {
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const sent = onTransfer(async (body) => {
      await gate
      return HttpResponse.json(transfer(body), { status: 201 })
    })
    const user = setup()
    await fillAndReview(user)

    await user.dblClick(screen.getByRole('button', { name: 'Send ₹100.00' }))
    await waitFor(() => {
      expect(sendButton()).toBeDisabled()
    })
    expect(sendButton()).toHaveTextContent('Sending…')
    expect(screen.getByRole('button', { name: 'Back to edit' })).toBeDisabled()
    release()

    await screen.findByText('Money sent')
    expect(sent).toHaveLength(1)
  })

  it('a retry after a network error reuses the same key', async () => {
    const sent = onTransfer(() => HttpResponse.error(), created())
    const user = setup()
    await fillAndReview(user)

    await user.click(screen.getByRole('button', { name: 'Send ₹100.00' }))
    expect(await screen.findByText('Not confirmed yet')).toBeInTheDocument()
    expect(screen.getByText(/Trying again is safe/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await screen.findByText('Money sent')

    expect(sent).toHaveLength(2)
    expect(sent[1]?.key).toBe(sent[0]?.key)
    expect(sent[1]?.body).toEqual(sent[0]?.body)
  })

  it('changed details get a new key', async () => {
    const sent = onTransfer(() => problem(422, 'INSUFFICIENT_FUNDS'), created())
    const user = setup()
    await fillAndReview(user)
    await user.click(screen.getByRole('button', { name: 'Send ₹100.00' }))
    await screen.findByText('Transfer not sent')

    await user.click(screen.getByRole('button', { name: 'Back to edit' }))
    // The form keeps what was typed.
    expect(screen.getByLabelText('Amount (₹)')).toHaveValue('100')
    await user.clear(screen.getByLabelText('Amount (₹)'))
    await user.type(screen.getByLabelText('Amount (₹)'), '50')
    await user.click(screen.getByRole('button', { name: 'Review' }))
    await user.click(screen.getByRole('button', { name: 'Send ₹50.00' }))
    await screen.findByText('Money sent')

    expect(sent).toHaveLength(2)
    expect(sent[1]?.key).not.toBe(sent[0]?.key)
  })

  it.each([
    ['INSUFFICIENT_FUNDS', 422, 'Your available balance is too low for this amount.'],
    ['LIMIT_EXCEEDED', 422, 'This would exceed one of your wallet limits.'],
    ['WALLET_FROZEN', 409, 'This wallet is frozen and cannot send or receive money.'],
    ['KYC_REQUIRED', 403, 'Complete your KYC before using this feature.'],
    ['RATE_LIMITED', 429, 'Too many requests. Please wait a moment and try again.'],
  ] as const)('shows the message for %s', async (code, status, message) => {
    onTransfer(() => problem(status, code))
    const user = setup()
    await fillAndReview(user)
    await user.click(screen.getByRole('button', { name: 'Send ₹100.00' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(message)
    expect(alert).toHaveAttribute('data-error-code', code)
    expect(alert).toHaveTextContent('Reference: corr-test-1')
    if (code === 'KYC_REQUIRED') {
      expect(screen.getByRole('link', { name: 'Go to KYC' })).toHaveAttribute('href', '/customer/kyc')
    }
    // Still on the confirm step, ready to try again.
    expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled()
  })

  it('names an unknown recipient instead of a generic not-found', async () => {
    onTransfer(() => problem(404, 'NOT_FOUND'))
    const user = setup()
    await fillAndReview(user)
    await user.click(screen.getByRole('button', { name: 'Send ₹100.00' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('No wallet was found for this recipient.')
  })

  it('shows a held transfer clearly', async () => {
    onTransfer(created('HELD_FOR_REVIEW'))
    const user = setup()
    await fillAndReview(user, { amount: '50000' })
    await user.click(screen.getByRole('button', { name: 'Send ₹50,000.00' }))

    expect(await screen.findByText('On hold for review')).toBeInTheDocument()
    expect(screen.getByTestId('transfer-result')).toHaveAttribute('data-status', 'HELD_FOR_REVIEW')
    expect(screen.getByText(/₹50,000.00 is on hold while our compliance team reviews/)).toBeInTheDocument()
    expect(screen.queryByText('Money sent')).not.toBeInTheDocument()
  })

  it.each([
    [{ mobile: '12345' }, /Enter a 10-digit Indian mobile number/],
    [{ amount: '0' }, 'The amount must be more than ₹0.'],
    [{ amount: '10.123' }, /at most 2 decimals/],
    [{ amount: '-5' }, /Enter an amount in rupees/],
    [{ amount: '1,000' }, /Enter an amount in rupees/],
  ])('refuses %j without a request', async (input, message) => {
    const sent = onTransfer(created())
    const user = setup()
    await fillAndReview(user, input)

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Send ₹/ })).not.toBeInTheDocument()
    expect(sent).toHaveLength(0)
  })

  it('refuses a bad wallet ID and your own wallet', async () => {
    const user = setup()
    await user.selectOptions(screen.getByLabelText('Send to'), 'walletId')
    await user.type(screen.getByLabelText('Recipient wallet ID'), 'not-a-wallet')
    await user.type(screen.getByLabelText('Amount (₹)'), '10')
    await user.click(screen.getByRole('button', { name: 'Review' }))
    expect(await screen.findByText(/Enter a wallet ID like/)).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Recipient wallet ID'))
    await user.type(screen.getByLabelText('Recipient wallet ID'), FULL_WALLET.walletId)
    await user.click(screen.getByRole('button', { name: 'Review' }))
    expect(await screen.findByText('You cannot send money to your own wallet.')).toBeInTheDocument()
  })

  it('shows the available balance as a hint', () => {
    setup()
    expect(screen.getByLabelText('Amount (₹)')).toHaveAccessibleDescription('Available: ₹1,250.50')
  })
})

describe('SendMoneyPage', () => {
  it('shows the form and the wallet ID to share', async () => {
    renderRoutes([{ path: '/send', element: <SendMoneyPage /> }], { path: '/send', auth: fakeAuth(['CUSTOMER']).auth })

    expect(await screen.findByTestId('my-wallet-id')).toHaveTextContent(FULL_WALLET.walletId)
    expect(screen.getByRole('form', { name: 'Send money' })).toBeInTheDocument()
  })

  it('asks a MIN wallet for full KYC', async () => {
    server.use(
      http.get(`${CORE}/wallets/me`, () =>
        HttpResponse.json({ ...FULL_WALLET, tier: 'MIN', limits: { ...FULL_WALLET.limits, p2pAllowed: false } }),
      ),
    )
    renderRoutes([{ path: '/send', element: <SendMoneyPage /> }], { path: '/send', auth: fakeAuth(['CUSTOMER']).auth })

    expect(await screen.findByText('Sending money needs full KYC.')).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Send money' })).not.toBeInTheDocument()
  })

  it('shows no form for a frozen wallet', async () => {
    server.use(http.get(`${CORE}/wallets/me`, () => HttpResponse.json({ ...FULL_WALLET, status: 'FROZEN' })))
    renderRoutes([{ path: '/send', element: <SendMoneyPage /> }], { path: '/send', auth: fakeAuth(['CUSTOMER']).auth })

    expect(await screen.findByText('Wallet frozen')).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Send money' })).not.toBeInTheDocument()
  })
})
