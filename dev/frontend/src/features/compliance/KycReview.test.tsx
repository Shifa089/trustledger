import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { KycReviewCase } from '@/api/compliance'
import { fakeAuth, renderRoutes } from '@/test/render'
import { CORE, problem, server } from '@/test/server'
import { KycQueuePage } from './KycQueuePage'
import { KycReviewPage } from './KycReviewPage'

/** The officer signed in for these tests: GET /me in test/server.ts answers this core user ID. */
const ME = '00000000-0000-0000-0000-000000000001'
const OTHER_OFFICER = '00000000-0000-7000-8000-0000000000b2'
const CASE_ID = '00000000-0000-7000-8000-0000000000c1'

function reviewCase(overrides: Partial<KycReviewCase> = {}): KycReviewCase {
  return {
    id: CASE_ID,
    userId: '00000000-0000-7000-8000-0000000000a1',
    level: 'FULL',
    status: 'SUBMITTED',
    address: '12 Fake Street, Testpur 560001',
    documents: [
      {
        id: '00000000-0000-7000-8000-0000000000d1',
        type: 'PAN_CARD',
        contentType: 'image/png',
        sizeBytes: 20480,
        sha256: 'ab'.repeat(32),
        uploadedAt: '2026-11-24T05:00:00Z',
      },
      {
        id: '00000000-0000-7000-8000-0000000000d2',
        type: 'ADDRESS_PROOF',
        contentType: 'application/pdf',
        sizeBytes: 40960,
        sha256: 'cd'.repeat(32),
        uploadedAt: '2026-11-24T05:01:00Z',
      },
    ],
    verifiedBy: null,
    verifiedAt: null,
    approvedBy: null,
    approvedAt: null,
    rejectedBy: null,
    rejectedAt: null,
    rejectionReason: null,
    createdAt: '2026-11-24T05:02:00Z',
    updatedAt: '2026-11-24T05:02:00Z',
    ...overrides,
  }
}

const VERIFIED_BY_ME = reviewCase({ status: 'VERIFIED', verifiedBy: ME, verifiedAt: '2026-11-24T06:00:00Z' })

function setup(initial: KycReviewCase) {
  let current = initial
  server.use(http.get(`${CORE}/compliance/kyc-cases/${CASE_ID}`, () => HttpResponse.json(current)))
  const user = userEvent.setup()
  renderRoutes([{ path: '/compliance/kyc/:id', element: <KycReviewPage /> }], {
    path: `/compliance/kyc/${CASE_ID}`,
    auth: fakeAuth(['COMPLIANCE_OFFICER'], 'officer1').auth,
  })
  return {
    user,
    set: (next: KycReviewCase) => {
      current = next
    },
  }
}

