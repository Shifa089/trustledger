import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw/http'
import { fakeAuth, renderRoutes } from '@/test/render'
import { CORE, corsJson, problem, server } from '@/test/server'
import { MinKycForm } from './MinKycForm'
import { MinKycPage } from './MinKycPage'

const TODAY = '2026-11-24'

function setup() {
  const posts: unknown[] = []
  server.use(
    http.post(`${CORE}/kyc/min`, async ({ request }) => {
      posts.push(await request.json())
      return corsJson({ id: 'case-1', level: 'MIN', status: 'APPROVED' }, 201)
    }),
  )
  const onDone = vi.fn()
  const user = userEvent.setup()
  renderRoutes([{ path: '/kyc', element: <MinKycForm today={TODAY} onDone={onDone} /> }], {
    path: '/kyc',
    auth: fakeAuth(['CUSTOMER']).auth,
  })
  return { posts, onDone, user }
}

/** A date input can't be typed into in jsdom (partial values are sanitised away), so set it like a picker does. */
function pickDate(value: string) {
  const input = screen.getByLabelText('Date of birth')
  fireEvent.change(input, { target: { value } })
  fireEvent.blur(input)
}

async function fillValid(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Full name'), 'Test Customer')
  pickDate('1995-04-15')
  await user.type(screen.getByLabelText('Mobile number'), '9876543210')
  await user.type(screen.getByLabelText('PAN'), 'abcpe1234f')
}

