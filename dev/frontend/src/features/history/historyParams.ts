import { TRANSACTION_TYPES, type TransactionType } from '@/api/types'
import type { HistoryQuery } from '@/api/wallet'
import { isIsoDay } from '@/lib/dates'
import type { SortField, SortState } from './transactions'

/**
 * The history's filters live in the URL (?from=&to=&type=&sort=&page=), so a filtered view can be bookmarked,
 * shared, reloaded and walked with Back. `page` in the URL is 1-based for people; the API's is 0-based. Bad values
 * (hand-edited URLs) are ignored rather than sent, so they never cause a 400.
 */
export interface HistoryParams {
  from: string | undefined
  to: string | undefined
  type: TransactionType | undefined
  /** Zero-based. */
  page: number
  sort: SortState
}

export const HISTORY_PAGE_SIZE = 20
export const DEFAULT_SORT: SortState = { field: 'createdAt', ascending: false }

function day(value: string | null): string | undefined {
  return value !== null && isIsoDay(value) ? value : undefined
}

function sortOf(value: string | null): SortState {
  const match = value === null ? null : /^(createdAt|amount),(asc|desc)$/.exec(value)
  if (match === null) {
    return DEFAULT_SORT
  }
  return { field: match[1] as SortField, ascending: match[2] === 'asc' }
}

export function readHistoryParams(search: URLSearchParams): HistoryParams {
  const type = search.get('type')
  const page = Number.parseInt(search.get('page') ?? '1', 10)
  return {
    from: day(search.get('from')),
    to: day(search.get('to')),
    type: TRANSACTION_TYPES.find((known) => known === type),
    page: Number.isSafeInteger(page) && page >= 1 ? page - 1 : 0,
    sort: sortOf(search.get('sort')),
  }
}

export function sortParam(sort: SortState): string {
  return `${sort.field},${sort.ascending ? 'asc' : 'desc'}`
}

/** The URL for `params`, leaving defaults out so a plain /customer/history stays plain. */
export function writeHistoryParams(params: HistoryParams): URLSearchParams {
  const search = new URLSearchParams()
  if (params.from !== undefined) search.set('from', params.from)
  if (params.to !== undefined) search.set('to', params.to)
  if (params.type !== undefined) search.set('type', params.type)
  if (params.sort.field !== DEFAULT_SORT.field || params.sort.ascending !== DEFAULT_SORT.ascending) {
    search.set('sort', sortParam(params.sort))
  }
  if (params.page > 0) search.set('page', String(params.page + 1))
  return search
}

/** The API query for `params`. */
export function toHistoryQuery(params: HistoryParams): HistoryQuery {
  const query: HistoryQuery = { page: params.page, size: HISTORY_PAGE_SIZE, sort: sortParam(params.sort) }
  if (params.from !== undefined) query.from = params.from
  if (params.to !== undefined) query.to = params.to
  if (params.type !== undefined) query.type = params.type
  return query
}

/** Clicking a column: the same column flips direction, a new one starts descending (newest / largest first). */
export function nextSort(current: SortState, field: SortField): SortState {
  return current.field === field ? { field, ascending: !current.ascending } : { field, ascending: false }
}
