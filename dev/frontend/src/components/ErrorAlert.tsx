import { CircleAlert } from 'lucide-react'
import { isApiError } from '@/api/errors'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'

/** An API failure as the user should see it: the friendly message plus the correlation ID for support. */
export function ErrorAlert({ error, title = 'Something went wrong' }: { error: unknown; title?: string }) {
  const message = isApiError(error) ? error.message : 'Something went wrong.'
  const reference = isApiError(error) ? error.correlationId : undefined
  return (
    <Alert variant="destructive">
      <CircleAlert aria-hidden />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription>
        <p>{message}</p>
        {reference !== undefined && <p className="font-mono text-xs">Reference: {reference}</p>}
      </AlertDescription>
    </Alert>
  )
}
