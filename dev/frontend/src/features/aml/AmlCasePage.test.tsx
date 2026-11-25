import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { CaseDetail } from '@/api/aml'
import type { ComplianceWallet } from '@/api/compliance'
import { fakeAuth, renderRoutes } from '@/test/render'
import { AML, CORE, problem, server } from '@/test/server'
import { AmlCasePage } from './AmlCasePage'
import { buildTimeline } from './timeline'

/** fakeAuth's profile.sub: how the AML service knows the signed-in officer. */
const ME = 'user-1'
const CASE_ID = '00000000-0000-7000-8000-00000000ca01'
const WALLET_ID = '00000000-0000-7000-8000-00000000a0a1'

function amlCase(overrides: Partial<CaseDetail> = {}): CaseDetail {
  return {
    id: CASE_ID,
    walletId: WALLET_ID,
    status: 'OPEN',
    assignedTo: null,
    resolution: null,
    createdAt: '2026-11-25T04:00:02Z',
    updatedAt: '2026-11-25T04:20:02Z',
    // Listed out of order on purpose: the timeline sorts them.
    alerts: [
      { id: 'al-2', ruleCode: 'R2', severity: 'HIGH', details: '3 transactions in ₹45,000-₹49,999', createdAt: '2026-11-25T04:20:01Z' },
    ],
    freezeRequests: [
      { id: 'fr-1', alertId: 'al-2', ruleCode: 'R2', requestedAt: '2026-11-25T04:20:01Z', publishedAt: '2026-11-25T04:20:03Z' },
    ],
    facts: [
      { id: 'f3', direction: 'IN', source: 'TOPUP', counterpartyWalletId: null, referenceId: 't3', amount: '46000.00', currency: 'INR', occurredAt: '2026-11-25T04:20:01Z' },
      { id: 'f1', direction: 'IN', source: 'TOPUP', counterpartyWalletId: null, referenceId: 't1', amount: '46000.00', currency: 'INR', occurredAt: '2026-11-25T04:00:00Z' },
      { id: 'f2', direction: 'OUT', source: 'TRANSFER', counterpartyWalletId: '00000000-0000-7000-8000-00000000b0b0', referenceId: 'x2', amount: '1500.50', currency: 'INR', occurredAt: '2026-11-25T04:10:00Z' },
    ],
    ...overrides,
  }
}

const FROZEN_BY_AML: ComplianceWallet = {
  walletId: WALLET_ID,
  userId: '00000000-0000-7000-8000-00000000c0c1',
  tier: 'FULL',
  status: 'FROZEN',
  balance: '138000.00',
  held: '0.00',
  available: '138000.00',
  currentFreeze: {
    freezeId: 'fz-1',
    source: 'AML',
    reason: `AML case ${CASE_ID}, rule R2`,
    frozenBy: null,
    frozenAt: '2026-11-25T04:20:04Z',
  },
}

function setup(initial: CaseDetail) {
  let current = initial
  server.use(
    http.get(`${AML}/aml/cases/${CASE_ID}`, () => HttpResponse.json(current)),
    http.get(`${CORE}/compliance/wallets/${WALLET_ID}`, () => HttpResponse.json(FROZEN_BY_AML)),
  )
  const user = userEvent.setup()
  renderRoutes([{ path: '/compliance/aml/cases/:id', element: <AmlCasePage /> }], {
    path: `/compliance/aml/cases/${CASE_ID}`,
    auth: fakeAuth(['COMPLIANCE_OFFICER'], 'officer1').auth,
  })
  return {
    user,
    set: (next: CaseDetail) => {
      current = next
    },
  }
}

describe('buildTimeline', () => {
  it('orders facts, alerts and freezes by time, fact before alert before freeze at the same instant', () => {
    expect(buildTimeline(amlCase()).map((entry) => entry.title)).toEqual([
      'Top-up ₹46,000.00',
      'Transfer sent ₹1,500.50',
      'Top-up ₹46,000.00',
      'Alert: R2 Structuring',
      'Freeze requested (R2 Structuring)',
      'Freeze request sent to core-app',
    ])
  })
})

