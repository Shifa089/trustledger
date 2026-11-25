import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { Topup, TopupStatus } from '@/api/types'
import { fakeAuth, renderRoutes } from '@/test/render'
import { CORE, FULL_WALLET, problem, server, USAGE } from '@/test/server'
import { AddMoneyPage } from './AddMoneyPage'
import type { CheckoutRequest, OpenCheckout } from './razorpay'
import { roomLeft } from './room'
import { TopupFlow } from './TopupFlow'

const TOPUP_ID = '00000000-0000-7000-8000-0000000000d1'

function topup(status: TopupStatus, amount = '500.00'): Topup {
  return {
    id: TOPUP_ID,
    status,
    amount,
    currency: 'INR',
    gatewayOrderId: 'order_TEST123',
    gatewayPaymentId: status === 'PENDING' ? null : 'pay_TEST123',
    keyId: 'rzp_test_public',
    createdAt: '2026-11-25T06:00:00Z',
    updatedAt: '2026-11-25T06:00:00Z',
  }
}

/** A stand-in for Razorpay Checkout: remembers what it was opened with, the test plays the customer. */
function fakeCheckout() {
  const opened: CheckoutRequest[] = []
  const open: OpenCheckout = (request) => {
    opened.push(request)
    return Promise.resolve()
  }
  const last = () => {
    const request = opened.at(-1)
    if (request === undefined) throw new Error('Checkout was not opened')
    return request
  }
  return { open, opened, last }
}

interface Created {
  amount: string
  key: string | null
}

function onCreate(...responders: ((amount: string) => Response)[]) {
  const created: Created[] = []
  server.use(
    http.post(`${CORE}/topups`, async ({ request }) => {
      const { amount } = (await request.json()) as { amount: string }
      created.push({ amount, key: request.headers.get('Idempotency-Key') })
      const respond = responders[Math.min(created.length, responders.length) - 1]
      if (respond === undefined) throw new Error('no responder')
      return respond(amount)
    }),
  )
  return created
}

const pending = (amount: string) => HttpResponse.json(topup('PENDING', amount), { status: 201 })

/** GET /topups/{id} answers with these statuses in turn (the last one repeats). */
function onPoll(...statuses: TopupStatus[]) {
  let polls = 0
  server.use(
    http.get(`${CORE}/topups/:id`, () => {
      polls += 1
      return HttpResponse.json(topup(statuses[Math.min(polls, statuses.length) - 1] ?? 'PENDING'))
    }),
  )
  return () => polls
}

function setup(checkout = fakeCheckout(), maxPolls = 4) {
  const user = userEvent.setup()
  renderRoutes([{ path: '/add', element: <TopupFlow openCheckout={checkout.open} pollIntervalMs={5} maxPolls={maxPolls} /> }], {
    path: '/add',
    auth: fakeAuth(['CUSTOMER']).auth,
  })
  return { user, checkout }
}

async function submit(user: ReturnType<typeof userEvent.setup>, amount = '500') {
  await user.type(screen.getByLabelText('Amount (₹)'), amount)
  await user.click(screen.getByRole('button', { name: 'Add money' }))
}

function pay(checkout: ReturnType<typeof fakeCheckout>) {
  act(() => {
    checkout.last().onPaid('pay_TEST123')
  })
}

