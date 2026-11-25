import { CircleCheck, TriangleAlert } from 'lucide-react'
import { useRef } from 'react'
import { useRunReconciliation, type ReconciliationReport } from '@/api/operations'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Id, PageHeader, tableClass, tdClass, thClass } from '@/components/staff'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDateTime } from '@/lib/dates'
import { formatRupees } from '@/lib/money'

/**
 * /admin/reconciliation (FR-L-4): runs the nightly job now and shows the result. It compares each wallet's stored
 * balance with the balance derived from the ledger; any difference is listed (the server caps the list).
 */
export function ReconciliationPage() {
  const run = useRunReconciliation()
  const inFlight = useRef(false)

  const start = async () => {
    if (inFlight.current) return
    inFlight.current = true
    try {
      await run.mutateAsync()
    } catch {
      // shown below
    } finally {
      inFlight.current = false
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Reconciliation"
        intro="Check every wallet's stored balance against its ledger-derived balance. The run is recorded in the audit log."
      >
        <Button
          disabled={run.isPending}
          onClick={() => {
            void start()
          }}
        >
          {run.isPending ? 'Running…' : 'Run reconciliation'}
        </Button>
      </PageHeader>
      {run.isError && <ErrorAlert error={run.error} title="Reconciliation didn't run" />}
      {run.isSuccess && <ReportView report={run.data} />}
    </div>
  )
}

function seconds(report: ReconciliationReport): string {
  const ms = new Date(report.finishedAt).getTime() - new Date(report.startedAt).getTime()
  return `${(Math.max(ms, 0) / 1000).toFixed(1)} s`
}

function ReportView({ report }: { report: ReconciliationReport }) {
  const clean = report.mismatches === 0 && report.unlinked === 0
  return (
    <section aria-label="Reconciliation result" className="flex flex-col gap-6">
      {clean ? (
        <Alert>
          <CircleCheck aria-hidden className="text-emerald-600" />
          <AlertTitle>All balances match</AlertTitle>
          <AlertDescription>
            {report.walletsChecked} wallets checked: every stored balance equals its ledger balance.
          </AlertDescription>
        </Alert>
      ) : (
        <Alert variant="destructive">
          <TriangleAlert aria-hidden />
          <AlertTitle>Differences found</AlertTitle>
          <AlertDescription>
            {report.mismatches} balance mismatches and {report.unlinked} wallets without a ledger account. Investigate
            before the next run; corrections are reversal entries, never edits.
          </AlertDescription>
        </Alert>
      )}
      <Card>
        <CardHeader>
          <CardTitle>Run</CardTitle>
          <CardDescription>
            Started {formatDateTime(report.startedAt)}, took {seconds(report)}.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-3 gap-4 text-center">
            <Stat label="Wallets checked" value={report.walletsChecked} />
            <Stat label="Balance mismatches" value={report.mismatches} />
            <Stat label="Unlinked wallets" value={report.unlinked} />
          </dl>
        </CardContent>
      </Card>
      {report.listed.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Mismatches</CardTitle>
            {report.truncated && (
              <CardDescription>Only the first {report.listed.length} are listed; the counts above are complete.</CardDescription>
            )}
          </CardHeader>
          <CardContent className="overflow-x-auto">
            <table className={tableClass}>
              <caption className="sr-only">Wallets whose balance differs from the ledger</caption>
              <thead className="border-b text-muted-foreground">
                <tr>
                  <th scope="col" className={thClass}>Wallet</th>
                  <th scope="col" className={thClass}>Problem</th>
                  <th scope="col" className={`${thClass} text-right`}>Stored balance</th>
                  <th scope="col" className={`${thClass} text-right`}>Ledger balance</th>
                </tr>
              </thead>
              <tbody>
                {report.listed.map((row) => (
                  <tr key={row.walletId} className="border-b last:border-0">
                    <td className={tdClass}>
                      <Id value={row.walletId} />
                    </td>
                    <td className={tdClass}>{row.reason === 'BALANCE' ? 'Balance differs' : 'No ledger account'}</td>
                    <td className={`${tdClass} text-right tabular-nums`}>{formatRupees(row.cachedBalance)}</td>
                    <td className={`${tdClass} text-right tabular-nums`}>
                      {row.ledgerBalance === null ? '-' : signedRupees(row.ledgerBalance)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      )}
    </section>
  )
}

/** A ledger balance can be negative ("-12.50"); formatRupees takes unsigned decimal strings. */
function signedRupees(amount: string): string {
  return amount.startsWith('-') ? `−${formatRupees(amount.slice(1))}` : formatRupees(amount)
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-2xl font-semibold tabular-nums">{value}</dd>
    </div>
  )
}
