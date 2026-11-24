import { ApiError, toProblemDetail } from './errors'

/**
 * The one way the web app talks to the backend (CLAUDE.md "all API calls go through the API client").
 *
 * Every request gets:
 * - `Authorization: Bearer <access token>` when the user is signed in (read from memory, never from storage);
 * - a fresh `X-Correlation-Id`, so a failure on screen can be found in the server logs (SPEC §5 "Headers");
 * - `Idempotency-Key` when the caller passes one (POST /transfers, POST /topups).
 * A non-2xx response becomes an {@link ApiError} with a friendly message mapped from the ProblemDetail errorCode.
 */

export const API_PREFIX = '/api/v1'
export const CORRELATION_HEADER = 'X-Correlation-Id'
export const IDEMPOTENCY_HEADER = 'Idempotency-Key'

export type QueryValue = string | number | boolean | undefined
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface RequestOptions {
  method?: HttpMethod
  /** Sent as JSON, unless it is FormData (multipart, the browser sets the boundary). */
  body?: unknown
  query?: Record<string, QueryValue>
  idempotencyKey?: string
  signal?: AbortSignal
}

export interface ApiClientOptions {
  /** Service origin without the API prefix, e.g. http://localhost:8080. */
  baseUrl: string
  /** The current access token, or undefined when signed out. */
  getAccessToken: () => string | undefined | Promise<string | undefined>
  /** Called after any 401, e.g. to start a new sign-in. */
  onUnauthenticated?: () => void
  fetchFn?: typeof fetch
  newCorrelationId?: () => string
}

export interface ApiClient {
  readonly baseUrl: string
  request: <T>(path: string, options?: RequestOptions) => Promise<T>
  get: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) => Promise<T>
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) => Promise<T>
  /** For files (statement PDF, STR CSV): the raw body plus the server's file name, if it sent one. */
  download: (path: string, options?: Omit<RequestOptions, 'body'>) => Promise<{ blob: Blob; fileName?: string }>
}

export function buildUrl(baseUrl: string, path: string, query?: Record<string, QueryValue>): string {
  const cleanPath = path.startsWith('/') ? path : `/${path}`
  const url = new URL(`${baseUrl.replace(/\/+$/, '')}${API_PREFIX}${cleanPath}`)
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined && value !== '') {
      url.searchParams.set(key, String(value))
    }
  }
  return url.toString()
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const fetchFn = options.fetchFn ?? ((input: RequestInfo | URL, init?: RequestInit) => fetch(input, init))
  const newCorrelationId = options.newCorrelationId ?? (() => crypto.randomUUID())

  async function send(path: string, request: RequestOptions, accept: string): Promise<Response> {
    const correlationId = newCorrelationId()
    const headers = new Headers({ Accept: accept, [CORRELATION_HEADER]: correlationId })
    const token = await options.getAccessToken()
    if (token !== undefined && token !== '') {
      headers.set('Authorization', `Bearer ${token}`)
    }
    if (request.idempotencyKey !== undefined) {
      headers.set(IDEMPOTENCY_HEADER, request.idempotencyKey)
    }
    let body: BodyInit | undefined
    if (request.body instanceof FormData) {
      body = request.body
    } else if (request.body !== undefined) {
      headers.set('Content-Type', 'application/json')
      body = JSON.stringify(request.body)
    }

    const init: RequestInit = { method: request.method ?? 'GET', headers, credentials: 'omit' }
    if (body !== undefined) init.body = body
    if (request.signal !== undefined) init.signal = request.signal

    let response: Response
    try {
      response = await fetchFn(buildUrl(options.baseUrl, path, request.query), init)
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') {
        throw cause
      }
      throw new ApiError(0, {}, correlationId)
    }
    if (!response.ok) {
      const problem = toProblemDetail(await readJson(response))
      if (response.status === 401) {
        options.onUnauthenticated?.()
      }
      const fallbackCode = statusFallback(response.status)
      if (problem.errorCode === undefined && fallbackCode !== undefined) {
        problem.errorCode = fallbackCode
      }
      const echoed = response.headers.get(CORRELATION_HEADER) ?? correlationId
      throw new ApiError(response.status, problem, echoed)
    }
    return response
  }

  async function request<T>(path: string, req: RequestOptions = {}): Promise<T> {
    const response = await send(path, req, 'application/json, application/problem+json')
    if (response.status === 204) {
      return undefined as T
    }
    return (await readJson(response)) as T
  }

  return {
    baseUrl: options.baseUrl,
    request,
    get: (path, req) => request(path, { ...req, method: 'GET' }),
    post: (path, body, req) => request(path, { ...req, method: 'POST', body }),
    download: async (path, req) => {
      const response = await send(path, { ...req, method: req?.method ?? 'GET' }, '*/*')
      const fileName = fileNameFrom(response.headers.get('Content-Disposition'))
      const blob = await response.blob()
      return fileName === undefined ? { blob } : { blob, fileName }
    },
  }
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (text === '') {
    return undefined
  }
  try {
    return JSON.parse(text) as unknown
  } catch {
    return undefined
  }
}

/** When a proxy or gateway answers without a ProblemDetail, still give the right message. */
function statusFallback(status: number): string | undefined {
  switch (status) {
    case 401:
      return 'UNAUTHENTICATED'
    case 403:
      return 'ACCESS_DENIED'
    case 404:
      return 'NOT_FOUND'
    case 429:
      return 'RATE_LIMITED'
    default:
      return status >= 500 ? 'INTERNAL_ERROR' : undefined
  }
}

function fileNameFrom(disposition: string | null): string | undefined {
  const match = disposition === null ? null : /filename="?([^";]+)"?/i.exec(disposition)
  return match?.[1]
}
