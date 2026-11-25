import { Download, ShieldAlert } from 'lucide-react'
import { useState } from 'react'
import { useAuth } from 'react-oidc-context'
import {
  useConsents,
  useDataRequests,
  useRecordConsent,
  useRequestErasure,
  useRequestExport,
} from '@/api/privacy'
import type { ConsentItem, ConsentOverview, DataRequest } from '@/api/types'
import { ErrorAlert } from '@/components/ErrorAlert'
import { FormField } from '@/components/FormField'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { formatDateTime } from '@/lib/dates'

/** What the customer types to unlock erasure: deliberate, and the same for everyone. */
export const ERASE_PHRASE = 'ERASE'

/** /customer/privacy: marketing consent (FR-ID-3), data export and erasure (FR-PR-2). */
export function PrivacyPage({ pollMs }: { pollMs?: number }) {
  const requests = useDataRequests(pollMs)
  const erased = requests.data?.items.some((r) => r.type === 'ERASURE' && r.status === 'COMPLETED') === true
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Privacy</h1>
        <p className="text-muted-foreground">Your consents, a copy of your data, and erasure.</p>
      </div>
      {erased ? (
        <ErasedCard />
      ) : (
        <>
          <ConsentCard />
          <ExportCard requests={requests} />
          <ErasureCard />
        </>
      )}
    </div>
  )
}

function ConsentCard() {
  const consents = useConsents()
  return (
    <Card className="max-w-2xl">
      <CardHeader>
        <CardTitle>Consents</CardTitle>
        <CardDescription>
          Terms and the privacy policy are needed to use TrustLedger. Marketing is optional and can be withdrawn at any
          time.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {consents.isPending && <p className="text-sm text-muted-foreground">Loading your consents…</p>}
        {consents.isError && <ErrorAlert error={consents.error} title="Couldn't load your consents" />}
        {consents.isSuccess && <ConsentList overview={consents.data} />}
      </CardContent>
    </Card>
  )
}

function ConsentList({ overview }: { overview: ConsentOverview }) {
  const record = useRecordConsent()
  const find = (purpose: ConsentItem['purpose']) => overview.consents.find((c) => c.purpose === purpose)
  const marketing = find('MARKETING')
  const marketingVersion = overview.policyVersions.MARKETING
  return (
    <>
      <ul className="flex flex-col gap-2 text-sm">
        {(['TERMS', 'PRIVACY'] as const).map((purpose) => {
          const item = find(purpose)
          return (
            <li key={purpose} className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{purpose === 'TERMS' ? 'Terms of use' : 'Privacy policy'}</span>
              <Badge variant={item?.granted === true ? 'secondary' : 'outline'}>
                {item?.granted === true ? 'Accepted' : 'Not accepted'}
              </Badge>
              {item !== undefined && (
                <span className="text-muted-foreground">
                  version {item.policyVersion}, {formatDateTime(item.capturedAt)}
                </span>
              )}
            </li>
          )
        })}
      </ul>
      <label className="flex items-start gap-3 text-sm">
        <input
          type="checkbox"
          role="switch"
          className="mt-0.5 size-4"
          checked={marketing?.granted === true}
          aria-checked={marketing?.granted === true}
          disabled={record.isPending || marketingVersion === undefined}
          onChange={(event) => {
            if (marketingVersion !== undefined) {
              record.mutate({ purpose: 'MARKETING', policyVersion: marketingVersion, granted: event.target.checked })
            }
          }}
        />
        <span>
          <span className="font-medium">Marketing messages</span>
          <span className="block text-muted-foreground">
            News and offers by e-mail. {marketing === undefined ? 'Not answered yet.' : `Last changed ${formatDateTime(marketing.capturedAt)}.`}
          </span>
        </span>
      </label>
      {record.isError && <ErrorAlert error={record.error} title="Your choice was not saved" />}
    </>
  )
}

/** Only http(s) links are rendered: a presigned URL is never a javascript: or data: URL. */
function safeUrl(url: string): string | undefined {
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.toString() : undefined
  } catch {
    return undefined
  }
}

