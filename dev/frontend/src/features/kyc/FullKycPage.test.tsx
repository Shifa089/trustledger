import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw/http'
import type { KycOverview } from '@/api/types'
import { fakeAuth, renderRoutes } from '@/test/render'
import { CORE, corsJson, server } from '@/test/server'
import { FullKycPage } from './FullKycPage'
import { MAX_DOCUMENT_BYTES } from './documents'

const MIN_ONLY: KycOverview = {
  currentLevel: 'MIN',
  cases: [
    {
      id: 'min-case',
      level: 'MIN',
      status: 'APPROVED',
      maskedPan: 'XXXXX1234X',
      rejectionReason: null,
      createdAt: '2026-11-01T05:00:00Z',
      updatedAt: '2026-11-01T05:00:00Z',
    },
  ],
}

function file(name: string, type: string, size?: number): File {
  const f = new File(['%PDF-1.4 fake'], name, { type })
  if (size !== undefined) {
    Object.defineProperty(f, 'size', { value: size })
  }
  return f
}

function setup(overview: KycOverview = MIN_ONLY) {
  const uploads: string[] = []
  const fullPosts: unknown[] = []
  server.use(
    http.get(`${CORE}/kyc/me`, () => HttpResponse.json(overview)),
    http.post(`${CORE}/kyc/full`, async ({ request }) => {
      fullPosts.push(await request.json())
      return HttpResponse.json(
        { id: 'full-case', level: 'FULL', status: 'SUBMITTED', requiredDocuments: ['PAN_CARD', 'ADDRESS_PROOF'], createdAt: '2026-11-24T05:00:00Z' },
        { status: 201 },
      )
    }),
    http.post(`${CORE}/kyc/cases/:id/documents`, async ({ request, params }) => {
      const body = await request.text()
      uploads.push(`${String(params.id)}:${/name="type"\r\n\r\n(\w+)/.exec(body)?.[1] ?? '?'}`)
      return corsJson({ id: 'doc', type: 'PAN_CARD', contentType: 'application/pdf', sizeBytes: 13, sha256: 'x', uploadedAt: '2026-11-24T05:00:00Z' }, 201)
    }),
  )
  // applyAccept: false, so a wrong file type reaches our own check, as it can in a real browser ("All files").
  const user = userEvent.setup({ applyAccept: false })
  renderRoutes([{ path: '/customer/kyc/full', element: <FullKycPage /> }], {
    path: '/customer/kyc/full',
    auth: fakeAuth(['CUSTOMER']).auth,
  })
  return { user, uploads, fullPosts }
}

async function submitAddress(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText('Address'), '12 Test Street, Bengaluru 560001')
  await user.click(screen.getByRole('button', { name: 'Continue to documents' }))
  await screen.findByText('Step 2 of 2: documents')
}

function row(label: string) {
  return screen.getByLabelText(label).closest('li') as HTMLElement
}

describe('FullKycPage', () => {
  it('shows the demo notice', async () => {
    setup()
    expect(await screen.findByText('Use fake data, this is a demo')).toBeInTheDocument()
  })

  it('sends the address, then marks the required documents', async () => {
    const { user, fullPosts } = setup()
    await submitAddress(user)

    expect(fullPosts).toEqual([{ address: '12 Test Street, Bengaluru 560001' }])
    expect(within(row('PAN card')).getByText('required')).toBeInTheDocument()
    expect(within(row('Address proof')).getByText('required')).toBeInTheDocument()
    expect(within(row('Photo')).getByText('optional')).toBeInTheDocument()
  })

  it('refuses a file over 5 MB before uploading anything', async () => {
    const { user, uploads } = setup()
    await submitAddress(user)

    await user.upload(screen.getByLabelText('PAN card'), file('pan.pdf', 'application/pdf', MAX_DOCUMENT_BYTES + 1))

    expect(await within(row('PAN card')).findByText('This file is larger than 5 MB.')).toBeInTheDocument()
    expect(screen.getByLabelText('PAN card')).toHaveAttribute('aria-invalid', 'true')
    expect(uploads).toHaveLength(0)
  })

  it('refuses a file type other than PDF, JPG or PNG before uploading anything', async () => {
    const { user, uploads } = setup()
    await submitAddress(user)

    await user.upload(screen.getByLabelText('PAN card'), file('pan.gif', 'image/gif'))

    expect(await within(row('PAN card')).findByText('Choose a PDF, JPG or PNG file.')).toBeInTheDocument()
    expect(uploads).toHaveLength(0)
  })

  it('accepts exactly 5 MB and uploads each document to the new case', async () => {
    const { user, uploads } = setup()
    await submitAddress(user)

    await user.upload(screen.getByLabelText('PAN card'), file('pan.pdf', 'application/pdf', MAX_DOCUMENT_BYTES))
    expect(await within(row('PAN card')).findByText('Uploaded')).toBeInTheDocument()
    await user.upload(screen.getByLabelText('Address proof'), file('bill.png', 'image/png'))
    expect(await within(row('Address proof')).findByText('Uploaded')).toBeInTheDocument()

    expect(uploads).toEqual(['full-case:PAN_CARD', 'full-case:ADDRESS_PROOF'])
    expect(screen.getByRole('status')).toHaveTextContent('All required documents are uploaded.')
  })

  it('shows the server refusal on the document row', async () => {
    const { user } = setup()
    server.use(
      http.post(`${CORE}/kyc/cases/:id/documents`, () =>
        corsJson({ status: 415, errorCode: 'UNSUPPORTED_DOCUMENT_TYPE', correlationId: 'c-1' }, 415, 'application/problem+json'),
      ),
    )
    await submitAddress(user)

    await user.upload(screen.getByLabelText('Photo'), file('photo.jpg', 'image/jpeg'))

    expect(await within(row('Photo')).findByText(/Unsupported file type\. Upload a PDF, JPG or PNG\. \(reference c-1\)/)).toBeInTheDocument()
  })

  it('resumes uploads on a submitted case after a reload', async () => {
    const { user, uploads } = setup({
      currentLevel: 'MIN',
      cases: [
        ...MIN_ONLY.cases,
        {
          id: 'open-case',
          level: 'FULL',
          status: 'SUBMITTED',
          maskedPan: null,
          rejectionReason: null,
          createdAt: '2026-11-20T05:00:00Z',
          updatedAt: '2026-11-20T05:00:00Z',
        },
      ],
    })

    await screen.findByText('Step 2 of 2: documents')
    await user.upload(screen.getByLabelText('PAN card'), file('pan.pdf', 'application/pdf'))

    await waitFor(() => {
      expect(uploads).toEqual(['open-case:PAN_CARD'])
    })
  })

  it('sends someone without minimum KYC there first', async () => {
    setup({ currentLevel: 'NONE', cases: [] })
    expect(await screen.findByRole('link', { name: 'minimum KYC' })).toHaveAttribute('href', '/customer/kyc/min')
  })
})
