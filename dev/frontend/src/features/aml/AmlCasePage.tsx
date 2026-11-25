import { Download } from 'lucide-react'
import { useRef, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { Link, useParams } from 'react-router'
import { downloadStrReport, MAX_NOTES_LENGTH, useCase, useCaseAction, type CaseAction, type CaseDetail } from '@/api/aml'
import { useComplianceWallet } from '@/api/compliance'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Id, LoadingRows, PageHeader, ReasonForm, StatusBadge } from '@/components/staff'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDateTime } from '@/lib/dates'
import { saveBlob } from '@/lib/staff'
import { cn } from '@/lib/utils'
import { WalletLink } from '../compliance/HeldTransfersPage'
import { buildTimeline, ruleName } from './timeline'

const DONE: Readonly<Record<CaseAction['action'], string>> = {
  assign: 'The case is assigned to you and now under investigation.',
  close: 'Case closed as a false positive.',
  escalate: 'Case escalated. The STR report is ready to download.',
}

/**
 * /compliance/aml/cases/:id (FR-AML-2/3/4): the alerts, a timeline of the wallet's activity, the wallet's live status
 * from core-app (an AML freeze shows here), and the workflow OPEN → INVESTIGATING → CLOSED_FALSE_POSITIVE or
 * ESCALATED. Only the assigned officer finishes a case; the AML service knows officers by their Keycloak subject.
 */