function ExportCard({ requests }: { requests: ReturnType<typeof useDataRequests> }) {
  const requestExport = useRequestExport()
  const exports = (requests.data?.items ?? []).filter((r) => r.type === 'EXPORT')
  const pending = exports.some((r) => r.status === 'PENDING')
  return (
    <Card className="max-w-2xl">
      <CardHeader>
        <CardTitle>Download your data</CardTitle>
        <CardDescription>
          A JSON file with your profile, KYC details, consents, wallet and transactions. It is prepared in the
          background; the download link is valid for a short time.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap gap-3">
          <Button
            type="button"
            disabled={requestExport.isPending || pending}
            onClick={() => {
              requestExport.mutate()
            }}
          >
            {requestExport.isPending ? 'Requesting…' : pending ? 'Preparing your export…' : 'Request data export'}
          </Button>
          {exports.some((r) => r.download !== null) && (
            <Button type="button" variant="outline" onClick={() => void requests.refetch()}>
              Refresh links
            </Button>
          )}
        </div>
        {requestExport.isError && <ErrorAlert error={requestExport.error} title="Export not requested" />}
        {requests.isError && <ErrorAlert error={requests.error} title="Couldn't load your requests" />}
        {exports.length > 0 && (
          <ul className="flex flex-col gap-3 text-sm" aria-label="Data exports">
            {exports.map((request) => (
              <ExportRow key={request.id} request={request} />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

function ExportRow({ request }: { request: DataRequest }) {
  const url = request.download === null ? undefined : safeUrl(request.download.url)
  return (
    <li className="flex flex-col gap-1 rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span>Requested {formatDateTime(request.requestedAt)}</span>
        <Badge variant={request.status === 'FAILED' ? 'destructive' : request.status === 'PENDING' ? 'outline' : 'secondary'}>
          {request.status === 'PENDING' ? 'Preparing' : request.status === 'COMPLETED' ? 'Ready' : 'Failed'}
        </Badge>
      </div>
      {request.status === 'FAILED' && <p className="text-muted-foreground">This export failed. Please request a new one.</p>}
      {url !== undefined && request.download !== null && (
        <p className="flex flex-wrap items-center gap-2">
          <a href={url} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 font-medium underline">
            <Download aria-hidden className="size-4" />
            Download your data (JSON)
          </a>
          <span className="text-muted-foreground">Link valid until {formatDateTime(request.download.expiresAt)}</span>
        </p>
      )}
    </li>
  )
}

function ErasureCard() {
  const erase = useRequestErasure()
  const [phrase, setPhrase] = useState('')
  const confirmed = phrase === ERASE_PHRASE
  return (
    <Card className="max-w-2xl border-destructive/50">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-destructive">
          <ShieldAlert aria-hidden className="size-5" />
          Erase my personal data
        </CardTitle>
        <CardDescription>
          Your name, date of birth, mobile number, PAN, address and e-mail are anonymised and your wallet is closed.
          This can't be undone. By law we keep the ledger, transfer and audit records for the retention period, without
          your personal details. The wallet must be empty, not frozen, and have no open compliance review.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          className="flex max-w-sm flex-col gap-4"
          aria-label="Erase my personal data"
          onSubmit={(event) => {
            event.preventDefault()
            if (confirmed && !erase.isPending) {
              erase.mutate()
            }
          }}
        >
          <FormField id="erase-confirm" label={`Type ${ERASE_PHRASE} to confirm`}>
            <Input autoComplete="off" spellCheck={false} value={phrase} onChange={(e) => {
                setPhrase(e.target.value)
              }}
            />
          </FormField>
          {erase.isError && <ErrorAlert error={erase.error} title="Your data was not erased" />}
          <div>
            <Button type="submit" variant="destructive" disabled={!confirmed || erase.isPending}>
              {erase.isPending ? 'Erasing…' : 'Erase my data'}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  )
}

function ErasedCard() {
  const auth = useAuth()
  return (
    <Card className="max-w-2xl" data-testid="erased">
      <CardHeader>
        <CardTitle>Your personal data has been erased</CardTitle>
        <CardDescription>
          Your wallet is closed. Financial and audit records are kept, without your personal details, for the
          retention period the law requires.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Button type="button" variant="outline" onClick={() => void auth.signoutRedirect()}>
          Sign out
        </Button>
      </CardContent>
    </Card>
  )
}
