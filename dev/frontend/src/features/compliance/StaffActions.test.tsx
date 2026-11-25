import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import type { ComplianceWallet, ReviewTransfer } from '@/api/compliance'
import type { ReconciliationReport } from '@/api/operations'
import { fakeAuth, renderRoutes } from '@/test/render'
import { CORE, problem, server } from '@/test/server'
import { ReconciliationPage } from '../admin/ReconciliationPage'
import { HeldTransfersPage } from './HeldTransfersPage'
import { WalletPage } from './WalletPage'

const HELD: ReviewTransfer = {
  id: '00000000-0000-7000-8000-0000000000e1',
  status: 'HELD_FOR_REVIEW',
  senderWalletId: '00000000-0000-7000-8000-00000000a001',
  receiverWalletId: '00000000-0000-7000-8000-00000000a002',
  amount: '75000.00',
  currency: 'INR',
  createdAt: '2026-11-25T05:00:00Z',
  reviewedBy: null,
  reviewedAt: null,
}

describe('HeldTransfersPage', () => {
  it('approves after a confirm step, once even on a double click', async () => {
    let held = [HELD]
    let approvals = 0
    server.use(
      http.get(`${CORE}/compliance/transfers`, ({ request }) => {
        expect(new URL(request.url).searchParams.get('status')).toBe('HELD_FOR_REVIEW')
        return HttpResponse.json({ items: held, page: 0, size: 20, totalElements: held.length, totalPages: 1 })
      }),
      http.post(`${CORE}/compliance/transfers/${HELD.id}/approve`, async () => {
        await delay(100)
        approvals += 1
        held = []
        return HttpResponse.json({ ...HELD, status: 'COMPLETED' })
      }),
    )
    const user = userEvent.setup()
    renderRoutes([{ path: '/t', element: <HeldTransfersPage /> }], {
      path: '/t',
      auth: fakeAuth(['COMPLIANCE_OFFICER']).auth,
    })

    expect(await screen.findByText('₹75,000.00')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: `00000000… wallet ${HELD.senderWalletId}` })).toHaveAttribute(
      'href',
      `/compliance/wallets/${HELD.senderWalletId}`,
    )
    await user.click(screen.getByRole('button', { name: `Approve transfer ${HELD.id}` }))
    expect(screen.getByText('Send ₹75,000.00?')).toBeInTheDocument()
    await user.dblClick(screen.getByRole('button', { name: 'Confirm approve' }))

    expect(await screen.findByText('Approved: ₹75,000.00 sent to the receiver.')).toBeInTheDocument()
    expect(await screen.findByText('No transfers are waiting for review.')).toBeInTheDocument()
    expect(approvals).toBe(1)
  })

  it('shows a refusal and keeps the transfer listed', async () => {
    server.use(
      http.get(`${CORE}/compliance/transfers`, () =>
        HttpResponse.json({ items: [HELD], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
      ),
      http.post(`${CORE}/compliance/transfers/${HELD.id}/reject`, () => problem(409, 'INVALID_STATE_TRANSITION')),
    )
    const user = userEvent.setup()
    renderRoutes([{ path: '/t', element: <HeldTransfersPage /> }], {
      path: '/t',
      auth: fakeAuth(['COMPLIANCE_OFFICER']).auth,
    })
    await user.click(await screen.findByRole('button', { name: `Reject transfer ${HELD.id}` }))
    await user.click(screen.getByRole('button', { name: 'Confirm reject' }))
    expect(await screen.findByText('That action is not possible in the current state. Refresh and try again.')).toBeInTheDocument()
    expect(screen.getByText('₹75,000.00')).toBeInTheDocument()
  })
})

const WALLET_ID = '00000000-0000-7000-8000-00000000a001'
const ACTIVE: ComplianceWallet = {
  walletId: WALLET_ID,
  userId: '00000000-0000-7000-8000-00000000c001',
  tier: 'FULL',
  status: 'ACTIVE',
  balance: '1500.50',
  held: '0.00',
  available: '1500.50',
  currentFreeze: null,
}

function renderWallet(path: string) {
  const user = userEvent.setup()
  const result = renderRoutes(
    [
      { path: '/compliance/wallets', element: <WalletPage /> },
      { path: '/compliance/wallets/:id', element: <WalletPage /> },
    ],
    { path, auth: fakeAuth(['COMPLIANCE_OFFICER']).auth },
  )
  return { user, ...result }
}

