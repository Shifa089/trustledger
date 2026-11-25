import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { coreApi } from './index'
import type { Topup, Transfer, TransferRequest } from './types'
import { walletKeys } from './wallet'

/**
 * Money movement (SPEC §5, FR-P-1..4). Both POSTs need an `Idempotency-Key`. The caller owns the key: one per
 * attempt, reused when that same attempt is retried, so a retry after a timeout can never move money twice (the
 * backend replays the stored 2xx answer for a key it has already seen).
 */

/** A new random key (UUID v4, which the backend requires). */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

export interface Keyed<T> {
  body: T
  idempotencyKey: string
}

/** POST /transfers: 201 with status COMPLETED or HELD_FOR_REVIEW. */
export function createTransfer({ body, idempotencyKey }: Keyed<TransferRequest>): Promise<Transfer> {
  return coreApi.post<Transfer>('/transfers', body, { idempotencyKey })
}

/** POST /topups: 201 with the Razorpay order ID and the public test key ID. */
export function createTopup({ body, idempotencyKey }: Keyed<{ amount: string }>): Promise<Topup> {
  return coreApi.post<Topup>('/topups', body, { idempotencyKey })
}

/** GET /topups/{id}: the owner only; anyone else gets 404. */
export function fetchTopup(id: string, signal?: AbortSignal): Promise<Topup> {
  return coreApi.get<Topup>(`/topups/${encodeURIComponent(id)}`, signal === undefined ? {} : { signal })
}

/** After money moved: balance, limits used and history are stale. */
export function useRefreshWallet() {
  const queryClient = useQueryClient()
  return useCallback(() => queryClient.invalidateQueries({ queryKey: walletKeys.all }), [queryClient])
}

export function useCreateTransfer() {
  const refresh = useRefreshWallet()
  return useMutation({
    mutationFn: createTransfer,
    onSuccess: async () => {
      await refresh()
    },
  })
}

export function useCreateTopup() {
  return useMutation({ mutationFn: createTopup })
}

