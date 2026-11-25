import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { ConsentOverview, DataRequest, DataRequests } from '@/api/types'
import { fakeAuth, renderRoutes } from '@/test/render'
import { CORE, problem, server } from '@/test/server'
import { PrivacyPage } from './PrivacyPage'

const CONSENTS: ConsentOverview = {
  requiredGiven: true,
  consents: [
    { purpose: 'TERMS', granted: true, policyVersion: '2026-11-01', capturedAt: '2026-11-01T05:00:00Z' },
    { purpose: 'PRIVACY', granted: true, policyVersion: '2026-11-01', capturedAt: '2026-11-01T05:00:00Z' },
    { purpose: 'MARKETING', granted: false, policyVersion: '2026-10-01', capturedAt: '2026-11-01T05:00:00Z' },
  ],
  policyVersions: { TERMS: '2026-11-01', PRIVACY: '2026-11-01', MARKETING: '2026-11-15' },
}

function request(overrides: Partial<DataRequest>): DataRequest {
  return {
    id: '00000000-0000-7000-8000-0000000000e1',
    type: 'EXPORT',
    status: 'PENDING',
    requestedAt: '2026-11-25T06:00:00Z',
    completedAt: null,
    download: null,
    ...overrides,
  }
}

const READY = request({
  status: 'COMPLETED',
  completedAt: '2026-11-25T06:00:05Z',
  download: { url: 'https://s3.test/exports/e1.json?X-Amz-Signature=abc', expiresAt: '2026-11-25T06:15:05Z' },
})

function setup(requests: DataRequests = { items: [] }) {
  let current = requests
  server.use(
    http.get(`${CORE}/me/consents`, () => HttpResponse.json(CONSENTS)),
    http.get(`${CORE}/me/data-requests`, () => HttpResponse.json(current)),
  )
  const user = userEvent.setup()
  const { auth, signoutRedirect } = fakeAuth(['CUSTOMER'])
  renderRoutes([{ path: '/privacy', element: <PrivacyPage pollMs={10} /> }], { path: '/privacy', auth })
  return {
    user,
    signoutRedirect,
    setRequests: (next: DataRequests) => {
      current = next
    },
  }
}

describe('PrivacyPage', () => {
  it('shows the required consents and the marketing switch', async () => {
    setup()
    const marketing = await screen.findByRole('switch', { name: /Marketing messages/ })
    expect(marketing).not.toBeChecked()
    expect(screen.getByText('Terms of use')).toBeInTheDocument()
    expect(screen.getAllByText('Accepted')).toHaveLength(2)
  })

  it('records marketing consent with the current policy version', async () => {
    const posts: unknown[] = []
    const { user } = setup()
    server.use(
      http.post(`${CORE}/me/consents`, async ({ request: req }) => {
        posts.push(await req.json())
        return HttpResponse.json(
          {
            ...CONSENTS,
            consents: CONSENTS.consents.map((c) =>
              c.purpose === 'MARKETING' ? { ...c, granted: true, policyVersion: '2026-11-15' } : c,
            ),
          },
          { status: 201 },
        )
      }),
    )
    await user.click(await screen.findByRole('switch', { name: /Marketing messages/ }))

    await waitFor(() => {
      expect(screen.getByRole('switch', { name: /Marketing messages/ })).toBeChecked()
    })
    expect(posts).toEqual([{ consents: [{ purpose: 'MARKETING', policyVersion: '2026-11-15', granted: true }] }])
  })

  it('shows a consent that was not saved', async () => {
    const { user } = setup()
    server.use(http.post(`${CORE}/me/consents`, () => problem(429, 'RATE_LIMITED')))
    await user.click(await screen.findByRole('switch', { name: /Marketing messages/ }))

    expect(await screen.findByText('Your choice was not saved')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: /Marketing messages/ })).not.toBeChecked()
  })

  it('requests an export, polls while it is prepared, then offers the download link', async () => {
    let posts = 0
    const { user, setRequests } = setup()
    server.use(
      http.post(`${CORE}/me/data-export`, () => {
        posts += 1
        setRequests({ items: [request({})] })
        return HttpResponse.json(request({}), { status: 202 })
      }),
    )
    await user.click(await screen.findByRole('button', { name: 'Request data export' }))

    expect(await screen.findByRole('button', { name: 'Preparing your export…' })).toBeDisabled()
    expect(screen.getByText('Preparing')).toBeInTheDocument()

    setRequests({ items: [READY] })
    const link = await screen.findByRole('link', { name: 'Download your data (JSON)' })
    expect(link).toHaveAttribute('href', READY.download?.url)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByText(/Link valid until/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Request data export' })).toBeEnabled()
    expect(posts).toBe(1)
  })

  it('shows REQUEST_IN_PROGRESS', async () => {
    const { user } = setup()
    server.use(http.post(`${CORE}/me/data-export`, () => problem(409, 'REQUEST_IN_PROGRESS')))
    await user.click(await screen.findByRole('button', { name: 'Request data export' }))

    expect(await screen.findByText('This request is already being processed. Please wait a moment.')).toBeInTheDocument()
  })

  it('never renders a download link that is not http(s)', async () => {
    setup({ items: [request({ ...READY, download: { url: 'javascript:alert(1)', expiresAt: READY.completedAt ?? '' } })] })
    expect(await screen.findByText('Ready')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Download your data (JSON)' })).not.toBeInTheDocument()
  })

  it('keeps the erase button disabled until ERASE is typed exactly', async () => {
    let posts = 0
    const { user } = setup()
    server.use(
      http.post(`${CORE}/me/erasure-request`, () => {
        posts += 1
        return HttpResponse.json(request({ type: 'ERASURE', status: 'COMPLETED' }))
      }),
    )
    const form = within(await screen.findByRole('form', { name: 'Erase my personal data' }))
    const input = form.getByLabelText('Type ERASE to confirm')
    const button = form.getByRole('button', { name: 'Erase my data' })
    expect(button).toBeDisabled()

    await user.type(input, 'erase')
    expect(button).toBeDisabled()
    await user.clear(input)
    await user.type(input, 'ERASE ')
    expect(button).toBeDisabled()
    await user.type(input, '{Enter}')
    expect(posts).toBe(0) // Enter doesn't bypass the phrase either

    await user.clear(input)
    await user.type(input, 'ERASE')
    expect(button).toBeEnabled()
    await user.click(button)

    expect(await screen.findByText('Your personal data has been erased')).toBeInTheDocument()
    expect(posts).toBe(1)
  })

  it.each([
    ['WALLET_NOT_EMPTY', 'The wallet still holds money, so this request cannot go ahead.'],
    ['AML_CASE_OPEN', 'This request is on hold while a compliance review is open.'],
    ['WALLET_FROZEN', 'This wallet is frozen and cannot send or receive money.'],
  ])('shows the erasure refusal %s', async (code, message) => {
    const { user } = setup()
    server.use(http.post(`${CORE}/me/erasure-request`, () => problem(409, code)))
    await user.type(await screen.findByLabelText('Type ERASE to confirm'), 'ERASE')
    await user.click(screen.getByRole('button', { name: 'Erase my data' }))

    expect(await screen.findByText('Your data was not erased')).toBeInTheDocument()
    expect(screen.getByText(message)).toBeInTheDocument()
  })

  it('after erasure shows only the erased state, with sign out', async () => {
    const { user, signoutRedirect } = setup({ items: [request({ type: 'ERASURE', status: 'COMPLETED' })] })
    expect(await screen.findByTestId('erased')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Request data export' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(signoutRedirect).toHaveBeenCalled()
  })
})
