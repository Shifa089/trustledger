import { Link, useSearchParams } from 'react-router'
import { KYC_REVIEW_STATUSES, useKycQueue } from '@/api/compliance'
import { useMe } from '@/api/me'
import type { KycStatus } from '@/api/types'
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
import { cn } from '@/lib/utils'

function statusFrom(search: URLSearchParams): KycStatus {
  const value = search.get('status')
  return KYC_REVIEW_STATUSES.find((status) => status === value) ?? 'SUBMITTED'
}

/**
 * /compliance/kyc: the full-KYC queue, oldest first (SPEC §5). Status and page live in the URL. No personal data
 * here: the review page shows the address only once an officer opens a case.
 */
export function KycQueuePage() {
  const [search, setSearch] = useSearchParams()
  const status = statusFrom(search)
  const page = pageFrom(search)
  const queue = useKycQueue(status, page)
  const me = useMe()

  const go = (nextStatus: KycStatus, nextPage: number) => {
    const params = new URLSearchParams()
    if (nextStatus !== 'SUBMITTED') params.set('status', nextStatus)
    if (nextPage > 0) params.set('page', String(nextPage + 1))
    setSearch(params)
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="KYC queue"
        intro="Full-KYC cases, oldest first. One officer verifies, a different officer approves (maker-checker)."
      />
      <div role="tablist" aria-label="Case status" className="flex flex-wrap gap-2">
        {KYC_REVIEW_STATUSES.map((value) => (
          <Button
            key={value}
            role="tab"
            aria-selected={value === status}
            variant={value === status ? 'default' : 'outline'}
            size="sm"
            onClick={() => {
              go(value, 0)
            }}
          >
            {value.charAt(0) + value.slice(1).toLowerCase()}
          </Button>
        ))}
      </div>

      <section aria-label="KYC cases" aria-busy={queue.isFetching} className="flex flex-col gap-4">
        {queue.isPending ? (
          <LoadingRows label="Loading KYC cases" />
        ) : queue.isError ? (
          <ErrorAlert error={queue.error} title="Couldn't load the KYC queue" />
        ) : queue.data.items.length === 0 ? (
          <EmptyState>No {status.toLowerCase()} cases.</EmptyState>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className={tableClass}>
                <caption className="sr-only">KYC cases with status {status}</caption>
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th scope="col" className={thClass}>Case</th>
                    <th scope="col" className={thClass}>Status</th>
                    <th scope="col" className={thClass}>Documents</th>
                    <th scope="col" className={thClass}>Submitted</th>
                    <th scope="col" className={thClass}>Updated</th>
                    <th scope="col" className={thClass}>
                      <span className="sr-only">Open</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {queue.data.items.map((item) => {
                    const mine = item.verifiedBy !== null && item.verifiedBy === me.data?.id
                    return (
                      <tr key={item.id} className="border-b last:border-0">
                        <td className={tdClass}>
                          <Id value={item.id} />
                        </td>
                        <td className={cn(tdClass, 'flex flex-wrap gap-1')}>
                          <StatusBadge value={item.status} />
                          {mine && <span className="text-xs text-muted-foreground">verified by you</span>}
                        </td>
                        <td className={tdClass}>{item.documents.length}</td>
                        <td className={tdClass}>{formatDateTime(item.createdAt)}</td>
                        <td className={tdClass}>{formatDateTime(item.updatedAt)}</td>
                        <td className={tdClass}>
                          <Link className="text-primary underline-offset-4 hover:underline" to={`/compliance/kyc/${item.id}`}>
                            Review{' '}<span className="sr-only">case {item.id}</span>
                          </Link>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            <Pager
              page={page}
              totalPages={queue.data.totalPages}
              totalElements={queue.data.totalElements}
              noun="cases"
              onPage={(next) => {
                go(status, next)
              }}
            />
          </>
        )}
      </section>
    </div>
  )
}
