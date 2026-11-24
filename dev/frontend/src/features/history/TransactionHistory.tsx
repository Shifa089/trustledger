import { useSearchParams } from 'react-router'
import { TRANSACTION_TYPES, type TransactionType } from '@/api/types'
import { isNoWallet, useTransactions } from '@/api/wallet'
import { ErrorAlert } from '@/components/ErrorAlert'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input, Select } from '@/components/ui/input'
import { todayInIndia } from '@/lib/dates'
import { nextSort, readHistoryParams, toHistoryQuery, writeHistoryParams, type HistoryParams } from './historyParams'
import { TransactionTable } from './TransactionTable'
import { TYPE_LABELS } from './transactions'

/**
 * /customer/history (FR-S-1): filters by date range and type, server-side paging and sorting. Everything is in the
 * URL; changing a filter or the sort goes back to page 1.
 */
export function TransactionHistory() {
  const [search, setSearch] = useSearchParams()
  const params = readHistoryParams(search)
  const rangeInvalid = params.from !== undefined && params.to !== undefined && params.from > params.to
  const history = useTransactions(toHistoryQuery(params), !rangeInvalid)
  const today = todayInIndia()

  const update = (change: Partial<HistoryParams>, keepPage = false) => {
    setSearch(writeHistoryParams({ ...params, ...change, ...(keepPage ? {} : { page: 0 }) }))
  }
  const filtered = params.from !== undefined || params.to !== undefined || params.type !== undefined

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Transaction history</h1>
        <p className="text-muted-foreground">Money added, sent and received. Dates are India time.</p>
      </div>
      <Card>
        <CardContent>
          <form
            aria-label="Filters"
            className="grid gap-4 sm:grid-cols-[1fr_1fr_1fr_auto] sm:items-end"
            onSubmit={(event) => {
              event.preventDefault()
            }}
          >
            <FormField id="from" label="From" error={rangeInvalid ? '"From" must not be after "To".' : undefined}>
              <Input
                type="date"
                max={today}
                value={params.from ?? ''}
                onChange={(event) => {
                  update({ from: event.target.value === '' ? undefined : event.target.value })
                }}
              />
            </FormField>
            <FormField id="to" label="To">
              <Input
                type="date"
                max={today}
                value={params.to ?? ''}
                onChange={(event) => {
                  update({ to: event.target.value === '' ? undefined : event.target.value })
                }}
              />
            </FormField>
            <FormField id="type" label="Type">
              <Select
                value={params.type ?? ''}
                onChange={(event) => {
                  const value = event.target.value
                  update({ type: value === '' ? undefined : (value as TransactionType) })
                }}
              >
                <option value="">All types</option>
                {TRANSACTION_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {TYPE_LABELS[type]}
                  </option>
                ))}
              </Select>
            </FormField>
            <Button
              type="button"
              variant="outline"
              disabled={!filtered}
              onClick={() => {
                update({ from: undefined, to: undefined, type: undefined })
              }}
            >
              Clear filters
            </Button>
          </form>
        </CardContent>
      </Card>

      <section aria-label="Transactions" aria-busy={history.isFetching} className="flex flex-col gap-4">
        {rangeInvalid ? null : history.isPending ? (
          <LoadingRows />
        ) : history.isError ? (
          isNoWallet(history.error) ? (
            <p className="text-sm">You don't have a wallet yet. Complete minimum KYC first.</p>
          ) : (
            <div className="flex flex-col items-start gap-3">
              <ErrorAlert error={history.error} title="Couldn't load your transactions" />
              <Button
                variant="outline"
                onClick={() => {
                  void history.refetch()
                }}
              >
                Try again
              </Button>
            </div>
          )
        ) : history.data.items.length === 0 ? (
          <p className="rounded-md border border-dashed p-8 text-center text-sm text-muted-foreground">
            {filtered ? 'No transactions match these filters.' : 'No transactions yet.'}
          </p>
        ) : (
          <>
            <TransactionTable
              transactions={history.data.items}
              caption="Transactions"
              sort={params.sort}
              onSort={(field) => {
                update({ sort: nextSort(params.sort, field) })
              }}
            />
            <nav aria-label="Pagination" className="flex items-center justify-between gap-4 text-sm">
              <Button
                variant="outline"
                disabled={params.page === 0}
                onClick={() => {
                  update({ page: params.page - 1 }, true)
                }}
              >
                Previous
              </Button>
              <span aria-live="polite">
                Page {Math.min(params.page + 1, Math.max(history.data.totalPages, 1))} of{' '}
                {Math.max(history.data.totalPages, 1)} · {history.data.totalElements} transactions
              </span>
              <Button
                variant="outline"
                disabled={params.page + 1 >= history.data.totalPages}
                onClick={() => {
                  update({ page: params.page + 1 }, true)
                }}
              >
                Next
              </Button>
            </nav>
          </>
        )}
      </section>
    </div>
  )
}

function LoadingRows() {
  return (
    <div role="status" aria-label="Loading transactions" className="flex flex-col gap-2">
      {Array.from({ length: 5 }, (_, index) => (
        <div key={index} className="h-9 animate-pulse rounded-md bg-muted" />
      ))}
    </div>
  )
}
