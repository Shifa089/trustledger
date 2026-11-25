import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { coreApi } from './index'
import type { ConsentOverview, ConsentPurpose, DataRequest, DataRequests } from './types'

/** Consent (FR-ID-3) and data rights (FR-PR-2). Always the token's own user: there is no ID to tamper with. */
export const privacyKeys = {
  consents: ['privacy', 'consents'] as const,
  requests: ['privacy', 'data-requests'] as const,
}

/** How often the request list is re-read while an export is still being prepared. */
export const DATA_REQUEST_POLL_MS = 3000

export function useConsents() {
  return useQuery({ queryKey: privacyKeys.consents, queryFn: () => coreApi.get<ConsentOverview>('/me/consents') })
}

export interface ConsentDecision {
  purpose: ConsentPurpose
  policyVersion: string
  granted: boolean
}

/** POST /me/consents: records the decision with the current policy version; the answer is the new overview. */
export function useRecordConsent() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (decision: ConsentDecision) =>
      coreApi.post<ConsentOverview>('/me/consents', { consents: [decision] }),
    onSuccess: (overview) => {
      queryClient.setQueryData(privacyKeys.consents, overview)
    },
  })
}

/** GET /me/data-requests, re-read every few seconds while any request is still PENDING. */
export function useDataRequests(pollMs: number = DATA_REQUEST_POLL_MS) {
  return useQuery({
    queryKey: privacyKeys.requests,
    queryFn: () => coreApi.get<DataRequests>('/me/data-requests'),
    refetchInterval: (query) =>
      query.state.data?.items.some((item) => item.status === 'PENDING') === true ? pollMs : false,
  })
}

/** POST /me/data-export: 202, prepared in the background. 409 REQUEST_IN_PROGRESS while one is PENDING. */
export function useRequestExport() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => coreApi.post<DataRequest>('/me/data-export'),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: privacyKeys.requests })
    },
  })
}

/** POST /me/erasure-request: synchronous; on 200 the personal data is gone and the wallet is closed. */
export function useRequestErasure() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => coreApi.post<DataRequest>('/me/erasure-request'),
    onSuccess: (erasure) => {
      // Shown at once; everything else about this user (wallet, profile) is stale now.
      queryClient.setQueryData<DataRequests>(privacyKeys.requests, (old) => ({
        items: [erasure, ...(old?.items ?? []).filter((item) => item.id !== erasure.id)],
      }))
      void queryClient.invalidateQueries({ predicate: (query) => query.queryKey[0] !== 'privacy' })
    },
  })
}
