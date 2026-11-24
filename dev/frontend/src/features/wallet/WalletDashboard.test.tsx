import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw/http'
import type { MyWallet, Transaction, TransactionHistory } from '@/api/types'
import { fakeAuth, renderRoutes } from '@/test/render'
import { CORE, FULL_WALLET, problem, server } from '@/test/server'
import { WalletDashboard } from './WalletDashboard'

const TODAY = '2026-11-24'

const TOPUP: Transaction = {
  id: 't1',
  type: 'TOPUP',
  status: 'CAPTURED',
  amount: '3000.10',
  currency: 'INR',
  counterpartyWalletId: null,
  note: null,
  createdAt: '2026-11-01T05:00:00Z',
}

const RECENT: Transaction[] = [
  {
    id: 't3',
    type: 'TRANSFER_OUT',
    status: 'COMPLETED',
    amount: '1500.50',
    currency: 'INR',
    counterpartyWalletId: '00000000-0000-7000-8000-00000000b7c9',
    note: 'rent',
    createdAt: '2026-11-24T05:00:00Z',
  },
  {
    id: 't2',
    type: 'TRANSFER_IN',
    status: 'COMPLETED',
    amount: '0.20',
    currency: 'INR',
    counterpartyWalletId: '00000000-0000-7000-8000-00000000c111',
    note: null,
    createdAt: '2026-11-20T05:00:00Z',
  },
  TOPUP,
]

function page(items: Transaction[]): TransactionHistory {
  return { items, page: 0, size: items.length, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 }
}

/** Serves the history and records every query string the dashboard asked for. */
function serveHistory(items: Transaction[]) {
  const queries: URLSearchParams[] = []
  server.use(
    http.get(`${CORE}/wallets/me/transactions`, ({ request }) => {
      const search = new URL(request.url).searchParams
      queries.push(search)
      return HttpResponse.json(page(items))
    }),
  )
  return queries
}

function render(path: Parameters<typeof renderRoutes>[1]['path'] = '/customer') {
  return renderRoutes([{ path: '/customer', element: <WalletDashboard today={TODAY} /> }], {
    path,
    auth: fakeAuth(['CUSTOMER']).auth,
  })
}

function bar(name: string) {
  return screen.getByRole('progressbar', { name })
}

