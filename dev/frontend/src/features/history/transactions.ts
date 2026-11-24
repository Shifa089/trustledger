import type { Transaction, TransactionType } from '@/api/types'
import { formatMoney, parseRupees } from '@/lib/money'

export const TYPE_LABELS: Readonly<Record<TransactionType, string>> = {
  TOPUP: 'Money added',
  TRANSFER_IN: 'Received',
  TRANSFER_OUT: 'Sent',
}

export const STATUS_LABELS: Readonly<Record<string, string>> = {
  COMPLETED: 'Completed',
  CAPTURED: 'Completed',
  HELD_FOR_REVIEW: 'Held for review',
  INITIATED: 'Processing',
  CREATED: 'Awaiting payment',
  PENDING: 'Awaiting payment',
  REJECTED: 'Rejected',
  FAILED: 'Failed',
  EXPIRED: 'Expired',
  REVERSED: 'Reversed',
  PARKED: 'Under review',
}

/** Signed for display: money out shows as "-₹…". The API amount itself is always positive. */
export function signedAmount(transaction: Transaction): string {
  const paise = parseRupees(transaction.amount)
  return formatMoney(transaction.type === 'TRANSFER_OUT' ? -paise : paise)
}

/** Last 4 characters of an ID: enough to tell wallets apart, nothing more (FR-N-1 style). */
export function shortId(id: string): string {
  return `…${id.slice(-4)}`
}

export type SortField = 'createdAt' | 'amount'
export interface SortState {
  field: SortField
  ascending: boolean
}
