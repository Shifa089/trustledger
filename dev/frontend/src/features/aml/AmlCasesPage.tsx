import { useAuth } from 'react-oidc-context'
import { Link, useSearchParams } from 'react-router'
import { CASE_STATUSES, useCases, type CaseStatus } from '@/api/aml'
import { ErrorAlert } from '@/components/ErrorAlert'
import {
  EmptyState,
  Id,
  LoadingRows,
  PageHeader,
  Pager,
  StatusBadge,
  tableClass,
  tdClass,
  thClass,
} from '@/components/staff'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/dates'
import { pageFrom } from '@/lib/staff'
import { WalletLink } from '../compliance/HeldTransfersPage'

/** /compliance/aml/cases (FR-AML-2/4): one case per wallet investigation, filtered by status (open ones by default). */
export function AmlCasesPage() {
  const [search, setSearch] = useSearchParams()
  const raw = search.get('status')
  const status: CaseStatus | undefined = raw === 'ALL' ? undefined : (CASE_STATUSES.find((s) => s === raw) ?? 'OPEN')
  const page = pageFrom(search)
  const cases = useCases({ status, page })
  const mySubject = useAuth().user?.profile.sub

  const go = (nextStatus: string, nextPage: number) => {
    const params = new URLSearchParams()
    if (nextStatus !== 'OPEN') params.set('status', nextStatus)
    if (nextPage > 0) params.set('page', String(nextPage + 1))
    setSearch(params)
  }
  const tabs: readonly string[] = [...CASE_STATUSES, 'ALL']

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="AML cases" intro="Open → investigating → closed as a false positive, or escalated with an STR.">
        <Link to="/compliance/aml" className="text-sm text-primary underline-offset-4 hover:underline">
          View alerts
        </Link>
      </PageHeader>
      <div role="tablist" aria-label="Case status" className="flex flex-wrap gap-2">
        {tabs.map((value) => {
          const selected = (status ?? 'ALL') === value
          return (
            <Button
              key={value}
              role="tab"
              aria-selected={selected}
              size="sm"
              variant={selected ? 'default' : 'outline'}
              onClick={() => {
                go(value, 0)
              }}
            >
              {value === 'ALL' ? 'All' : value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ')}
            </Button>
          )
        })}
      </div>
      <section aria-label="Cases" aria-busy={cases.isFetching} className="flex flex-col gap-4">
        {cases.isPending ? (
          <LoadingRows label="Loading cases" />
        ) : cases.isError ? (
          <ErrorAlert error={cases.error} title="Couldn't load AML cases" />
        ) : cases.data.content.length === 0 ? (
          <EmptyState>No cases here.</EmptyState>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className={tableClass}>
                <caption className="sr-only">AML cases</caption>
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th scope="col" className={thClass}>Case</th>
                    <th scope="col" className={thClass}>Status</th>
                    <th scope="col" className={thClass}>Highest severity</th>
                    <th scope="col" className={thClass}>Alerts</th>
                    <th scope="col" className={thClass}>Wallet</th>
                    <th scope="col" className={thClass}>Assigned to</th>
                    <th scope="col" className={thClass}>Last activity</th>
                  </tr>
                </thead>
                <tbody>
                  {cases.data.content.map((c) => (
                    <tr key={c.id} className="border-b last:border-0">
                      <td className={tdClass}>
                        <Link
                          to={`/compliance/aml/cases/${c.id}`}
                          className="font-mono text-xs text-primary underline-offset-4 hover:underline"
                          title={c.id}
                        >
                          {c.id.slice(0, 8)}…{' '}<span className="sr-only">open case {c.id}</span>
                        </Link>
                      </td>
                      <td className={tdClass}>
                        <StatusBadge value={c.status} />
                      </td>
                      <td className={tdClass}>{c.highestSeverity === null ? '-' : <StatusBadge value={c.highestSeverity} />}</td>
                      <td className={tdClass}>{c.alertCount}</td>
                      <td className={tdClass}>
                        <WalletLink id={c.walletId} />
                      </td>
                      <td className={tdClass}>
                        {c.assignedTo === null ? '-' : c.assignedTo === mySubject ? 'You' : <Id value={c.assignedTo} />}
                      </td>
                      <td className={tdClass}>{formatDateTime(c.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager
              page={page}
              totalPages={cases.data.totalPages}
              totalElements={cases.data.totalElements}
              noun="cases"
              onPage={(next) => {
                go(status ?? 'ALL', next)
              }}
            />
          </>
        )}
      </section>
    </div>
  )
}