describe('KycReviewPage', () => {
  it('shows the maker-checker error when the officer who verified the case tries to approve it', async () => {
    let approvals = 0
    const { user } = setup(VERIFIED_BY_ME)
    server.use(
      http.post(`${CORE}/compliance/kyc-cases/${CASE_ID}/approve`, () => {
        approvals += 1
        return problem(409, 'MAKER_CHECKER_VIOLATION', { detail: 'The checker must differ from the maker.' })
      }),
    )

    // The page warns before the click...
    expect(await screen.findByText('You verified this case')).toBeInTheDocument()
    expect(screen.getByText('you')).toBeInTheDocument()

    // ...but leaves the decision to the server, which refuses it.
    await user.click(screen.getByRole('button', { name: 'Approve' }))
    const alert = await screen.findByTestId('maker-checker-error')
    expect(within(alert).getByText('Maker-checker: approval refused')).toBeInTheDocument()
    expect(alert).toHaveTextContent('A different officer must approve a case you verified')
    expect(alert).toHaveTextContent('Reference: corr-test-1')
    expect(approvals).toBe(1)
    expect(screen.getByTestId('case-status')).toHaveTextContent('Verified')
  })

  it('lets the maker verify a submitted case', async () => {
    const { user, set } = setup(reviewCase())
    server.use(
      http.post(`${CORE}/compliance/kyc-cases/${CASE_ID}/verify`, () => {
        const verified = reviewCase({ status: 'VERIFIED', verifiedBy: ME, verifiedAt: '2026-11-25T06:00:00Z' })
        set(verified)
        return HttpResponse.json(verified)
      }),
    )

    await user.click(await screen.findByRole('button', { name: 'Verify' }))

    expect(await screen.findByText('Case verified. A different officer must now approve it.')).toBeInTheDocument()
    expect(screen.getByTestId('case-status')).toHaveTextContent('Verified')
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.getByText('You verified this case')).toBeInTheDocument()
  })

  it('lets a different officer approve a verified case', async () => {
    const { user } = setup(reviewCase({ status: 'VERIFIED', verifiedBy: OTHER_OFFICER, verifiedAt: '2026-11-24T06:00:00Z' }))
    server.use(
      http.post(`${CORE}/compliance/kyc-cases/${CASE_ID}/approve`, () =>
        HttpResponse.json(
          reviewCase({
            status: 'APPROVED',
            verifiedBy: OTHER_OFFICER,
            verifiedAt: '2026-11-24T06:00:00Z',
            approvedBy: ME,
            approvedAt: '2026-11-25T06:00:00Z',
          }),
        ),
      ),
    )

    expect(await screen.findByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.queryByText('You verified this case')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Approve' }))

    expect(await screen.findByText('Case approved. The customer is now FULL KYC.')).toBeInTheDocument()
    expect(screen.getByTestId('case-status')).toHaveTextContent('Approved')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('needs a reason to reject and sends it', async () => {
    const bodies: unknown[] = []
    const { user } = setup(reviewCase())
    server.use(
      http.post(`${CORE}/compliance/kyc-cases/${CASE_ID}/reject`, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json(
          reviewCase({ status: 'REJECTED', rejectedBy: ME, rejectedAt: '2026-11-25T06:00:00Z', rejectionReason: 'Blurry PAN card' }),
        )
      }),
    )

    await user.click(await screen.findByRole('button', { name: 'Reject…' }))
    await user.click(screen.getByRole('button', { name: 'Reject case' }))
    expect(await screen.findByText('Enter a reason.')).toBeInTheDocument()
    expect(bodies).toHaveLength(0)

    await user.type(screen.getByLabelText('Reason for rejection'), 'Blurry PAN card')
    await user.click(screen.getByRole('button', { name: 'Reject case' }))

    expect(await screen.findByText('Case rejected. The customer can see the reason.')).toBeInTheDocument()
    expect(bodies).toEqual([{ reason: 'Blurry PAN card' }])
    expect(screen.getByText('Blurry PAN card')).toBeInTheDocument()
  })

  it('opens a document through a fresh presigned link each time', async () => {
    let links = 0
    const { user } = setup(reviewCase())
    server.use(
      http.get(`${CORE}/compliance/kyc-cases/${CASE_ID}/documents/:docId`, ({ params }) => {
        links += 1
        return HttpResponse.json({
          url: `https://s3.test/kyc/${String(params.docId)}?X-Amz-Signature=sig${String(links)}`,
          expiresAt: '2026-11-25T06:05:00Z',
        })
      }),
    )

    await user.click(await screen.findByRole('button', { name: 'View PAN card' }))
    const image = await screen.findByRole('img', { name: 'PAN card uploaded by the customer' })
    expect(image).toHaveAttribute('src', expect.stringContaining('sig1'))
    expect(screen.getByTestId('link-expires')).toHaveTextContent('Link expires')

    await user.click(screen.getByRole('button', { name: 'Get a new link' }))
    await waitFor(() => {
      expect(screen.getByRole('img', { name: 'PAN card uploaded by the customer' })).toHaveAttribute(
        'src',
        expect.stringContaining('sig2'),
      )
    })

    // A PDF isn't shown inline: it opens in a new tab, without an opener.
    await user.click(screen.getByRole('button', { name: 'View Address proof' }))
    const open = await screen.findByRole('link', { name: 'Open in new tab' })
    expect(open).toHaveAttribute('target', '_blank')
    expect(open).toHaveAttribute('rel', 'noopener noreferrer')
    expect(links).toBe(3)
  })

  it('never renders a link that is not http(s)', async () => {
    const { user } = setup(reviewCase())
    server.use(
      http.get(`${CORE}/compliance/kyc-cases/${CASE_ID}/documents/:docId`, () =>
        HttpResponse.json({ url: 'javascript:alert(1)', expiresAt: '2026-11-25T06:05:00Z' }),
      ),
    )
    await user.click(await screen.findByRole('button', { name: 'View PAN card' }))
    expect(await screen.findByText('The link was not valid.')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Open in new tab' })).not.toBeInTheDocument()
  })
})

describe('KycQueuePage', () => {
  it('lists the queue for the chosen status and marks cases verified by me', async () => {
    const seen: string[] = []
    server.use(
      http.get(`${CORE}/compliance/kyc-cases`, ({ request }) => {
        const status = new URL(request.url).searchParams.get('status') ?? ''
        seen.push(status)
        return HttpResponse.json({
          items:
            status === 'VERIFIED'
              ? [{ ...VERIFIED_BY_ME, documents: VERIFIED_BY_ME.documents }]
              : [{ ...reviewCase(), documents: [] }],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
        })
      }),
    )
    const user = userEvent.setup()
    const { router } = renderRoutes([{ path: '/compliance/kyc', element: <KycQueuePage /> }], {
      path: '/compliance/kyc',
      auth: fakeAuth(['COMPLIANCE_OFFICER']).auth,
    })

    expect(await screen.findByRole('link', { name: `Review case ${CASE_ID}` })).toHaveAttribute(
      'href',
      `/compliance/kyc/${CASE_ID}`,
    )
    await user.click(screen.getByRole('tab', { name: 'Verified' }))
    expect(await screen.findByText('verified by you')).toBeInTheDocument()
    expect(router.state.location.search).toBe('?status=VERIFIED')
    expect(seen).toEqual(['SUBMITTED', 'VERIFIED'])
  })
})
