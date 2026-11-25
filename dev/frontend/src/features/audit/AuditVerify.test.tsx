import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { ChainVerification } from '@/api/operations'
import { fakeAuth, renderRoutes } from '@/test/render'
import { CORE, problem, server } from '@/test/server'
import { AuditVerify } from './AuditVerify'

function setup(answer: () => Response) {
  let calls = 0
  server.use(
    http.get(`${CORE}/audit/verify`, () => {
      calls += 1
      return answer()
    }),
  )
  const user = userEvent.setup()
  renderRoutes([{ path: '/audit/events', element: <AuditVerify /> }], {
    path: '/audit/events',
    auth: fakeAuth(['AUDITOR'], 'auditor1').auth,
  })
  return { user, calls: () => calls }
}

const OK: ChainVerification = {
  status: 'OK',
  checkedEvents: 128,
  lastSeq: 128,
  lastHash: 'f'.repeat(64),
  brokenSeq: null,
  reason: null,
}

describe('AuditVerify', () => {
  it('verifies only when asked, then shows OK with the events checked', async () => {
    const { user, calls } = setup(() => HttpResponse.json(OK))
    expect(calls()).toBe(0)

    await user.click(screen.getByRole('button', { name: 'Verify chain' }))

    const ok = await screen.findByTestId('chain-ok')
    expect(ok).toHaveTextContent('Chain OK')
    expect(ok).toHaveTextContent('128 events checked, up to sequence #128')
    expect(calls()).toBe(1)
  })

  it('shows the first broken sequence number and why', async () => {
    const { user } = setup(() =>
      HttpResponse.json({
        status: 'BROKEN',
        checkedEvents: 42,
        lastSeq: 41,
        lastHash: 'a'.repeat(64),
        brokenSeq: 42,
        reason: 'HASH_MISMATCH',
      } satisfies ChainVerification),
    )

    await user.click(screen.getByRole('button', { name: 'Verify chain' }))

    const broken = await screen.findByTestId('chain-broken')
    expect(broken).toHaveTextContent('Chain broken at sequence #42')
    expect(broken).toHaveTextContent("its stored hash doesn't match its contents")
    expect(screen.queryByTestId('chain-ok')).not.toBeInTheDocument()
  })

  it('shows an error when the check itself fails, and can run again', async () => {
    let fail = true
    const { user, calls } = setup(() => (fail ? problem(503, 'INTERNAL_ERROR') : HttpResponse.json(OK)))

    await user.click(screen.getByRole('button', { name: 'Verify chain' }))
    expect(await screen.findByText("Couldn't verify the chain")).toBeInTheDocument()

    fail = false
    await user.click(screen.getByRole('button', { name: 'Verify chain' }))
    expect(await screen.findByTestId('chain-ok')).toBeInTheDocument()
    expect(calls()).toBe(2)
  })
})
