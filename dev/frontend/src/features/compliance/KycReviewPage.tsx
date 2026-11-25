import { CircleAlert, ShieldAlert } from 'lucide-react'
import { useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { useKycCase, useKycDecision, type KycDecision, type KycReviewCase } from '@/api/compliance'
import { isApiError } from '@/api/errors'
import { useMe } from '@/api/me'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Id, LoadingRows, PageHeader, ReasonForm, StatusBadge } from '@/components/staff'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDateTime } from '@/lib/dates'
import { DOCUMENT_LABELS } from '../kyc/documents'
import { DocumentViewer } from './DocumentViewer'

/** KycCase.MAX_REASON_LENGTH. */
export const MAX_KYC_REASON_LENGTH = 500

const DONE: Readonly<Record<KycDecision['action'], string>> = {
  verify: 'Case verified. A different officer must now approve it.',
  approve: 'Case approved. The customer is now FULL KYC.',
  reject: 'Case rejected. The customer can see the reason.',
}

/**
 * /compliance/kyc/:id (FR-KYC-3). Maker-checker is enforced in three places: this page's hint, the service
 * (409 MAKER_CHECKER_VIOLATION) and the database (a check that approved_by differs from verified_by). The Approve
 * button stays enabled for the maker on purpose: the hint explains, and the server is the authority.
 */
