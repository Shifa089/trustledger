import { useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useHeldTransfers, useTransferDecision, type ReviewTransfer } from '@/api/compliance'
import { ErrorAlert } from '@/components/ErrorAlert'
import {
  EmptyState,
  Id,
  LoadingRows,
  PageHeader,
  Pager,
  tableClass,
  tdClass,
  thClass,
} from '@/components/staff'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/dates'
import { pageFrom } from '@/lib/staff'
import { formatRupees } from '@/lib/money'

type Action = 'approve' | 'reject'

/**
 * /compliance/transfers (FR-P-3): transfers at or above the high-value threshold wait here with their amount held.
 * Approve moves the money; reject releases the hold back to the sender. Each decision is confirmed first, and a
 * ref-based in-flight guard plus disabled buttons stop a double click deciding twice.
 */
export function HeldTransfersPage() {
  const [search, setSearch] = useSearchParams()
  const page = pageFrom(search)
  const queue = useHeldTransfers(page)
  const decision = useTransferDecision()
  const [confirming, setConfirming] = useState<{ id: string; action: Action } | undefined>()
  const [done, setDone] = useState<string | undefined>()
  const inFlight = useRef(false)

  const decide = async (transfer: ReviewTransfer, action: Action) => {
    if (inFlight.current) return
    inFlight.current = true
    setDone(undefined)
    try {
      await decision.mutateAsync({ id: transfer.id, action })
      setDone(
        action === 'approve'
          ? `Approved: ${formatRupees(transfer.amount)} sent to the receiver.`
          : `Rejected: ${formatRupees(transfer.amount)} released back to the sender.`,
      )
      setConfirming(undefined)
    } catch {
      // shown below
    } finally {
      inFlight.current = false
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Held transfers"
        intro="Transfers of ₹50,000 or more wait for a compliance decision. The amount is held from the sender's balance."
      />
      <p aria-live="polite" className="text-sm font-medium text-emerald-700 dark:text-emerald-400">
        {done}
      </p>
      {decision.isError && <ErrorAlert error={decision.error} title="The decision was not saved" />}

      <section aria-label="Held transfers" aria-busy={queue.isFetching} className="flex flex-col gap-4">
        {queue.isPending ? (
          <LoadingRows label="Loading held transfers" />
        ) : queue.isError ? (
          <ErrorAlert error={queue.error} title="Couldn't load held transfers" />
        ) : queue.data.items.length === 0 ? (
          <EmptyState>No transfers are waiting for review.</EmptyState>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className={tableClass}>
                <caption className="sr-only">Transfers held for review</caption>
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th scope="col" className={thClass}>Transfer</th>
                    <th scope="col" className={`${thClass} text-right`}>Amount</th>
                    <th scope="col" className={thClass}>From wallet</th>
                    <th scope="col" className={thClass}>To wallet</th>
                    <th scope="col" className={thClass}>Created</th>
                    <th scope="col" className={thClass}>Decision</th>
                  </tr>
                </thead>
                <tbody>
                  {queue.data.items.map((transfer) => {
                    const asking = confirming?.id === transfer.id ? confirming.action : undefined
                    const busy = decision.isPending && decision.variables.id === transfer.id
                    return (
                      <tr key={transfer.id} className="border-b last:border-0">
                        <td className={tdClass}>
                          <Id value={transfer.id} />
                        </td>
                        <td className={`${tdClass} text-right font-medium tabular-nums`}>
                          {formatRupees(transfer.amount)}
                        </td>
                        <td className={tdClass}>
                          <WalletLink id={transfer.senderWalletId} />
                        </td>
                        <td className={tdClass}>
                          <WalletLink id={transfer.receiverWalletId} />
                        </td>
                        <td className={tdClass}>{formatDateTime(transfer.createdAt)}</td>
                        <td className={tdClass}>
                          {asking === undefined ? (
                            <div className="flex gap-2">
                              <Button
                                size="sm"
                                disabled={decision.isPending}
                                onClick={() => {
                                  setConfirming({ id: transfer.id, action: 'approve' })
                                }}
                              >
                                Approve{' '}<span className="sr-only">transfer {transfer.id}</span>
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                disabled={decision.isPending}
                                onClick={() => {
                                  setConfirming({ id: transfer.id, action: 'reject' })
                                }}
                              >
                                Reject{' '}<span className="sr-only">transfer {transfer.id}</span>
                              </Button>
                            </div>
                          ) : (
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="text-xs">
                                {asking === 'approve' ? 'Send' : 'Release'} {formatRupees(transfer.amount)}?
                              </span>
                              <Button
                                size="sm"
                                variant={asking === 'reject' ? 'destructive' : 'default'}
                                disabled={decision.isPending}
                                onClick={() => {
                                  void decide(transfer, asking)
                                }}
                              >
                                {busy ? 'Saving…' : asking === 'approve' ? 'Confirm approve' : 'Confirm reject'}
                              </Button>
                              <Button
                                size="sm"
                                variant="ghost"
                                disabled={decision.isPending}
                                onClick={() => {
                                  setConfirming(undefined)
                                }}
                              >
                                Cancel
                              </Button>
                            </div>
                          )}
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
              noun="transfers"
              onPage={(next) => {
                setSearch(next > 0 ? { page: String(next + 1) } : {})
              }}
            />
          </>
        )}
      </section>
    </div>
  )
}

export function WalletLink({ id }: { id: string }) {
  return (
    <Link
      to={`/compliance/wallets/${id}`}
      className="font-mono text-xs text-primary underline-offset-4 hover:underline"
      title={id}
    >
      {id.slice(0, 8)}…{' '}<span className="sr-only">wallet {id}</span>
    </Link>
  )
}