describe('AmlCasePage', () => {
  it('shows the alerts, the timeline and the wallet frozen by the AML service', async () => {
    setup(amlCase())

    const timeline = await screen.findByRole('list', { name: 'Case timeline' })
    const items = within(timeline).getAllByRole('listitem')
    expect(items).toHaveLength(6)
    expect(items[1]).toHaveTextContent('to wallet 00000000…')
    expect(items[3]).toHaveTextContent('3 transactions in ₹45,000-₹49,999')

    expect(await screen.findByTestId('linked-wallet-status')).toHaveTextContent('Frozen by the AML service')
    expect(screen.getByTestId('assignee')).toHaveTextContent('Nobody yet')
  })

  it('assigns an open case to me', async () => {
    const bodies: unknown[] = []
    const { user } = setup(amlCase())
    server.use(
      http.post(`${AML}/aml/cases/${CASE_ID}/assign`, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json(amlCase({ status: 'INVESTIGATING', assignedTo: ME }))
      }),
    )

    await user.click(await screen.findByRole('button', { name: 'Assign to me' }))

    expect(await screen.findByText('The case is assigned to you and now under investigation.')).toBeInTheDocument()
    expect(bodies).toEqual([{}])
    expect(screen.getByTestId('assignee')).toHaveTextContent('You')
    expect(screen.getByTestId('case-status')).toHaveTextContent('Investigating')
    expect(screen.getByRole('button', { name: 'Close as false positive…' })).toBeInTheDocument()
  })

  it('closes as a false positive only with notes', async () => {
    const bodies: unknown[] = []
    const { user } = setup(amlCase({ status: 'INVESTIGATING', assignedTo: ME }))
    server.use(
      http.post(`${AML}/aml/cases/${CASE_ID}/close`, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json(
          amlCase({ status: 'CLOSED_FALSE_POSITIVE', assignedTo: ME, resolution: 'Salary and a refund, documented' }),
        )
      }),
    )

    await user.click(await screen.findByRole('button', { name: 'Close as false positive…' }))
    await user.click(screen.getByRole('button', { name: 'Close as false positive' }))
    expect(await screen.findByText('Enter a reason.')).toBeInTheDocument()
    expect(bodies).toHaveLength(0)

    await user.type(screen.getByLabelText('Why is this a false positive?'), 'Salary and a refund, documented')
    await user.click(screen.getByRole('button', { name: 'Close as false positive' }))

    expect(await screen.findByText('Case closed as a false positive.')).toBeInTheDocument()
    expect(bodies).toEqual([{ resolution: 'FALSE_POSITIVE', notes: 'Salary and a refund, documented' }])
    expect(screen.queryByRole('button', { name: /Download STR/ })).not.toBeInTheDocument()
  })

  it('escalates, then offers the STR CSV download', async () => {
    const { user } = setup(amlCase({ status: 'INVESTIGATING', assignedTo: ME }))
    let downloads = 0
    server.use(
      http.post(`${AML}/aml/cases/${CASE_ID}/escalate`, () =>
        HttpResponse.json(amlCase({ status: 'ESCALATED', assignedTo: ME, resolution: 'Structuring under the hold' })),
      ),
      http.get(`${AML}/aml/cases/${CASE_ID}/str-report`, () => {
        downloads += 1
        return new HttpResponse('caseId,walletId\n', {
          headers: { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename="str-ca01.csv"' },
        })
      }),
    )
    const createObjectURL = vi.fn(() => 'blob:str')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    await user.click(await screen.findByRole('button', { name: 'Escalate (STR)…' }))
    await user.type(screen.getByLabelText('Grounds for the STR'), 'Structuring under the hold')
    await user.click(screen.getByRole('button', { name: 'Escalate and file STR' }))

    await user.click(await screen.findByRole('button', { name: 'Download STR (CSV)' }))
    await vi.waitFor(() => {
      expect(click).toHaveBeenCalledOnce()
    })
    expect(downloads).toBe(1)
    expect(createObjectURL).toHaveBeenCalledOnce()
    expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('str-ca01.csv')
    click.mockRestore()
  })

  it('explains when another officer has taken the case meanwhile', async () => {
    const { user } = setup(amlCase({ status: 'INVESTIGATING', assignedTo: ME }))
    server.use(http.post(`${AML}/aml/cases/${CASE_ID}/escalate`, () => problem(409, 'NOT_CASE_ASSIGNEE')))

    await user.click(await screen.findByRole('button', { name: 'Escalate (STR)…' }))
    await user.type(screen.getByLabelText('Grounds for the STR'), 'notes')
    await user.click(screen.getByRole('button', { name: 'Escalate and file STR' }))

    expect(await screen.findByText('Only the officer assigned to this case can do that.')).toBeInTheDocument()
  })

  it("offers to take over a case another officer is investigating, but not to finish it", async () => {
    setup(amlCase({ status: 'INVESTIGATING', assignedTo: '9b2f0c1e-0000-4000-8000-000000000002' }))
    expect(await screen.findByRole('button', { name: 'Take over' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Close as false positive…' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Escalate (STR)…' })).not.toBeInTheDocument()
  })
})
