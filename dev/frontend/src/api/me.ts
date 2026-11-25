import { useQuery } from '@tanstack/react-query'
import { coreApi } from './index'
import type { Me } from './types'

export const meQueryKey = ['me'] as const

/** GET /me: the caller's core user. Its `id` is what core-app records as verifiedBy, approvedBy and frozenBy. */
export function useMe() {
  return useQuery({ queryKey: meQueryKey, queryFn: () => coreApi.get<Me>('/me'), staleTime: 5 * 60_000 })
}
