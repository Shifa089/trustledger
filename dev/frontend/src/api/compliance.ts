import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { coreApi } from './index'
import type { DocumentType, KycLevel, KycStatus } from './types'

/**
 * The compliance officer's core-app endpoints (SPEC §5): KYC review with maker-checker (FR-KYC-3), held transfers
 * (FR-P-3) and wallet freeze/unfreeze (FR-W-3). The server enforces every rule; the UI only explains it.
 */

/** Paginated lists from core-app's review endpoints use `items` (the AML service uses `content`). */
export interface ItemPage<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export const STAFF_PAGE_SIZE = 20

// --- KYC review ---

export const KYC_REVIEW_STATUSES: readonly KycStatus[] = ['SUBMITTED', 'VERIFIED', 'APPROVED', 'REJECTED']

export interface KycDocumentMeta {
  id: string
  type: DocumentType
  contentType: string
  sizeBytes: number
  sha256: string
  uploadedAt: string
}

/** A queue row: no personal data (SPEC §5). */
export interface KycQueueItem {
  id: string
  userId: string
  status: KycStatus
  documents: KycDocumentMeta[]
  verifiedBy: string | null
  createdAt: string
  updatedAt: string
}

/** The review page: the address, the document metadata, and who did each step (core user IDs). */
export interface KycReviewCase {
  id: string
  userId: string
  level: KycLevel
  status: KycStatus
  address: string | null
  documents: KycDocumentMeta[]
  verifiedBy: string | null
  verifiedAt: string | null
  approvedBy: string | null
  approvedAt: string | null
  rejectedBy: string | null
  rejectedAt: string | null
  rejectionReason: string | null
  createdAt: string
  updatedAt: string
}

/** A presigned S3 GET, valid 5 minutes. */
export interface DocumentLink {
  url: string
  expiresAt: string
}

export const complianceKeys = {
  kycQueueAll: ['compliance', 'kyc', 'queue'] as const,
  kycQueue: (status: KycStatus, page: number) => ['compliance', 'kyc', 'queue', status, page] as const,
  kycCase: (id: string) => ['compliance', 'kyc', 'case', id] as const,
  document: (caseId: string, docId: string) => ['compliance', 'kyc', 'document', caseId, docId] as const,
  transfersAll: ['compliance', 'transfers'] as const,
  transfers: (page: number) => ['compliance', 'transfers', page] as const,
  wallet: (id: string) => ['compliance', 'wallet', id] as const,
}

export function useKycQueue(status: KycStatus, page: number) {
  return useQuery({
    queryKey: complianceKeys.kycQueue(status, page),
    queryFn: () =>
      coreApi.get<ItemPage<KycQueueItem>>('/compliance/kyc-cases', {
        query: { status, page, size: STAFF_PAGE_SIZE },
      }),
    placeholderData: keepPreviousData,
  })
}

export function useKycCase(id: string) {
  return useQuery({
    queryKey: complianceKeys.kycCase(id),
    queryFn: () => coreApi.get<KycReviewCase>(`/compliance/kyc-cases/${encodeURIComponent(id)}`),
  })
}

/**
 * A fresh presigned link each time a document is opened, never cached (staleTime 0, gcTime 0): it expires after
 * 5 minutes, and every request is an audited document view (KYC_DOCUMENT_VIEW).
 */
export function useDocumentLink(caseId: string, docId: string | undefined) {
  return useQuery({
    queryKey: complianceKeys.document(caseId, docId ?? ''),
    queryFn: () =>
      coreApi.get<DocumentLink>(
        `/compliance/kyc-cases/${encodeURIComponent(caseId)}/documents/${encodeURIComponent(docId ?? '')}`,
      ),
    enabled: docId !== undefined,
    staleTime: 0,
    gcTime: 0,
    refetchOnWindowFocus: false,
  })
}

export type KycDecision = { action: 'verify' } | { action: 'approve' } | { action: 'reject'; reason: string }

export function useKycDecision(caseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (decision: KycDecision) =>
      coreApi.post<KycReviewCase>(
        `/compliance/kyc-cases/${encodeURIComponent(caseId)}/${decision.action}`,
        decision.action === 'reject' ? { reason: decision.reason } : undefined,
      ),
    onSuccess: (updated) => {
      queryClient.setQueryData(complianceKeys.kycCase(caseId), updated)
      void queryClient.invalidateQueries({ queryKey: complianceKeys.kycQueueAll })
    },
    onError: () => {
      // A 409 can mean someone else moved the case: show its real state.
      void queryClient.invalidateQueries({ queryKey: complianceKeys.kycCase(caseId) })
    },
  })
}

// --- Held transfers (FR-P-3) ---

export interface ReviewTransfer {
  id: string
  status: string
  senderWalletId: string
  receiverWalletId: string
  amount: string
  currency: string
  createdAt: string
  reviewedBy: string | null
  reviewedAt: string | null
}

export function useHeldTransfers(page: number) {
  return useQuery({
    queryKey: complianceKeys.transfers(page),
    queryFn: () =>
      coreApi.get<ItemPage<ReviewTransfer>>('/compliance/transfers', {
        query: { status: 'HELD_FOR_REVIEW', page, size: STAFF_PAGE_SIZE },
      }),
    placeholderData: keepPreviousData,
  })
}

export function useTransferDecision() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'approve' | 'reject' }) =>
      coreApi.post<ReviewTransfer>(`/compliance/transfers/${encodeURIComponent(id)}/${action}`),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: complianceKeys.transfersAll })
    },
  })
}

// --- Wallets (FR-W-3, FR-AML-3) ---

export type WalletStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED'

export interface WalletFreezeInfo {
  freezeId: string
  source: 'COMPLIANCE' | 'AML'
  reason: string
  frozenBy: string | null
  frozenAt: string
}

export interface ComplianceWallet {
  walletId: string
  userId: string
  tier: 'MIN' | 'FULL'
  status: WalletStatus
  balance: string
  held: string
  available: string
  currentFreeze: WalletFreezeInfo | null
}

/** GET /compliance/wallets/{id} (Day 32): the wallet's real status, including a freeze the AML service asked for. */
export function useComplianceWallet(id: string | undefined) {
  return useQuery({
    queryKey: complianceKeys.wallet(id ?? ''),
    queryFn: () => coreApi.get<ComplianceWallet>(`/compliance/wallets/${encodeURIComponent(id ?? '')}`),
    enabled: id !== undefined,
  })
}

export function useWalletDecision(walletId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ action, reason }: { action: 'freeze' | 'unfreeze'; reason: string }) =>
      coreApi.post<ComplianceWallet>(`/compliance/wallets/${encodeURIComponent(walletId)}/${action}`, { reason }),
    onSuccess: (updated) => {
      queryClient.setQueryData(complianceKeys.wallet(walletId), updated)
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: complianceKeys.wallet(walletId) })
    },
  })
}
