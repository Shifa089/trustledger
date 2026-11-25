import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import type { ItemPage } from './compliance'
import { coreApi } from './index'

/** ADMIN: reconciliation (FR-L-4). AUDITOR: the audit trail and its hash chain (FR-A-1/2). */

export interface ReconciliationMismatch {
  walletId: string
  reason: 'BALANCE' | 'UNLINKED'
  currency: string
  cachedBalance: string
  ledgerBalance: string | null
}

export interface ReconciliationReport {
  walletsChecked: number
  mismatches: number
  unlinked: number
  listed: ReconciliationMismatch[]
  truncated: boolean
  startedAt: string
  finishedAt: string
}

export function useRunReconciliation() {
  return useMutation({ mutationFn: () => coreApi.post<ReconciliationReport>('/admin/reconciliation/run') })
}

export interface AuditEvent {
  seq: number
  occurredAt: string
  actorId: string | null
  actorRole: string | null
  action: string
  entityType: string
  entityId: string | null
  before: unknown
  after: unknown
  ip: string | null
  userAgent: string | null
  correlationId: string | null
  prevHash: string | null
  hash: string
}

/** The filters as the API takes them: `from` inclusive and `to` exclusive, both instants. */
export interface AuditQuery {
  entityType?: string | undefined
  entityId?: string | undefined
  actorId?: string | undefined
  action?: string | undefined
  from?: string | undefined
  to?: string | undefined
  page: number
  size: number
}

export function useAuditEvents(query: AuditQuery, enabled = true) {
  return useQuery({
    queryKey: ['audit', 'events', query],
    queryFn: () => coreApi.get<ItemPage<AuditEvent>>('/audit/events', { query: { ...query } }),
    placeholderData: keepPreviousData,
    enabled,
  })
}

export interface ChainVerification {
  status: 'OK' | 'BROKEN'
  checkedEvents: number
  lastSeq: number | null
  lastHash: string | null
  brokenSeq: number | null
  reason: 'PREV_HASH_MISMATCH' | 'HASH_MISMATCH' | 'UNREADABLE' | null
}

/** A mutation, not a query: verification walks the whole chain, so it runs only when the auditor asks. */
export function useVerifyChain() {
  return useMutation({ mutationFn: () => coreApi.get<ChainVerification>('/audit/verify') })
}