describe('WalletPage', () => {
  it('looks a wallet up by ID and refuses anything else', async () => {
    server.use(http.get(`${CORE}/compliance/wallets/${WALLET_ID}`, () => HttpResponse.json(ACTIVE)))
    const { user, router } = renderWallet('/compliance/wallets')

    await user.type(screen.getByLabelText('Wallet ID'), 'not-a-wallet')
    await user.click(screen.getByRole('button', { name: 'Find wallet' }))
    expect(screen.getByText('Enter a wallet ID (a UUID).')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Wallet ID'))
    await user.type(screen.getByLabelText('Wallet ID'), WALLET_ID)
    await user.click(screen.getByRole('button', { name: 'Find wallet' }))
    expect(await screen.findByTestId('wallet-status')).toHaveTextContent('Active')
    expect(router.state.location.pathname).toBe(`/compliance/wallets/${WALLET_ID}`)
  })

  it('freezes with a required reason, then offers unfreeze', async () => {
    const bodies: unknown[] = []
    server.use(
      http.get(`${CORE}/compliance/wallets/${WALLET_ID}`, () => HttpResponse.json(ACTIVE)),
      http.post(`${CORE}/compliance/wallets/${WALLET_ID}/freeze`, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json({
          ...ACTIVE,
          status: 'FROZEN',
          currentFreeze: {
            freezeId: 'fz',
            source: 'COMPLIANCE',
            reason: 'Bank reported fraud',
            frozenBy: '00000000-0000-0000-0000-000000000001',
            frozenAt: '2026-11-25T06:00:00Z',
          },
        } satisfies ComplianceWallet)
      }),
    )
    const { user } = renderWallet(`/compliance/wallets/${WALLET_ID}`)

    await user.click(await screen.findByRole('button', { name: 'Freeze wallet' }))
    expect(await screen.findByText('Enter a reason.')).toBeInTheDocument()
    expect(bodies).toHaveLength(0)

    await user.type(screen.getByLabelText('Reason for freezing'), 'Bank reported fraud')
    await user.click(screen.getByRole('button', { name: 'Freeze wallet' }))

    expect(await screen.findByText('Wallet frozen.')).toBeInTheDocument()
    expect(bodies).toEqual([{ reason: 'Bank reported fraud' }])
    expect(screen.getByTestId('wallet-status')).toHaveTextContent('Frozen')
    expect(screen.getByTestId('freeze-source')).toHaveTextContent('Compliance officer')
    expect(screen.getByRole('button', { name: 'Unfreeze wallet' })).toBeInTheDocument()
  })

  it('shows a freeze the AML service asked for', async () => {
    server.use(
      http.get(`${CORE}/compliance/wallets/${WALLET_ID}`, () =>
        HttpResponse.json({
          ...ACTIVE,
          status: 'FROZEN',
          currentFreeze: { freezeId: 'fz', source: 'AML', reason: 'AML case x, rule R2', frozenBy: null, frozenAt: '2026-11-25T06:00:00Z' },
        } satisfies ComplianceWallet),
      ),
    )
    renderWallet(`/compliance/wallets/${WALLET_ID}`)
    expect(await screen.findByTestId('freeze-source')).toHaveTextContent('AML service (automatic)')
    expect(screen.getByLabelText('Reason for unfreezing')).toBeInTheDocument()
  })

  it('says so when the wallet does not exist', async () => {
    server.use(http.get(`${CORE}/compliance/wallets/${WALLET_ID}`, () => problem(404, 'NOT_FOUND')))
    renderWallet(`/compliance/wallets/${WALLET_ID}`)
    expect(await screen.findByText("Couldn't load the wallet")).toBeInTheDocument()
  })
})

describe('ReconciliationPage', () => {
  const base: ReconciliationReport = {
    walletsChecked: 12,
    mismatches: 0,
    unlinked: 0,
    listed: [],
    truncated: false,
    startedAt: '2026-11-25T06:00:00.000Z',
    finishedAt: '2026-11-25T06:00:01.250Z',
  }

  function renderPage(report: ReconciliationReport) {
    let runs = 0
    server.use(
      http.post(`${CORE}/admin/reconciliation/run`, async () => {
        await delay(100)
        runs += 1
        return HttpResponse.json(report)
      }),
    )
    const user = userEvent.setup()
    renderRoutes([{ path: '/r', element: <ReconciliationPage /> }], { path: '/r', auth: fakeAuth(['ADMIN']).auth })
    return { user, runs: () => runs }
  }

  it('runs once and reports that all balances match', async () => {
    const { user, runs } = renderPage(base)
    await user.dblClick(screen.getByRole('button', { name: 'Run reconciliation' }))
    expect(await screen.findByText('All balances match')).toBeInTheDocument()
    expect(screen.getByText(/12 wallets checked/)).toBeInTheDocument()
    expect(screen.getByText(/took 1.3 s/)).toBeInTheDocument()
    expect(runs()).toBe(1)
  })

  it('lists mismatches, including negative ledger balances and unlinked wallets', async () => {
    const { user } = renderPage({
      ...base,
      mismatches: 1,
      unlinked: 1,
      truncated: true,
      listed: [
        { walletId: '00000000-0000-7000-8000-00000000a001', reason: 'BALANCE', currency: 'INR', cachedBalance: '100.00', ledgerBalance: '-12.50' },
        { walletId: '00000000-0000-7000-8000-00000000a002', reason: 'UNLINKED', currency: 'INR', cachedBalance: '5.00', ledgerBalance: null },
      ],
    })
    await user.click(screen.getByRole('button', { name: 'Run reconciliation' }))
    expect(await screen.findByText('Differences found')).toBeInTheDocument()
    expect(screen.getByText('−₹12.50')).toBeInTheDocument()
    expect(screen.getByText('No ledger account')).toBeInTheDocument()
    expect(screen.getByText(/Only the first 2 are listed/)).toBeInTheDocument()
  })
})
