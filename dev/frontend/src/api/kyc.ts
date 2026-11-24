import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { coreApi } from './index'
import type { UploadOptions } from './client'
import type { DocumentType, FullKycCase, KycDocument, KycOverview, MinKycRequest } from './types'
import { walletKeys } from './wallet'

/** Customer KYC (SPEC §5): the caller is always the token's user, never a parameter. */
export const kycKeys = {
  me: ['kyc', 'me'] as const,
}

export function useKycOverview() {
  return useQuery({ queryKey: kycKeys.me, queryFn: () => coreApi.get<KycOverview>('/kyc/me') })
}

/** POST /kyc/min. Valid data makes the user MIN at once; the wallet follows asynchronously (FR-W-1). */
export function useSubmitMinKyc() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: MinKycRequest) => coreApi.post<unknown>('/kyc/min', body),
    onSuccess: async () => {
      // Mark stale, don't refetch now: the min-KYC page redirects anyone already at MIN, and a refetch here would
      // send the user to the KYC page before the form can take them to the dashboard. The next screen refetches.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: kycKeys.me, refetchType: 'none' }),
        queryClient.invalidateQueries({ queryKey: walletKeys.all, refetchType: 'none' }),
      ])
    },
  })
}

/** POST /kyc/full: the address; the answer names the documents to upload next. */
export function useSubmitFullKyc() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (address: string) => coreApi.post<FullKycCase>('/kyc/full', { address }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: kycKeys.me })
    },
  })
}

/** POST /kyc/cases/{id}/documents, multipart `file` + `type`, with upload progress. */
export function uploadKycDocument(
  caseId: string,
  type: DocumentType,
  file: File,
  options?: UploadOptions,
): Promise<KycDocument> {
  const form = new FormData()
  form.append('type', type)
  form.append('file', file)
  return coreApi.upload<KycDocument>(`/kyc/cases/${encodeURIComponent(caseId)}/documents`, form, options)
}
