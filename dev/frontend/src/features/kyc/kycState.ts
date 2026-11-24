import type { KycCase, KycOverview } from '@/api/types'

/** What the customer should do next, from GET /kyc/me. */
export type KycNextStep =
  | { step: 'min' }
  | { step: 'full' }
  | { step: 'upload'; fullCase: KycCase }
  | { step: 'review'; fullCase: KycCase }
  | { step: 'rejected'; fullCase: KycCase }
  | { step: 'done' }

/** The newest full-KYC case, if any (cases come oldest first). */
export function latestFullCase(overview: KycOverview): KycCase | undefined {
  return overview.cases.filter((kycCase) => kycCase.level === 'FULL').at(-1)
}

export function nextStep(overview: KycOverview): KycNextStep {
  if (overview.currentLevel === 'FULL') {
    return { step: 'done' }
  }
  if (overview.currentLevel === 'NONE') {
    return { step: 'min' }
  }
  const fullCase = latestFullCase(overview)
  switch (fullCase?.status) {
    case undefined:
      return { step: 'full' }
    case 'SUBMITTED':
      return { step: 'upload', fullCase }
    case 'VERIFIED':
      return { step: 'review', fullCase }
    case 'REJECTED':
      return { step: 'rejected', fullCase }
    case 'APPROVED':
      return { step: 'done' }
  }
}

export const STATUS_TEXT: Readonly<Record<KycCase['status'], string>> = {
  SUBMITTED: 'Submitted',
  VERIFIED: 'Verified, awaiting approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
}
