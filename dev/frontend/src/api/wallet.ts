import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { addDays } from '@/lib/dates'
import { coreApi } from './index'
import { isApiError } from './errors'
import type { LimitUsage, MyWallet, Transaction, TransactionHistory, TransactionType } from './types'

/** The customer's own wallet and history (SPEC §5): no wallet ID anywhere, the token decides (BOLA). */
export const walletKeys = {
  all: ['wallet'] as const,
  me: ['wallet', 'me'] as const,
  usage: ['wallet', 'limit-usage'] as const,
  history: (query: HistoryQuery) => ['wallet', 'history', query] as const,
  activity: (from: string, to: string) => ['wallet', 'activity', from, to] as const,
}

/** The history parameters the UI sends; `page` is zero-based as in the API. */
export interface HistoryQuery {
  from?: string
  to?: string
  type?: TransactionType
  page: number
  size: number
  /** createdAt|amount, optionally ",asc" or ",desc"; absent = newest first. */
  sort?: string
}

/** No wallet yet: GET /wallets/me answers 403 KYC_REQUIRED until minimum KYC has created one. */
export function isNoWallet(error: unknown): boolean {
  return isApiError(error) && error.errorCode === 'KYC_REQUIRED'
}

/** How many times (one a second) the dashboard re-asks for a wallet that minimum KYC has just created. */
export const WALLET_WAIT_POLLS = 20

/**
 * GET /wallets/me. `waitForWallet`: the user has just reached MIN, and the wallet is created after that commit by an
 * event listener, so poll every second for a short while instead of showing "no wallet".
 */
export function useMyWallet({ waitForWallet = false }: { waitForWallet?: boolean } = {}) {
  return useQuery({
    queryKey: walletKeys.me,
    queryFn: () => coreApi.get<MyWallet>('/wallets/me'),
    refetchInterval: (query) =>
      waitForWallet && isNoWallet(query.state.error) && query.state.errorUpdateCount < WALLET_WAIT_POLLS ? 1000 : false,
  })
}

export function useLimitUsage(enabled: boolean) {
  return useQuery({
    queryKey: walletKeys.usage,
    queryFn: () => coreApi.get<LimitUsage>('/wallets/me/limit-usage'),
    enabled,
  })
}

export function fetchHistory(query: HistoryQuery, signal?: AbortSignal): Promise<TransactionHistory> {
  return coreApi.get<TransactionHistory>('/wallets/me/transactions', {
    query: { ...query },
    ...(signal === undefined ? {} : { signal }),
  })
}

/** One page of history. The previous page stays on screen while the next loads (no flicker when paging). */
export function useTransactions(query: HistoryQuery, enabled = true) {
  return useQuery({
    queryKey: walletKeys.history(query),
    queryFn: ({ signal }) => fetchHistory(query, signal),
    placeholderData: keepPreviousData,
    enabled,
  })
}

/** The most pages the 30-day chart reads (at the API's largest page size). */
export const ACTIVITY_MAX_PAGES = 5
export const ACTIVITY_PAGE_SIZE = 100

export interface Activity {
  items: Transaction[]
  /** More rows exist than the chart read; it shows the newest ones. */
  truncated: boolean
}

/** Every transaction from `from` to `to` (Indian days, both included), up to ACTIVITY_MAX_PAGES pages. */
export async function fetchActivity(from: string, to: string, signal?: AbortSignal): Promise<Activity> {
  const items: Transaction[] = []
  for (let page = 0; page < ACTIVITY_MAX_PAGES; page++) {
    const result = await fetchHistory({ from, to, page, size: ACTIVITY_PAGE_SIZE }, signal)
    items.push(...result.items)
    if (page + 1 >= result.totalPages) {
      return { items, truncated: false }
    }
  }
  return { items, truncated: true }
}

/** The last 30 Indian days up to and including `today`. */
export function useActivity(today: string, enabled: boolean) {
  const from = addDays(today, -29)
  return useQuery({
    queryKey: walletKeys.activity(from, today),
    queryFn: ({ signal }) => fetchActivity(from, today, signal),
    enabled,
  })
}
