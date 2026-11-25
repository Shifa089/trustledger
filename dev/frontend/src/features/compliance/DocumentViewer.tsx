import { ExternalLink, RefreshCw } from 'lucide-react'
import { useDocumentLink, type KycDocumentMeta } from '@/api/compliance'
import { ErrorAlert } from '@/components/ErrorAlert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDateTime } from '@/lib/dates'
import { isSafeUrl } from '@/lib/staff'
import { DOCUMENT_LABELS } from '../kyc/documents'

/**
 * One KYC document through a presigned S3 URL (SPEC §5: valid 5 minutes, every request audited). Images are shown
 * inline; a PDF opens in a new tab (the object is served as an attachment). The link is never cached: "Get a new
 * link" asks the server again.
 */
export function DocumentViewer({
  caseId,
  document,
  onClose,
}: {
  caseId: string
  document: KycDocumentMeta
  onClose: () => void
}) {
  const link = useDocumentLink(caseId, document.id)
  const label = DOCUMENT_LABELS[document.type]
  const url = link.data !== undefined && isSafeUrl(link.data.url) ? link.data.url : undefined

  return (
    <Card aria-label={`Document viewer: ${label}`} role="region">
      <CardHeader>
        <CardTitle>{label}</CardTitle>
        <CardDescription>
          {document.contentType} · {Math.ceil(document.sizeBytes / 1024)} KB · SHA-256{' '}
          <span className="font-mono" title={document.sha256}>
            {document.sha256.slice(0, 12)}…
          </span>
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {link.isPending && <p className="text-sm text-muted-foreground">Getting a secure link…</p>}
        {link.isError && <ErrorAlert error={link.error} title="Couldn't open the document" />}
        {link.isSuccess && url === undefined && <p className="text-sm text-destructive">The link was not valid.</p>}
        {url !== undefined && link.data !== undefined && (
          <>
            {document.contentType.startsWith('image/') ? (
              <img src={url} alt={`${label} uploaded by the customer`} className="max-h-[480px] rounded-md border object-contain" />
            ) : (
              <p className="text-sm">PDF documents open in a new tab.</p>
            )}
            <div className="flex flex-wrap items-center gap-3 text-sm">
              <a
                href={url}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
              >
                <ExternalLink className="size-4" aria-hidden />
                Open in new tab
              </a>
              <span className="text-muted-foreground" data-testid="link-expires">
                Link expires {formatDateTime(link.data.expiresAt)}
              </span>
            </div>
          </>
        )}
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={link.isFetching}
            onClick={() => {
              void link.refetch()
            }}
          >
            <RefreshCw aria-hidden />
            Get a new link
          </Button>
          <Button variant="ghost" size="sm" onClick={onClose}>
            Close viewer
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