export function KycReviewPage() {
  const { id = '' } = useParams()
  const kycCase = useKycCase(id)
  const me = useMe()
  const decision = useKycDecision(id)
  const [viewing, setViewing] = useState<string | undefined>()
  const [rejecting, setRejecting] = useState(false)
  const [done, setDone] = useState<string | undefined>()
  const inFlight = useRef(false)

  const decide = async (next: KycDecision) => {
    if (inFlight.current) return
    inFlight.current = true
    setDone(undefined)
    try {
      await decision.mutateAsync(next)
      setDone(DONE[next.action])
      setRejecting(false)
    } finally {
      inFlight.current = false
    }
  }

  if (kycCase.isPending) {
    return <LoadingRows label="Loading the KYC case" />
  }
  if (kycCase.isError) {
    return <ErrorAlert error={kycCase.error} title="Couldn't load the KYC case" />
  }
  const c = kycCase.data
  const verifiedByMe = c.verifiedBy !== null && c.verifiedBy === me.data?.id
  const viewed = c.documents.find((document) => document.id === viewing)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="KYC review" intro={`Full-KYC case ${c.id}`}>
        <Link to="/compliance/kyc" className="text-sm text-primary underline-offset-4 hover:underline">
          Back to the queue
        </Link>
      </PageHeader>

      <div className="flex flex-wrap items-center gap-3">
        <span className="text-sm text-muted-foreground">Status</span>
        <span data-testid="case-status">
          <StatusBadge value={c.status} />
        </span>
      </div>

      {c.status === 'VERIFIED' && verifiedByMe && (
        <Alert>
          <ShieldAlert aria-hidden />
          <AlertTitle>You verified this case</AlertTitle>
          <AlertDescription>
            Maker-checker: a different compliance officer must approve it. You can still reject it.
          </AlertDescription>
        </Alert>
      )}

      <p aria-live="polite" className="text-sm font-medium text-emerald-700 dark:text-emerald-400">
        {done}
      </p>
      {decision.isError && <DecisionError error={decision.error} />}

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Case</CardTitle>
            <CardDescription>Who did each step (core user IDs).</CardDescription>
          </CardHeader>
          <CardContent>
            <Steps kycCase={c} myId={me.data?.id} />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Address</CardTitle>
            <CardDescription>Personal data: shown for review only.</CardDescription>
          </CardHeader>
          <CardContent>
            <p className="text-sm whitespace-pre-line">{c.address ?? '-'}</p>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Documents</CardTitle>
          <CardDescription>Each view gets a fresh link valid for 5 minutes, and is recorded in the audit log.</CardDescription>
        </CardHeader>
        <CardContent>
          {c.documents.length === 0 ? (
            <p className="text-sm text-muted-foreground">No documents uploaded.</p>
          ) : (
            <ul className="flex flex-col divide-y">
              {c.documents.map((document) => (
                <li key={document.id} className="flex items-center justify-between gap-4 py-2 text-sm">
                  <span>
                    {DOCUMENT_LABELS[document.type]}{' '}
                    <span className="text-muted-foreground">· uploaded {formatDateTime(document.uploadedAt)}</span>
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setViewing(document.id)
                    }}
                  >
                    View{' '}<span className="sr-only">{DOCUMENT_LABELS[document.type]}</span>
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
      {viewed !== undefined && (
        <DocumentViewer
          key={viewed.id}
          caseId={c.id}
          document={viewed}
          onClose={() => {
            setViewing(undefined)
          }}
        />
      )}

      {(c.status === 'SUBMITTED' || c.status === 'VERIFIED') && (
        <Card>
          <CardHeader>
            <CardTitle>Decision</CardTitle>
            <CardDescription>
              {c.status === 'SUBMITTED'
                ? 'Maker step: verify that the documents match the details, or reject with a reason.'
                : 'Checker step: approve (only an officer who did not verify), or reject with a reason.'}
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {rejecting ? (
              <ReasonForm
                id="reject-reason"
                label="Reason for rejection"
                hint="The customer sees this reason. Don't include other people's data."
                maxLength={MAX_KYC_REASON_LENGTH}
                submitLabel="Reject case"
                pendingLabel="Rejecting…"
                destructive
                pending={decision.isPending}
                onSubmit={(reason) => decide({ action: 'reject', reason })}
                onCancel={() => {
                  setRejecting(false)
                }}
              />
            ) : (
              <div className="flex flex-wrap gap-2">
                {c.status === 'SUBMITTED' ? (
                  <Button
                    disabled={decision.isPending}
                    onClick={() => {
                      void decide({ action: 'verify' }).catch(() => undefined)
                    }}
                  >
                    {decision.isPending ? 'Verifying…' : 'Verify'}
                  </Button>
                ) : (
                  <Button
                    disabled={decision.isPending}
                    onClick={() => {
                      void decide({ action: 'approve' }).catch(() => undefined)
                    }}
                  >
                    {decision.isPending ? 'Approving…' : 'Approve'}
                  </Button>
                )}
                <Button
                  variant="outline"
                  disabled={decision.isPending}
                  onClick={() => {
                    setRejecting(true)
                    setDone(undefined)
                    decision.reset()
                  }}
                >
                  Reject…
                </Button>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}

/** The maker-checker refusal gets its own clear alert; anything else the usual error. */
function DecisionError({ error }: { error: unknown }) {
  if (isApiError(error) && error.errorCode === 'MAKER_CHECKER_VIOLATION') {
    return (
      <Alert variant="destructive" data-testid="maker-checker-error">
        <CircleAlert aria-hidden />
        <AlertTitle>Maker-checker: approval refused</AlertTitle>
        <AlertDescription>
          <p>{error.message}</p>
          <p>Ask another compliance officer to approve this case. The case is unchanged.</p>
          {error.correlationId !== undefined && <p className="font-mono text-xs">Reference: {error.correlationId}</p>}
        </AlertDescription>
      </Alert>
    )
  }
  return <ErrorAlert error={error} title="The decision was not saved" />
}

function Steps({ kycCase: c, myId }: { kycCase: KycReviewCase; myId: string | undefined }) {
  const who = (id: string | null) =>
    id === null ? null : id === myId ? <span className="font-medium">you</span> : <Id value={id} />
  const row = (label: string, at: string | null, by: string | null) => (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd>
        {at === null ? (
          '-'
        ) : (
          <>
            {formatDateTime(at)}
            {by !== null && <> by {who(by)}</>}
          </>
        )}
      </dd>
    </>
  )
  return (
    <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
      <dt className="text-muted-foreground">Customer</dt>
      <dd>
        <Id value={c.userId} />
      </dd>
      {row('Submitted', c.createdAt, null)}
      {row('Verified', c.verifiedAt, c.verifiedBy)}
      {row('Approved', c.approvedAt, c.approvedBy)}
      {row('Rejected', c.rejectedAt, c.rejectedBy)}
      {c.rejectionReason !== null && (
        <>
          <dt className="text-muted-foreground">Reason</dt>
          <dd>{c.rejectionReason}</dd>
        </>
      )}
    </dl>
  )
}
