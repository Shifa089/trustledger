import { useQuery } from '@tanstack/react-query'
import { amlApi } from '@/api'
import type { Page } from '@/api/types'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { SectionHome } from '../SectionHome'

/** Officers use both services: this page also calls the AML service, through the second API client base URL. */
export function ComplianceHome() {
  const openCases = useQuery({
    queryKey: ['aml', 'cases', { status: 'OPEN', size: 1 }],
    queryFn: () => amlApi.get<Page<unknown>>('/aml/cases', { query: { status: 'OPEN', size: 1 } }),
  })

  return (
    <SectionHome
      title="Compliance"
      intro="KYC review with maker-checker, held transfers, wallet freezes and AML cases."
      upcoming={[
        'KYC queue and review (verify, approve, reject, document viewer)',
        'Held transfers queue; wallet freeze and unfreeze',
        'AML alerts and cases, STR CSV download',
      ]}
    >
      <Card>
        <CardHeader>
          <CardTitle>Open AML cases</CardTitle>
          <CardDescription>From risk-aml-service GET /api/v1/aml/cases.</CardDescription>
        </CardHeader>
        <CardContent>
          {openCases.isPending && <p className="text-sm text-muted-foreground">Loading…</p>}
          {openCases.isError && <ErrorAlert error={openCases.error} title="Couldn't load AML cases" />}
          {openCases.isSuccess && (
            <p className="text-3xl font-semibold" data-testid="open-cases">
              {openCases.data.totalElements}
            </p>
          )}
        </CardContent>
      </Card>
    </SectionHome>
  )
}