export function AmlCasePage() {
  const { id = '' } = useParams()
  const amlCase = useCase(id)
  const auth = useAuth()
  const mySubject = auth.user?.profile.sub

  if (amlCase.isPending) return <LoadingRows label="Loading the AML case" />
  if (amlCase.isError) return <ErrorAlert error={amlCase.error} title="Couldn't load the AML case" />
  const c = amlCase.data

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="AML case" intro={`Case ${c.id}`}>
        <Link to="/compliance/aml/cases" className="text-sm text-primary underline-offset-4 hover:underline">
          All cases
        </Link>
      </PageHeader>
      <div className="grid gap-6 lg:grid-cols-2">
        <CaseSummaryCard amlCase={c} mySubject={mySubject} />
        <LinkedWalletCard walletId={c.walletId} />
      </div>
      <CaseActions amlCase={c} mySubject={mySubject} />
      <Card>
        <CardHeader>
          <CardTitle>Alerts ({c.alerts.length})</CardTitle>
        </CardHeader>
        <CardContent>
          <ul className="flex flex-col divide-y text-sm">
            {c.alerts.map((alert) => (
              <li key={alert.id} className="flex flex-wrap items-center gap-3 py-2">
                <StatusBadge value={alert.severity} />
                <span className="font-medium">{ruleName(alert.ruleCode)}</span>
                <span className="text-muted-foreground">{formatDateTime(alert.createdAt)}</span>
                {alert.details !== null && <span className="text-muted-foreground">{alert.details}</span>}
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>Timeline</CardTitle>
          <CardDescription>The wallet's activity the rules looked at, the alerts and any freeze, oldest first.</CardDescription>
        </CardHeader>
        <CardContent>
          <ol aria-label="Case timeline" className="relative flex flex-col gap-3 border-l pl-5">
            {buildTimeline(c).map((entry) => (
              <li key={entry.key} className="relative text-sm">
                <span
                  aria-hidden
                  className={cn(
                    'absolute top-1.5 -left-[25px] size-2.5 rounded-full',
                    entry.kind === 'fact'
                      ? 'bg-muted-foreground'
                      : entry.kind === 'alert'
                        ? entry.severity === 'HIGH'
                          ? 'bg-destructive'
                          : 'bg-amber-500'
                        : 'bg-primary',
                  )}
                />
                <p className="font-medium">{entry.title}</p>
                <p className="text-muted-foreground">
                  {formatDateTime(entry.at)}
                  {entry.detail !== undefined && <> · {entry.detail}</>}
                </p>
              </li>
            ))}
          </ol>
        </CardContent>
      </Card>
    </div>
  )
}

function CaseSummaryCard({ amlCase: c, mySubject }: { amlCase: CaseDetail; mySubject: string | undefined }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-3">
          Case{' '}
          <span data-testid="case-status">
            <StatusBadge value={c.status} />
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
          <dt className="text-muted-foreground">Wallet</dt>
          <dd>
            <WalletLink id={c.walletId} />
          </dd>
          <dt className="text-muted-foreground">Assigned to</dt>
          <dd data-testid="assignee">
            {c.assignedTo === null ? 'Nobody yet' : c.assignedTo === mySubject ? 'You' : <Id value={c.assignedTo} />}
          </dd>
          <dt className="text-muted-foreground">Opened</dt>
          <dd>{formatDateTime(c.createdAt)}</dd>
          <dt className="text-muted-foreground">Last activity</dt>
          <dd>{formatDateTime(c.updatedAt)}</dd>
          {c.resolution !== null && (
            <>
              <dt className="text-muted-foreground">Notes</dt>
              <dd className="whitespace-pre-line">{c.resolution}</dd>
            </>
          )}
        </dl>
      </CardContent>
    </Card>
  )
}

/** The wallet's status in core-app: shows whether the AML freeze request actually froze it. */
function LinkedWalletCard({ walletId }: { walletId: string }) {
  const wallet = useComplianceWallet(walletId)
  return (
    <Card>
      <CardHeader>
        <CardTitle>Wallet status (core-app)</CardTitle>
        <CardDescription>
          <Link to={`/compliance/wallets/${walletId}`} className="text-primary underline-offset-4 hover:underline">
            Open the wallet to freeze or unfreeze it
          </Link>
        </CardDescription>
      </CardHeader>
      <CardContent className="text-sm">
        {wallet.isPending && <p className="text-muted-foreground">Loading…</p>}
        {wallet.isError && <ErrorAlert error={wallet.error} title="Couldn't load the wallet" />}
        {wallet.isSuccess && (
          <p className="flex flex-wrap items-center gap-2" data-testid="linked-wallet-status">
            <StatusBadge value={wallet.data.status} />
            {wallet.data.currentFreeze !== null && (
              <span>
                {wallet.data.currentFreeze.source === 'AML' ? 'Frozen by the AML service' : 'Frozen by an officer'} on{' '}
                {formatDateTime(wallet.data.currentFreeze.frozenAt)}
              </span>
            )}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

function CaseActions({ amlCase: c, mySubject }: { amlCase: CaseDetail; mySubject: string | undefined }) {
  const action = useCaseAction(c.id)
  const [finishing, setFinishing] = useState<'close' | 'escalate' | undefined>()
  const [done, setDone] = useState<string | undefined>()
  const [downloadError, setDownloadError] = useState<unknown>()
  const [downloading, setDownloading] = useState(false)
  const inFlight = useRef(false)

  const run = async (next: CaseAction) => {
    if (inFlight.current) return
    inFlight.current = true
    setDone(undefined)
    try {
      await action.mutateAsync(next)
      setDone(DONE[next.action])
      setFinishing(undefined)
    } finally {
      inFlight.current = false
    }
  }

  const download = async () => {
    setDownloading(true)
    setDownloadError(undefined)
    try {
      const { blob, fileName } = await downloadStrReport(c.id)
      saveBlob(blob, fileName ?? `str-${c.id}.csv`)
    } catch (error) {
      setDownloadError(error)
    } finally {
      setDownloading(false)
    }
  }

  const active = c.status === 'OPEN' || c.status === 'INVESTIGATING'
  const mine = c.assignedTo !== null && c.assignedTo === mySubject

  return (
    <Card>
      <CardHeader>
        <CardTitle>Workflow</CardTitle>
        <CardDescription>
          {c.status === 'OPEN' && 'Assign the case to yourself to start the investigation.'}
          {c.status === 'INVESTIGATING' &&
            (mine
              ? 'Close it as a false positive, or escalate it and file a suspicious transaction report (STR). Notes are required.'
              : 'Another officer is investigating. Only the assigned officer can close or escalate it; you can take it over.')}
          {c.status === 'CLOSED_FALSE_POSITIVE' && 'Closed as a false positive. New alerts for this wallet open a new case.'}
          {c.status === 'ESCALATED' && 'Escalated: an STR is filed for this case.'}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p aria-live="polite" className="text-sm font-medium text-emerald-700 dark:text-emerald-400">
          {done}
        </p>
        {action.isError && <ErrorAlert error={action.error} title="The case was not updated" />}
        {downloadError !== undefined && <ErrorAlert error={downloadError} title="Couldn't download the STR report" />}

        {finishing !== undefined ? (
          <ReasonForm
            id={`${finishing}-notes`}
            label={finishing === 'close' ? 'Why is this a false positive?' : 'Grounds for the STR'}
            hint="Saved as the case resolution. Never sent to the customer."
            maxLength={MAX_NOTES_LENGTH}
            submitLabel={finishing === 'close' ? 'Close as false positive' : 'Escalate and file STR'}
            pendingLabel="Saving…"
            destructive={finishing === 'escalate'}
            pending={action.isPending}
            onSubmit={(notes) => run({ action: finishing, notes })}
            onCancel={() => {
              setFinishing(undefined)
            }}
          />
        ) : (
          <div className="flex flex-wrap gap-2">
            {active && !mine && (
              <Button
                disabled={action.isPending}
                onClick={() => {
                  void run({ action: 'assign' }).catch(() => undefined)
                }}
              >
                {action.isPending ? 'Assigning…' : c.status === 'OPEN' ? 'Assign to me' : 'Take over'}
              </Button>
            )}
            {c.status === 'INVESTIGATING' && mine && (
              <>
                <Button
                  variant="outline"
                  onClick={() => {
                    setFinishing('close')
                    action.reset()
                  }}
                >
                  Close as false positive…
                </Button>
                <Button
                  variant="destructive"
                  onClick={() => {
                    setFinishing('escalate')
                    action.reset()
                  }}
                >
                  Escalate (STR)…
                </Button>
              </>
            )}
            {c.status === 'ESCALATED' && (
              <Button
                variant="outline"
                disabled={downloading}
                onClick={() => {
                  void download()
                }}
              >
                <Download aria-hidden />
                {downloading ? 'Downloading…' : 'Download STR (CSV)'}
              </Button>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
