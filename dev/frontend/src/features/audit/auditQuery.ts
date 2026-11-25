import type { AuditQuery } from '@/api/operations'
import { addDays, isIsoDay, startOfIndianDay } from '@/lib/dates'
import { pageFrom } from '@/lib/staff'
import { UUID_PATTERN } from '../transfer/schemas'

export const AUDIT_PAGE_SIZE = 20

/** Entity types and actions the audit trail records today; the inputs accept any value. */
export const ENTITY_TYPES = ['KYC_CASE', 'KYC_DOCUMENT', 'TRANSFER', 'WALLET', 'RECONCILIATION_RUN']
export const ACTIONS = [
  'KYC_CASE_VERIFY',
  'KYC_CASE_APPROVE',
  'KYC_CASE_REJECT',
  'KYC_DOCUMENT_VIEW',
  'TRANSFER_APPROVE',
  'TRANSFER_REJECT',
  'WALLET_FREEZE',
  'WALLET_UNFREEZE',
  'RECONCILIATION_RUN',
]

export const AUDIT_FIELDS = ['entityType', 'entityId', 'actorId', 'action', 'from', 'to'] as const
export type AuditField = (typeof AUDIT_FIELDS)[number]

const CODE = /^[A-Z][A-Z0-9_]{0,63}$/

/**
 * The URL's filters as the API takes them. India-time days become instants: `from` is the start of that day and
 * `to` the start of the day after (the API's `to` is exclusive). Bad values are reported, never sent.
 */
export function toAuditQuery(search: URLSearchParams): { query: AuditQuery; problems: Partial<Record<AuditField, string>> } {
  const value = (key: AuditField) => (search.get(key) ?? '').trim()
  const problems: Partial<Record<AuditField, string>> = {}
  const code = (key: 'entityType' | 'action') => {
    const v = value(key).toUpperCase()
    if (v !== '' && !CODE.test(v)) problems[key] = 'Letters, digits and underscores only.'
    return v === '' || problems[key] !== undefined ? undefined : v
  }
  const uuid = (key: 'entityId' | 'actorId') => {
    const v = value(key)
    if (v !== '' && !UUID_PATTERN.test(v)) problems[key] = 'Enter a full ID (a UUID).'
    return v === '' || problems[key] !== undefined ? undefined : v.toLowerCase()
  }
  const day = (key: 'from' | 'to') => {
    const v = value(key)
    return v !== '' && isIsoDay(v) ? v : undefined
  }
  const from = day('from')
  const to = day('to')
  if (from !== undefined && to !== undefined && from > to) problems.from = '"From" must not be after "To".'
  return {
    query: {
      entityType: code('entityType'),
      entityId: uuid('entityId'),
      actorId: uuid('actorId'),
      action: code('action'),
      from: from === undefined ? undefined : startOfIndianDay(from),
      to: to === undefined ? undefined : startOfIndianDay(addDays(to, 1)),
      page: pageFrom(search),
      size: AUDIT_PAGE_SIZE,
    },
    problems,
  }
}
