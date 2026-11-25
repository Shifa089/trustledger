import { useQuery } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import { amlApi, coreApi } from '@/api'
import type { ItemPage } from '@/api/compliance'
import { isApiError } from '@/api/errors'
import type { Page } from '@/api/types'
import { SectionHome } from '../SectionHome'

function count(query: UseQueryResult<{ totalElements: number }>) {
  if (query.isPending) return <span className="text-base text-muted-foreground">Loading…</span>
  if (query.isError) {
    return (
      <span className="text-sm font-normal text-destructive">
        {isApiError(query.error) ? query.error.message : "Couldn't load."}
      </span>
    )
  }
  return query.data.totalElements
}

/**
 * The officer's home: what is waiting in each queue. Officers use both services, so the AML count comes from
 * risk-aml-service through the second API client base URL.
 */
export function ComplianceHome() {
  const kyc = useQuery({
    queryKey: ['compliance', 'home', 'kyc'],
    queryFn: () => coreApi.get<ItemPage<unknown>>('/compliance/kyc-cases', { query: { status: 'SUBMITTED', size: 1 } }),
  })
  const verified = useQuery({
    queryKey: ['compliance', 'home', 'kyc-verified'],
    queryFn: () => coreApi.get<ItemPage<unknown>>('/compliance/kyc-cases', { query: { status: 'VERIFIED', size: 1 } }),
  })
  const held = useQuery({
    queryKey: ['compliance', 'home', 'held'],
    queryFn: () =>
      coreApi.get<ItemPage<unknown>>('/compliance/transfers', { query: { status: 'HELD_FOR_REVIEW', size: 1 } }),
  })
  const openCases = useQuery({
    queryKey: ['aml', 'cases', { status: 'OPEN', size: 1 }],
    queryFn: () => amlApi.get<Page<unknown>>('/aml/cases', { query: { status: 'OPEN', size: 1 } }),
  })

  return (
    <SectionHome
      title="Compliance"
      intro="KYC review with maker-checker, held transfers, wallet freezes and AML cases."
      links={[
        {
          to: '/compliance/kyc',
          label: 'KYC to verify',
          description: 'Submitted full-KYC cases waiting for the maker step.',
          value: count(kyc),
          testId: 'kyc-submitted',
        },
        {
          to: '/compliance/kyc?status=VERIFIED',
          label: 'KYC to approve',
          description: 'Verified cases waiting for a different officer (checker).',
          value: count(verified),
          testId: 'kyc-verified',
        },
        {
          to: '/compliance/transfers',
          label: 'Held transfers',
          description: 'High-value transfers waiting for a decision.',
          value: count(held),
          testId: 'held-transfers',
        },
        {
          to: '/compliance/aml/cases',
          label: 'Open AML cases',
          description: 'From risk-aml-service: cases nobody has picked up yet.',
          value: count(openCases),
          testId: 'open-cases',
        },
        {
          to: '/compliance/wallets',
          label: 'Wallets',
          description: 'Look up a wallet, freeze or unfreeze it with a reason.',
        },
      ]}
    />
  )
}