describe('MinKycForm', () => {
  it('labels every input', () => {
    setup()
    for (const label of ['Full name', 'Date of birth', 'Mobile number', 'PAN']) {
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }
  })

  it('shows an error for an invalid PAN and sends nothing', async () => {
    const { posts, user } = setup()
    await fillValid(user)
    await user.clear(screen.getByLabelText('PAN'))
    // The 4th letter is the holder type: X is not one.
    await user.type(screen.getByLabelText('PAN'), 'ABCXE1234F')
    await user.click(screen.getByRole('button', { name: 'Submit minimum KYC' }))

    expect(await screen.findByText('Enter a valid PAN, for example ABCPE1234F.')).toBeInTheDocument()
    expect(screen.getByLabelText('PAN')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('PAN')).toHaveAccessibleDescription(/Enter a valid PAN/)
    expect(posts).toHaveLength(0)
  })

  it.each(['12345', '5876543210', '98765 4321', '+919876543'])('shows an error for mobile %j', async (mobile) => {
    const { posts, user } = setup()
    await fillValid(user)
    await user.clear(screen.getByLabelText('Mobile number'))
    await user.type(screen.getByLabelText('Mobile number'), mobile)
    await user.click(screen.getByRole('button', { name: 'Submit minimum KYC' }))

    expect(await screen.findByText(/Enter a 10-digit Indian mobile number/)).toBeInTheDocument()
    expect(posts).toHaveLength(0)
  })

  it('refuses someone under 18, counted on the Indian day', async () => {
    const { posts, user } = setup()
    await fillValid(user)
    pickDate('2008-11-25') // 18 tomorrow
    await user.click(screen.getByRole('button', { name: 'Submit minimum KYC' }))

    expect(await screen.findByText('You must be at least 18 years old.')).toBeInTheDocument()
    expect(posts).toHaveLength(0)
  })

  it('accepts someone who turns 18 today', async () => {
    const { posts, user } = setup()
    await fillValid(user)
    pickDate('2008-11-24')
    await user.click(screen.getByRole('button', { name: 'Submit minimum KYC' }))

    await waitFor(() => {
      expect(posts).toHaveLength(1)
    })
  })

  it('shows required-field errors on an empty submit', async () => {
    const { posts, user } = setup()
    await user.click(screen.getByRole('button', { name: 'Submit minimum KYC' }))

    expect(await screen.findByText('Enter your full name.')).toBeInTheDocument()
    expect(screen.getByText('Enter your date of birth.')).toBeInTheDocument()
    expect(posts).toHaveLength(0)
  })

  it('submits valid input (PAN upper-cased, name trimmed) and calls onDone', async () => {
    const { posts, onDone, user } = setup()
    await fillValid(user)
    await user.type(screen.getByLabelText('Full name'), '  ')
    await user.click(screen.getByRole('button', { name: 'Submit minimum KYC' }))

    await waitFor(() => {
      expect(onDone).toHaveBeenCalledOnce()
    })
    expect(posts).toEqual([
      { fullName: 'Test Customer', dateOfBirth: '1995-04-15', mobile: '9876543210', pan: 'ABCPE1234F' },
    ])
  })

  it('can be completed with the keyboard only', async () => {
    const { posts, user } = setup()
    await user.tab()
    expect(screen.getByLabelText('Full name')).toHaveFocus()
    await user.keyboard('Test Customer')
    await user.tab()
    expect(screen.getByLabelText('Date of birth')).toHaveFocus()
    pickDate('1995-04-15')
    await user.tab()
    expect(screen.getByLabelText('Mobile number')).toHaveFocus()
    await user.keyboard('9876543210')
    await user.tab()
    expect(screen.getByLabelText('PAN')).toHaveFocus()
    await user.keyboard('ABCPE1234F{Enter}')

    await waitFor(() => {
      expect(posts).toHaveLength(1)
    })
  })

  it('puts the server field errors on the fields and shows the friendly message', async () => {
    const { onDone, user } = setup()
    server.use(
      http.post(`${CORE}/kyc/min`, () =>
        problem(400, 'VALIDATION_FAILED', { violations: [{ field: 'mobile', message: 'mobile is already in use' }] }),
      ),
    )
    await fillValid(user)
    await user.click(screen.getByRole('button', { name: 'Submit minimum KYC' }))

    expect(await screen.findByText('mobile is already in use')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Some fields need attention')
    expect(onDone).not.toHaveBeenCalled()
  })

  it('explains a mobile number that is already registered', async () => {
    const { user } = setup()
    server.use(http.post(`${CORE}/kyc/min`, () => problem(409, 'PHONE_ALREADY_REGISTERED')))
    await fillValid(user)
    await user.click(screen.getByRole('button', { name: 'Submit minimum KYC' }))

    expect(await screen.findByText('This mobile number is already registered to another account.')).toBeInTheDocument()
  })
})

describe('MinKycPage', () => {
  it('takes the customer to the dashboard after a successful submit (not back to the KYC page)', async () => {
    let level: 'NONE' | 'MIN' = 'NONE'
    server.use(
      http.get(`${CORE}/kyc/me`, () => HttpResponse.json({ currentLevel: level, cases: [] })),
      http.post(`${CORE}/kyc/min`, () => {
        level = 'MIN'
        return corsJson({ id: 'case-1', level: 'MIN', status: 'APPROVED' }, 201)
      }),
    )
    const user = userEvent.setup()
    const { router } = renderRoutes(
      [
        { path: '/customer/kyc/min', element: <MinKycPage /> },
        { path: '/customer', element: <p>dashboard</p> },
        { path: '/customer/kyc', element: <p>kyc status</p> },
      ],
      { path: '/customer/kyc/min', auth: fakeAuth(['CUSTOMER']).auth },
    )
    await screen.findByText('Use fake data, this is a demo')
    await fillValid(user)
    await user.click(screen.getByRole('button', { name: 'Submit minimum KYC' }))

    expect(await screen.findByText('dashboard')).toBeInTheDocument()
    expect(router.state.location.state).toEqual({ justOnboarded: true })
  })

  it('sends someone already at MIN to the KYC status page', async () => {
    server.use(http.get(`${CORE}/kyc/me`, () => HttpResponse.json({ currentLevel: 'MIN', cases: [] })))
    renderRoutes(
      [
        { path: '/customer/kyc/min', element: <MinKycPage /> },
        { path: '/customer/kyc', element: <p>kyc status</p> },
      ],
      { path: '/customer/kyc/min', auth: fakeAuth(['CUSTOMER']).auth },
    )
    expect(await screen.findByText('kyc status')).toBeInTheDocument()
  })
})
