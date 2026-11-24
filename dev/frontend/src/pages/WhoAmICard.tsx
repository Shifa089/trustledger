import { useQuery } from '@tanstack/react-query'
import { coreApi } from '@/api'
import type { Me } from '@/api/types'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

const meQueryKey = ['me'] as const

/**
 * GET /me through the API client: proves the token, CORS and correlation ID work end to end, and provisions the
 * user row on first sign-in (FR-ID-2). Personal fields arrive masked.
 */
export function WhoAmICard() {
  const me = useQuery({ queryKey: meQueryKey, queryFn: () => coreApi.get<Me>('/me') })

  return (
    <Card>
      <CardHeader>
        <CardTitle>Your profile</CardTitle>
        <CardDescription>From core-app GET /api/v1/me (personal data masked).</CardDescription>
      </CardHeader>
      <CardContent>
        {me.isPending && <p className="text-sm text-muted-foreground">Loading…</p>}
        {me.isError && <ErrorAlert error={me.error} title="Couldn't load your profile" />}
        {me.isSuccess && (
          <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
            <dt className="text-muted-foreground">Name</dt>
            <dd>{me.data.fullName ?? '-'}</dd>
            <dt className="text-muted-foreground">Email</dt>
            <dd>{me.data.email ?? '-'}</dd>
            <dt className="text-muted-foreground">Status</dt>
            <dd>{me.data.status}</dd>
          </dl>
        )}
      </CardContent>
    </Card>
  )
}
