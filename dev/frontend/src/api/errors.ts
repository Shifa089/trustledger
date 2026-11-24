/**
 * RFC 9457 ProblemDetail from core-app and risk-aml-service (SPEC §5 "Error format"), and the message a user sees.
 * The errorCode list mirrors core-app's and risk-aml-service's ErrorCode enums.
 */

export interface Violation {
  field: string
  message: string
}

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  errorCode?: string
  correlationId?: string
  violations?: Violation[]
}

/** Client-side codes for failures that never reached a ProblemDetail. */
export type ClientErrorCode = 'NETWORK_ERROR' | 'UNKNOWN_ERROR'

export const FRIENDLY_MESSAGES = {
  VALIDATION_FAILED: 'Some fields need attention. Check the highlighted values and try again.',
  UNAUTHENTICATED: 'Your session has ended. Please sign in again.',
  ACCESS_DENIED: "You don't have permission to do that.",
  KYC_REQUIRED: 'Complete your KYC before using this feature.',
  NOT_FOUND: "We couldn't find that. It may not exist or you may not have access to it.",
  REQUEST_IN_PROGRESS: 'This request is already being processed. Please wait a moment.',
  MAKER_CHECKER_VIOLATION: 'A different officer must approve a case you verified (maker-checker).',
  INVALID_STATE_TRANSITION: 'That action is not possible in the current state. Refresh and try again.',
  KYC_ALREADY_SUBMITTED: 'Your KYC has already been submitted.',
  PHONE_ALREADY_REGISTERED: 'This mobile number is already registered to another account.',
  KYC_DOCUMENTS_MISSING: 'Some required KYC documents are missing.',
  CONCURRENT_MODIFICATION: 'Someone else changed this at the same time. Refresh and try again.',
  WALLET_FROZEN: 'This wallet is frozen and cannot send or receive money.',
  WALLET_NOT_EMPTY: 'The wallet still holds money, so this request cannot go ahead.',
  AML_CASE_OPEN: 'This request is on hold while a compliance review is open.',
  DUPLICATE_POSTING: 'This has already been recorded.',
  DOCUMENT_TOO_LARGE: 'The document is too large. The maximum size is 5 MB.',
  PAYLOAD_TOO_LARGE: 'The request is too large.',
  UNSUPPORTED_DOCUMENT_TYPE: 'Unsupported file type. Upload a PDF, JPG or PNG.',
  INSUFFICIENT_FUNDS: 'Your available balance is too low for this amount.',
  LIMIT_EXCEEDED: 'This would exceed one of your wallet limits.',
  IDEMPOTENCY_KEY_REUSED: 'This request was already sent with different details. Start a new one.',
  AGE_REQUIREMENT_NOT_MET: 'You must be at least 18 years old.',
  RATE_LIMITED: 'Too many requests. Please wait a moment and try again.',
  GATEWAY_REJECTED: 'The payment gateway refused the request. Retrying will not help.',
  GATEWAY_UNAVAILABLE: 'The payment gateway is unavailable. Please try again shortly.',
  STORAGE_UNAVAILABLE: 'Document storage is unavailable. Please try again shortly.',
  WEBHOOK_SIGNATURE_INVALID: 'The payment notification could not be verified.',
  NOT_CASE_ASSIGNEE: 'Only the officer assigned to this case can do that.',
  NOT_IMPLEMENTED: 'This feature is not available yet.',
  UNSUPPORTED_REQUEST: 'This request is not supported.',
  INTERNAL_ERROR: 'Something went wrong on our side.',
  NETWORK_ERROR: "Can't reach TrustLedger. Check your connection and try again.",
  UNKNOWN_ERROR: 'Something went wrong.',
} as const satisfies Record<string, string>

export type ErrorCode = keyof typeof FRIENDLY_MESSAGES

function isKnownCode(code: string): code is ErrorCode {
  return Object.hasOwn(FRIENDLY_MESSAGES, code)
}

/** The message for an errorCode; an unknown code (a newer backend) gets the generic one. */
export function friendlyMessage(errorCode: string | undefined): string {
  return errorCode !== undefined && isKnownCode(errorCode)
    ? FRIENDLY_MESSAGES[errorCode]
    : FRIENDLY_MESSAGES.UNKNOWN_ERROR
}

/** A failed API call. `message` is the friendly text; `detail` is the server's own wording. */
export class ApiError extends Error {
  override readonly name = 'ApiError'
  readonly status: number
  readonly errorCode: string
  readonly title: string | undefined
  readonly detail: string | undefined
  readonly correlationId: string | undefined
  readonly violations: readonly Violation[]

  constructor(status: number, problem: ProblemDetail, fallbackCorrelationId?: string) {
    const errorCode = problem.errorCode ?? (status === 0 ? 'NETWORK_ERROR' : 'UNKNOWN_ERROR')
    super(friendlyMessage(errorCode))
    this.status = status
    this.errorCode = errorCode
    this.title = problem.title
    this.detail = problem.detail
    this.correlationId = problem.correlationId ?? fallbackCorrelationId
    this.violations = problem.violations ?? []
  }

  /** Text for support: "… (reference 7f9c…)". The correlation ID finds the request in the logs. */
  get messageWithReference(): string {
    return this.correlationId === undefined ? this.message : `${this.message} (reference ${this.correlationId})`
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

/** Best-effort parse: only the known fields, each with the right type. */
export function toProblemDetail(body: unknown): ProblemDetail {
  if (typeof body !== 'object' || body === null) {
    return {}
  }
  const raw = body as Record<string, unknown>
  const text = (key: string): string | undefined => (typeof raw[key] === 'string' ? raw[key] : undefined)
  const problem: ProblemDetail = {}
  const assign = <K extends keyof ProblemDetail>(key: K, value: ProblemDetail[K] | undefined) => {
    if (value !== undefined) {
      problem[key] = value
    }
  }
  assign('type', text('type'))
  assign('title', text('title'))
  assign('detail', text('detail'))
  assign('instance', text('instance'))
  assign('errorCode', text('errorCode'))
  assign('correlationId', text('correlationId'))
  assign('status', typeof raw.status === 'number' ? raw.status : undefined)
  if (Array.isArray(raw.violations)) {
    problem.violations = raw.violations
      .filter((v): v is Record<string, unknown> => typeof v === 'object' && v !== null)
      .map((v) => ({
        field: typeof v.field === 'string' ? v.field : '',
        message: typeof v.message === 'string' ? v.message : '',
      }))
  }
  return problem
}