describe('TopupFlow', () => {
  it('creates the top-up with a key and opens Checkout with its order and public key', async () => {
    const created = onCreate(pending)
    const { user, checkout } = setup()
    await submit(user, '500.5')

    await screen.findByText('Complete the payment in Razorpay Checkout')
    expect(created).toEqual([{ amount: '500.50', key: expect.stringMatching(/^[0-9a-f-]{36}$/) as unknown }])
    expect(checkout.last()).toMatchObject({ keyId: 'rzp_test_public', orderId: 'order_TEST123', currency: 'INR' })
  })

  it('polling stops on CAPTURED', async () => {
    onCreate(pending)
    const polls = onPoll('PENDING', 'PENDING', 'CAPTURED')
    const { user, checkout } = setup(fakeCheckout(), 10)
    await submit(user)
    await screen.findByText('Complete the payment in Razorpay Checkout')

    pay(checkout)
    expect(screen.getByText('Confirming your payment…')).toBeInTheDocument()
    expect(await screen.findByText('Money added')).toBeInTheDocument()
    expect(screen.getByText('₹500.00 was added to your wallet.')).toBeInTheDocument()
    expect(polls()).toBe(3)

    // No further polls after the final status.
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(polls()).toBe(3)
  })

  it('polling stops on timeout with "we\'ll confirm it shortly"', async () => {
    onCreate(pending)
    const polls = onPoll('PENDING')
    const { user, checkout } = setup(fakeCheckout(), 4)
    await submit(user)
    await screen.findByText('Complete the payment in Razorpay Checkout')

    pay(checkout)
    expect(await screen.findByText("We'll confirm it shortly")).toBeInTheDocument()
    expect(screen.getByText(/You don't need to pay again/)).toBeInTheDocument()
    expect(polls()).toBe(4)
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(polls()).toBe(4)

    // "Check again" polls once more and can still finish.
    onPoll('CAPTURED')
    await user.click(screen.getByRole('button', { name: 'Check again' }))
    expect(await screen.findByText('Money added')).toBeInTheDocument()
  })

  it('keeps polling through a failed read', async () => {
    onCreate(pending)
    let polls = 0
    server.use(
      http.get(`${CORE}/topups/:id`, () => {
        polls += 1
        return polls === 1 ? HttpResponse.error() : HttpResponse.json(topup('CAPTURED'))
      }),
    )
    const { user, checkout } = setup()
    await submit(user)
    await screen.findByText('Complete the payment in Razorpay Checkout')
    pay(checkout)

    expect(await screen.findByText('Money added')).toBeInTheDocument()
    expect(polls).toBe(2)
  })

  it.each([
    ['FAILED', 'Payment failed'],
    ['EXPIRED', 'Top-up expired'],
    ['PARKED', 'Received, waiting for review'],
  ] as const)('stops on %s', async (status, title) => {
    onCreate(pending)
    onPoll(status)
    const { user, checkout } = setup()
    await submit(user)
    await screen.findByText('Complete the payment in Razorpay Checkout')
    pay(checkout)

    expect(await screen.findByText(title)).toBeInTheDocument()
  })

  it('says no payment was made when Checkout is closed, and does not poll', async () => {
    onCreate(pending)
    const polls = onPoll('PENDING')
    const { user, checkout } = setup()
    await submit(user)
    await screen.findByText('Complete the payment in Razorpay Checkout')

    act(() => {
      checkout.last().onAttemptFailed('Card declined')
      checkout.last().onDismiss()
    })
    expect(screen.getByText('No payment was made')).toBeInTheDocument()
    expect(screen.getByText('Last attempt failed: Card declined')).toBeInTheDocument()
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(polls()).toBe(0)
  })

  it('a retry after a network error reuses the key; a new amount gets a new one', async () => {
    const created = onCreate(() => HttpResponse.error(), () => HttpResponse.error(), pending)
    const { user } = setup()
    await submit(user, '500')
    expect(await screen.findByText("Couldn't start the top-up")).toBeInTheDocument()
    expect(screen.getByText(/Trying again with the same amount is safe/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Add money' }))
    await waitFor(() => {
      expect(created).toHaveLength(2)
    })
    expect(created[1]?.key).toBe(created[0]?.key)

    await user.clear(screen.getByLabelText('Amount (₹)'))
    await user.type(screen.getByLabelText('Amount (₹)'), '700')
    await user.click(screen.getByRole('button', { name: 'Add money' }))
    await screen.findByText('Complete the payment in Razorpay Checkout')
    expect(created[2]).toEqual({ amount: '700.00', key: expect.any(String) as unknown })
    expect(created[2]?.key).not.toBe(created[0]?.key)
  })

  it('a double click creates one top-up', async () => {
    const created = onCreate(pending)
    const { user } = setup()
    await user.type(screen.getByLabelText('Amount (₹)'), '500')
    await user.dblClick(screen.getByRole('button', { name: 'Add money' }))

    await screen.findByText('Complete the payment in Razorpay Checkout')
    expect(created).toHaveLength(1)
  })

  it('shows a limit refusal', async () => {
    onCreate(() => problem(422, 'LIMIT_EXCEEDED'))
    const { user } = setup()
    await submit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('This would exceed one of your wallet limits.')
  })

  it('reports a Checkout script that does not load', async () => {
    onCreate(pending)
    const { user } = setup({ open: () => Promise.reject(new Error('blocked')), opened: [], last: () => { throw new Error() } })
    await submit(user)

    expect(await screen.findByText("Checkout didn't open")).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open Checkout again' })).toBeInTheDocument()
  })

  it('refuses an invalid amount without a request', async () => {
    const created = onCreate(pending)
    const { user } = setup()
    await submit(user, '0')

    expect(await screen.findByText('The amount must be more than ₹0.')).toBeInTheDocument()
    expect(created).toHaveLength(0)
  })
})

describe('AddMoneyPage', () => {
  it('hints how much can still be added', async () => {
    renderRoutes([{ path: '/add', element: <AddMoneyPage flow={{ openCheckout: fakeCheckout().open }} /> }], {
      path: '/add',
      auth: fakeAuth(['CUSTOMER']).auth,
    })
    // Under the cap: 2,00,000 - 1,500.50 = 1,98,499.50; load left: 2,00,000 - 1,000 = 1,99,000.
    expect(await screen.findByText('You can add up to ₹1,98,499.50 now.')).toBeInTheDocument()
  })

  it('roomLeft is the smaller limit and never negative', () => {
    expect(roomLeft(FULL_WALLET, { ...USAGE, addedThisMonth: '199900.00' })).toBe('100.00')
    expect(roomLeft({ ...FULL_WALLET, balance: '250000.00' }, USAGE)).toBe('0.00')
    expect(roomLeft(FULL_WALLET, undefined)).toBe('198499.50')
  })
})