describe('WalletDashboard', () => {
  it('shows balance, held and available exactly as the API sent them', async () => {
    serveHistory(RECENT)
    render()

    expect(await screen.findByTestId('amount-balance')).toHaveTextContent('₹1,500.50')
    expect(screen.getByTestId('amount-held')).toHaveTextContent('₹250.00')
    expect(screen.getByTestId('amount-available')).toHaveTextContent('₹1,250.50')
    expect(screen.getByTestId('wallet-tier')).toHaveTextContent('Full KYC')
  })

  it('shows each limit used as a progress bar, from /wallets/me/limit-usage', async () => {
    serveHistory(RECENT)
    render()

    // ₹25,000 of ₹1,00,000 sent today; ₹4,75,000 of ₹5,00,000 this month (95%, amber); ₹1,000 of ₹2,00,000 added.
    expect(await screen.findByRole('progressbar', { name: 'Sent today' })).toHaveAttribute('aria-valuenow', '25')
    expect(bar('Sent today')).toHaveAttribute('aria-valuetext', '₹25,000.00 of ₹1,00,000.00, 25%')
    expect(bar('Sent this month')).toHaveAttribute('aria-valuenow', '95')
    expect(bar('Added this month')).toHaveAttribute('aria-valuenow', '0')
    expect(bar('Wallet balance')).toHaveAttribute('aria-valuetext', '₹1,500.50 of ₹2,00,000.00, 0%')
    expect(screen.getByText(/Transfers of ₹50,000.00 or more are held/)).toBeInTheDocument()
  })

  it('lists recent activity with signed amounts', async () => {
    serveHistory(RECENT)
    render()

    const table = await screen.findByRole('table', { name: 'Recent transactions' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('Sent')
    expect(rows[0]).toHaveTextContent('-₹1,500.50')
    expect(rows[0]).toHaveTextContent('To wallet …b7c9')
    expect(rows[0]).toHaveTextContent('rent')
    expect(rows[1]).toHaveTextContent('₹0.20')
    expect(rows[2]).toHaveTextContent('Money added')
    expect(rows[2]).toHaveTextContent('₹3,000.10')
  })

  it('asks for the 5 newest for recent activity and the last 30 Indian days for the chart', async () => {
    const queries = serveHistory(RECENT)
    render()

    await screen.findByTestId('activity-summary')
    const recent = queries.find((q) => q.get('size') === '5')
    const chart = queries.find((q) => q.get('size') === '100')
    expect(recent?.get('page')).toBe('0')
    expect(chart?.get('from')).toBe('2026-10-26')
    expect(chart?.get('to')).toBe(TODAY)
    expect(chart?.get('page')).toBe('0')
  })

  it('summarises the 30-day chart from the history, adding in paise', async () => {
    serveHistory(RECENT)
    render()

    // In: ₹3,000.10 + ₹0.20 = ₹3,000.30 exactly; out: ₹1,500.50.
    expect(await screen.findByTestId('activity-summary')).toHaveTextContent(
      'Last 30 days: ₹3,000.30 in, ₹1,500.50 out (completed only).',
    )
  })

  it('reads further history pages for the chart', async () => {
    let calls = 0
    server.use(
      http.get(`${CORE}/wallets/me/transactions`, ({ request }) => {
        const search = new URL(request.url).searchParams
        if (search.get('size') !== '100') return HttpResponse.json(page([]))
        calls++
        const pageNo = Number(search.get('page'))
        return HttpResponse.json({ items: [{ ...TOPUP, id: `p${pageNo}` }], page: pageNo, size: 100, totalElements: 2, totalPages: 2 })
      }),
    )
    render()

    expect(await screen.findByTestId('activity-summary')).toHaveTextContent('₹6,000.20 in')
    expect(calls).toBe(2)
  })

  it('shows an empty state when nothing happened yet', async () => {
    serveHistory([])
    render()

    expect(await screen.findByText('No transactions yet.')).toBeInTheDocument()
    expect(await screen.findByText('No money moved in the last 30 days.')).toBeInTheDocument()
  })

  it('tells a MIN customer that sending needs full KYC, without transfer bars', async () => {
    serveHistory([])
    const min: MyWallet = {
      ...FULL_WALLET,
      tier: 'MIN',
      limits: {
        maxBalance: '10000.00',
        monthlyLoad: '10000.00',
        p2pAllowed: false,
        dailyTransfer: null,
        highValueThreshold: null,
        monthlyTransfer: null,
      },
    }
    server.use(http.get(`${CORE}/wallets/me`, () => HttpResponse.json(min)))
    render()

    expect(await screen.findByText('Sending money needs full KYC.')).toBeInTheDocument()
    expect(screen.getByTestId('wallet-tier')).toHaveTextContent('Minimum KYC')
    expect(await screen.findByRole('progressbar', { name: 'Added this month' })).toHaveAttribute('aria-valuenow', '10')
    expect(screen.queryByRole('progressbar', { name: 'Sent today' })).not.toBeInTheDocument()
  })

  it('offers KYC when there is no wallet yet', async () => {
    server.use(http.get(`${CORE}/wallets/me`, () => problem(403, 'KYC_REQUIRED')))
    render()

    expect(await screen.findByText('No wallet yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Complete KYC' })).toHaveAttribute('href', '/customer/kyc')
  })

  it('waits for the wallet right after minimum KYC (it is created asynchronously)', async () => {
    serveHistory([])
    let calls = 0
    server.use(
      http.get(`${CORE}/wallets/me`, () => {
        calls++
        return calls === 1 ? problem(403, 'KYC_REQUIRED') : HttpResponse.json(FULL_WALLET)
      }),
    )
    render({ pathname: '/customer', state: { justOnboarded: true } })

    expect(await screen.findByText('Setting up your wallet…')).toBeInTheDocument()
    expect(await screen.findByTestId('amount-balance', {}, { timeout: 3000 })).toHaveTextContent('₹1,500.50')
  })

  it('shows a frozen wallet clearly', async () => {
    serveHistory([])
    server.use(http.get(`${CORE}/wallets/me`, () => HttpResponse.json({ ...FULL_WALLET, status: 'FROZEN' })))
    render()

    expect(await screen.findByText('Wallet frozen')).toBeInTheDocument()
  })

  it('shows the error with its reference when the wallet fails to load', async () => {
    server.use(http.get(`${CORE}/wallets/me`, () => problem(503, 'INTERNAL_ERROR')))
    render()

    expect(await screen.findByText("Couldn't load your wallet")).toBeInTheDocument()
    expect(screen.getByText('Reference: corr-test-1')).toBeInTheDocument()
  })
})
