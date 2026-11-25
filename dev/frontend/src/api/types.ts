/** Response shapes of the endpoints the app calls (SPEC §5). Personal fields arrive masked (FR-PR-1). */

/** GET /api/v1/me (core-app MeController.MeResponse). */
export interface Me {
  id: string
  email: string | null
  phone: string | null
  fullName: string | null
  status: string
  roles: string[]
}

/** A page of results, as the paginated list endpoints return it. */
export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// --- KYC (SPEC §5, FR-KYC-1/2) ---

export type KycLevel = 'MIN' | 'FULL'
export type KycStatus = 'SUBMITTED' | 'VERIFIED' | 'APPROVED' | 'REJECTED'
export type DocumentType = 'PAN_CARD' | 'ADDRESS_PROOF' | 'PHOTO'
export const DOCUMENT_TYPES: readonly DocumentType[] = ['PAN_CARD', 'ADDRESS_PROOF', 'PHOTO']

/** A case as its owner sees it (KycCaseView): the PAN masked, no other personal data. */
export interface KycCase {
  id: string
  level: KycLevel
  status: KycStatus
  maskedPan: string | null
  rejectionReason: string | null
  createdAt: string
  updatedAt: string
}

/** GET /kyc/me: the highest approved level and every case, oldest first. */
export interface KycOverview {
  currentLevel: 'NONE' | KycLevel
  cases: KycCase[]
}

/** POST /kyc/min body. PAN upper case, mobile 10 digits, date as yyyy-MM-dd. */
export interface MinKycRequest {
  fullName: string
  dateOfBirth: string
  mobile: string
  pan: string
}

/** POST /kyc/full: the new case and the document types still to upload. */
export interface FullKycCase {
  id: string
  level: KycLevel
  status: KycStatus
  requiredDocuments: DocumentType[]
  createdAt: string
}

/** POST /kyc/cases/{id}/documents: metadata only (never the file name or storage key). */
export interface KycDocument {
  id: string
  type: DocumentType
  contentType: string
  sizeBytes: number
  sha256: string
  uploadedAt: string
}

// --- Wallet (FR-W-1/2) ---

export type WalletTier = 'MIN' | 'FULL'
export type WalletStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED'

/** Amounts are decimal rupee strings; null when the tier has no such limit. */
export interface WalletLimits {
  maxBalance: string
  monthlyLoad: string
  p2pAllowed: boolean
  dailyTransfer: string | null
  highValueThreshold: string | null
  monthlyTransfer: string | null
}

/** GET /wallets/me. */
export interface MyWallet {
  walletId: string
  tier: WalletTier
  status: WalletStatus
  currency: string
  balance: string
  held: string
  available: string
  limits: WalletLimits
}

/** GET /wallets/me/limit-usage: counted like the backend's limit checks (day and month in India). */
export interface LimitUsage {
  transferredToday: string
  transferredThisMonth: string
  addedThisMonth: string
}

// --- History (FR-S-1) ---

export type TransactionType = 'TOPUP' | 'TRANSFER_IN' | 'TRANSFER_OUT'
export const TRANSACTION_TYPES: readonly TransactionType[] = ['TOPUP', 'TRANSFER_IN', 'TRANSFER_OUT']

export interface Transaction {
  id: string
  type: TransactionType
  /** Transfer or top-up status, e.g. COMPLETED, HELD_FOR_REVIEW, CAPTURED. */
  status: string
  amount: string
  currency: string
  counterpartyWalletId: string | null
  note: string | null
  createdAt: string
}

/** GET /wallets/me/transactions in offset mode. */
export interface TransactionHistory {
  items: Transaction[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  nextCursor?: string | null
}

// --- Payments (FR-P-1..4) ---

export type TransferStatus = 'INITIATED' | 'HELD_FOR_REVIEW' | 'COMPLETED' | 'REJECTED'

/** POST /transfers body: exactly one of recipientMobile and recipientWalletId. Amount as a decimal string. */
export interface TransferRequest {
  recipientMobile?: string
  recipientWalletId?: string
  amount: string
  note?: string
}

/** POST /transfers (201) and GET /transfers/{id}. */
export interface Transfer {
  id: string
  status: TransferStatus
  senderWalletId: string
  receiverWalletId: string
  amount: string
  currency: string
  note: string | null
  createdAt: string
}

export type TopupStatus = 'CREATED' | 'PENDING' | 'CAPTURED' | 'PARKED' | 'FAILED' | 'EXPIRED'

/** POST /topups (201) and GET /topups/{id}: the Razorpay order and the public test key ID for Checkout. */
export interface Topup {
  id: string
  status: TopupStatus
  amount: string
  currency: string
  gatewayOrderId: string | null
  gatewayPaymentId: string | null
  keyId: string | null
  createdAt: string
  updatedAt: string
}

// --- Consent and privacy (FR-ID-3, FR-PR-2) ---

export type ConsentPurpose = 'TERMS' | 'PRIVACY' | 'MARKETING'

export interface ConsentItem {
  purpose: ConsentPurpose
  granted: boolean
  policyVersion: string
  capturedAt: string
}

/** GET/POST /me/consents. */
export interface ConsentOverview {
  requiredGiven: boolean
  consents: ConsentItem[]
  policyVersions: Partial<Record<ConsentPurpose, string>>
}

export type DataRequestType = 'EXPORT' | 'ERASURE'
export type DataRequestStatus = 'PENDING' | 'COMPLETED' | 'FAILED'

/** One export or erasure request; `download` only on a COMPLETED export (a short-lived presigned link). */
export interface DataRequest {
  id: string
  type: DataRequestType
  status: DataRequestStatus
  requestedAt: string
  completedAt: string | null
  download: { url: string; expiresAt: string } | null
}

/** GET /me/data-requests. */
export interface DataRequests {
  items: DataRequest[]
}
