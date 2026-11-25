import { CircleCheck, ShieldX } from 'lucide-react'
import { useRef } from 'react'
import { useVerifyChain, type ChainVerification } from '@/api/operations'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

const REASONS: Readonly<Record<NonNullable<ChainVerification['reason']>, string>> = {
  PREV_HASH_MISMATCH: "its previous-hash doesn't match the event before it (an event was removed or reordered)",
  HASH_MISMATCH: "its stored hash doesn't match its contents (the event was changed)",
  UNREADABLE: "the event couldn't be read to recompute its hash",
}

/**
 * "Verify chain" (FR-A-2): GET /audit/verify walks the whole hash chain on the server. The result is OK with the
 * number of events checked, or the sequence number of the first broken link and why.
 */
export function AuditVerify() {
  const verify = useVerifyChain()
  const inFlight = useRef(false)

  const run = async () => {
    if (inFlight.current) return
    inFlight.current = true
    try {
      await verify.mutateAsync()
    } catch {
      // shown below
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Hash chain</CardTitle>
        <CardDescription>
          Every audit event stores the hash of the one before it. Verifying recomputes every hash from the first event.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div>
          <Button
            disabled={verify.isPending}
            onClick={() => {
              void run()
            }}
          >
            {verify.isPending ? 'Verifying…' : 'Verify chain'}
          </Button>
        </div>
        <div aria-live="polite">
          {verify.isError && <ErrorAlert error={verify.error} title="Couldn't verify the chain" />}
          {verify.isSuccess && <Result result={verify.data} />}
        </div>
      </CardContent>
    </Card>
  )
}

function Result({ result }: { result: ChainVerification }) {
  if (result.status === 'OK') {
    return (
      <Alert data-testid="chain-ok">
        <CircleCheck aria-hidden className="text-emerald-600" />
        <AlertTitle>Chain OK</AlertTitle>
        <AlertDescription>
          {result.checkedEvents} events checked
          {result.lastSeq !== null && <>, up to sequence #{result.lastSeq}</>}. No event was changed, removed or
          reordered.
        </AlertDescription>
      </Alert>
    )
  }
  return (
    <Alert variant="destructive" data-testid="chain-broken">
      <ShieldX aria-hidden />
      <AlertTitle>Chain broken at sequence #{result.brokenSeq ?? '?'}</AlertTitle>
      <AlertDescription>
        <p>
          The first broken link is event #{result.brokenSeq ?? '?'}:{' '}
          {result.reason === null ? 'the chain does not verify' : REASONS[result.reason]}.
        </p>
        <p>
          {result.checkedEvents} events checked. Events before it are intact. Treat this as a security incident.
        </p>
      </AlertDescription>
    </Alert>
  )
}
