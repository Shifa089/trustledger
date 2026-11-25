import { http, HttpResponse, type JsonBodyType } from 'msw'
import { setupServer } from 'msw/node'
import type { KycOverview, LimitUsage, MyWallet, TransactionHistory } from '@/api/types'

/** The base URLs that vite.config.ts gives the tests (test.env). */
export const CORE = 'http://core.test/api/v1'
export const AML = 'http://aml.test/api/v1'

/** A FULL-tier wallet: ₹1,500.50 balance, ₹250.00 held (fake data). */
export const FULL_WALLET: MyWallet = {
  walletId: '00000000-0000-7000-8000-00000000a001',
  tier: 'FULL',
  status: 'ACTIVE',
  currency: 'INR',
  balance: '1500.50',
  held: '250.00',
  available: '1250.50',
  limits: {
    maxBalance: '200000.00',
    monthlyLoad: '200000.00',
    p2pAllowed: true,
    dailyTransfer: '100000.00',
    highValueThreshold: '50000.00',
    monthlyTransfer: '500000.00',
  },
}

export const USAGE: LimitUsage = {
  transferredToday: '25000.00',
  transferredThisMonth: '475000.00',
  addedThisMonth: '1000.00',
}

export const FULL_KYC: KycOverview = {
  currentLevel: 'FULL',
  cases: [
    {
      id: '00000000-0000-7000-8000-00000000c001',
      level: 'MIN',
      status: 'APPROVED',
      maskedPan: 'XXXXX1234X',
      rejectionReason: null,
      createdAt: '2026-11-01T05:00:00Z',
      updatedAt: '2026-11-01T05:00:00Z',
    },
  ],
}

export const EMPTY_HISTORY: TransactionHistory = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }

/**
 * jsdom's XMLHttpRequest (used for uploads) enforces CORS like a browser: a preflight, then the allow-origin header
 * on the answer. These mirror core-app's local CORS settings.
 */
export const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Expose-Headers': 'X-Correlation-Id',
}

/** An upload answer (XHR) with the CORS headers jsdom requires. */
export function corsJson(body: JsonBodyType, status = 200, contentType = 'application/json') {
  return HttpResponse.json(body, { status, headers: { ...CORS_HEADERS, 'Content-Type': contentType } })
}

/** Default happy-path answers; a test overrides one with server.use(...). */
export const handlers = [
  http.options(`${CORE}/*`, () =>
    new HttpResponse(null, {
      status: 204,
      headers: {
        ...CORS_HEADERS,
        'Access-Control-Allow-Methods': 'GET, POST',
        'Access-Control-Allow-Headers': 'Authorization, X-Correlation-Id, Idempotency-Key, Content-Type, Accept',
      },
    }),
  ),
  http.get(`${CORE}/me`, () =>
    HttpResponse.json({
      id: '00000000-0000-0000-0000-000000000001',
      email: 'c*******1@example.test',
      phone: 'XXXXXX1234',
      fullName: 'Customer One',
      status: 'ACTIVE',
      roles: ['CUSTOMER'],
    }),
  ),
  http.get(`${CORE}/kyc/me`, () => HttpResponse.json(FULL_KYC)),
  http.get(`${CORE}/wallets/me`, () => HttpResponse.json(FULL_WALLET)),
  http.get(`${CORE}/wallets/me/limit-usage`, () => HttpResponse.json(USAGE)),
  http.get(`${CORE}/wallets/me/transactions`, () => HttpResponse.json(EMPTY_HISTORY)),
  http.get(`${CORE}/me/consents`, () =>
    HttpResponse.json({ requiredGiven: true, consents: [], policyVersions: { MARKETING: '2026-11-01' } }),
  ),
  http.get(`${CORE}/me/data-requests`, () => HttpResponse.json({ items: [] })),
  http.get(`${AML}/aml/cases`, () =>
    HttpResponse.json({ content: [], page: 0, size: 1, totalElements: 3, totalPages: 3 }),
  ),
]

export const server = setupServer(...handlers)

/** A ProblemDetail response as core-app sends it. */
export function problem(status: number, errorCode: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { status, errorCode, title: errorCode, correlationId: 'corr-test-1', ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}
