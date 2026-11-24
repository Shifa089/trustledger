import { http, HttpResponse } from 'msw/http'
import { CORE, server } from '@/test/server'
import { createApiClient, CORRELATION_HEADER, IDEMPOTENCY_HEADER } from './client'
import { ApiError, FRIENDLY_MESSAGES } from './errors'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

function client(token: string | null = 'access-token-1') {
  return createApiClient({ baseUrl: 'http://core.test', getAccessToken: () => token ?? undefined })
}

/** Captures the last request a handler saw. */
function capture(method: 'get' | 'post', path: string, response: () => Response = () => HttpResponse.json({})) {
  const seen: { request?: Request } = {}
  server.use(
    http[method](`${CORE}${path}`, ({ request }) => {
      seen.request = request.clone()
      return response()
    }),
  )
  return seen
}

function problem(status: number, body: Record<string, unknown>, headers: Record<string, string> = {}) {
  return () =>
    HttpResponse.json(body, { status, headers: { 'Content-Type': 'application/problem+json', ...headers } })
}

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise
  } catch (error) {
    if (error instanceof ApiError) return error
    throw error
  }
  throw new Error('expected the call to fail')
}

describe('API client: headers', () => {
  it('sends the bearer token and a fresh X-Correlation-Id on every call', async () => {
    const seen = capture('get', '/me')
    await client().get('/me')
    const first = seen.request?.headers.get(CORRELATION_HEADER)

    expect(seen.request?.headers.get('Authorization')).toBe('Bearer access-token-1')
    expect(first).toMatch(UUID)

    await client().get('/me')
    expect(seen.request?.headers.get(CORRELATION_HEADER)).toMatch(UUID)
    expect(seen.request?.headers.get(CORRELATION_HEADER)).not.toBe(first)
  })

  it('sends no Authorization header when signed out', async () => {
    const seen = capture('get', '/me')
    await client(null).get('/me')

    expect(seen.request?.headers.has('Authorization')).toBe(false)
    expect(seen.request?.headers.get(CORRELATION_HEADER)).toMatch(UUID)
  })

  it('sends Idempotency-Key and a JSON body when given', async () => {
    const seen = capture('post', '/transfers', () => HttpResponse.json({ status: 'COMPLETED' }, { status: 201 }))
    const result = await client().post<{ status: string }>(
      '/transfers',
      { amount: '100.00' },
      { idempotencyKey: 'key-123' },
    )

    expect(result.status).toBe('COMPLETED')
    expect(seen.request?.headers.get(IDEMPOTENCY_HEADER)).toBe('key-123')
    expect(seen.request?.headers.get('Content-Type')).toBe('application/json')
    expect(await seen.request?.json()).toEqual({ amount: '100.00' })
  })

  it('adds the /api/v1 prefix and skips empty query values', async () => {
    const seen = capture('get', '/wallets/me/transactions')
    await client().get('/wallets/me/transactions', { query: { page: 0, size: 20, type: undefined, from: '' } })

    const url = new URL(seen.request?.url ?? '')
    expect(url.pathname).toBe('/api/v1/wallets/me/transactions')
    expect(url.search).toBe('?page=0&size=20')
  })

  it('returns undefined for 204 No Content', async () => {
    capture('post', '/me/erasure-request', () => new HttpResponse(null, { status: 204 }))
    await expect(client().post('/me/erasure-request')).resolves.toBeUndefined()
  })

  it('works with the AML service base URL too', async () => {
    const aml = createApiClient({ baseUrl: 'http://aml.test/', getAccessToken: () => 'officer-token' })
    const page = await aml.get<{ totalElements: number }>('/aml/cases')
    expect(page.totalElements).toBe(3)
  })
})

describe('API client: ProblemDetail errors', () => {
  it('maps errorCode to a friendly message and keeps the server details', async () => {
    capture(
      'post',
      '/transfers',
      problem(422, {
        type: 'https://trustledger.dev/errors/insufficient-funds',
        title: 'Insufficient funds',
        status: 422,
        detail: 'Available balance is lower than the transfer amount.',
        errorCode: 'INSUFFICIENT_FUNDS',
        correlationId: 'corr-from-body',
      }),
    )
    const error = await failure(client().post('/transfers', {}))

    expect(error.status).toBe(422)
    expect(error.errorCode).toBe('INSUFFICIENT_FUNDS')
    expect(error.message).toBe(FRIENDLY_MESSAGES.INSUFFICIENT_FUNDS)
    expect(error.detail).toBe('Available balance is lower than the transfer amount.')
    expect(error.correlationId).toBe('corr-from-body')
    expect(error.messageWithReference).toContain('reference corr-from-body')
  })

  it('keeps the validation violations', async () => {
    capture(
      'post',
      '/kyc/min',
      problem(400, {
        errorCode: 'VALIDATION_FAILED',
        violations: [{ field: 'pan', message: 'must be a valid PAN' }],
      }),
    )
    const error = await failure(client().post('/kyc/min', {}))

    expect(error.message).toBe(FRIENDLY_MESSAGES.VALIDATION_FAILED)
    expect(error.violations).toEqual([{ field: 'pan', message: 'must be a valid PAN' }])
  })

  it('gives an unknown errorCode the generic message', async () => {
    capture('get', '/me', problem(409, { errorCode: 'SOMETHING_NEW' }))
    const error = await failure(client().get('/me'))

    expect(error.errorCode).toBe('SOMETHING_NEW')
    expect(error.message).toBe(FRIENDLY_MESSAGES.UNKNOWN_ERROR)
  })

  it('uses the echoed correlation header when the body has none', async () => {
    capture('get', '/me', problem(404, { errorCode: 'NOT_FOUND' }, { [CORRELATION_HEADER]: 'corr-from-header' }))
    const error = await failure(client().get('/me'))

    expect(error.correlationId).toBe('corr-from-header')
  })

  it('falls back to the status when a proxy answers without a ProblemDetail', async () => {
    capture('get', '/me', () => new HttpResponse('<html>Bad gateway</html>', { status: 502 }))
    const error = await failure(client().get('/me'))

    expect(error.errorCode).toBe('INTERNAL_ERROR')
    expect(error.message).toBe(FRIENDLY_MESSAGES.INTERNAL_ERROR)
    expect(error.correlationId).toMatch(UUID) // the one we sent, so support can still search for it
  })

  it('reports a network failure as NETWORK_ERROR', async () => {
    server.use(http.get(`${CORE}/me`, () => HttpResponse.error()))
    const error = await failure(client().get('/me'))

    expect(error.status).toBe(0)
    expect(error.errorCode).toBe('NETWORK_ERROR')
    expect(error.message).toBe(FRIENDLY_MESSAGES.NETWORK_ERROR)
  })

  it('calls onUnauthenticated on 401', async () => {
    capture('get', '/me', problem(401, { errorCode: 'UNAUTHENTICATED' }))
    const onUnauthenticated = vi.fn()
    const api = createApiClient({ baseUrl: 'http://core.test', getAccessToken: () => 'x', onUnauthenticated })

    const error = await failure(api.get('/me'))
    expect(error.message).toBe(FRIENDLY_MESSAGES.UNAUTHENTICATED)
    expect(onUnauthenticated).toHaveBeenCalledOnce()
  })
})
