import { CheckCircle2 } from 'lucide-react'
import { useState, type ChangeEvent } from 'react'
import { isApiError } from '@/api/errors'
import { uploadKycDocument } from '@/api/kyc'
import { DOCUMENT_TYPES, type DocumentType } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Progress } from '@/components/ui/progress'
import { ACCEPT_ATTRIBUTE, DOCUMENT_LABELS, documentProblem } from './documents'

type UploadState =
  | { phase: 'idle' }
  | { phase: 'uploading'; percent: number }
  | { phase: 'done' }
  | { phase: 'error'; message: string }

/**
 * FR-KYC-2 step 2: one row per document type. Choosing a file checks its type and size first (nothing is sent for
 * a wrong one), then uploads it with a progress bar. `required` comes from POST /kyc/full; after a reload it is
 * unknown, and every type is offered.
 */
export function DocumentUploads({
  caseId,
  required,
}: {
  caseId: string
  required: readonly DocumentType[] | undefined
}) {
  const [states, setStates] = useState<Partial<Record<DocumentType, UploadState>>>({})

  const setState = (type: DocumentType, state: UploadState) => {
    setStates((previous) => ({ ...previous, [type]: state }))
  }

  const allRequiredDone = required?.every((type) => states[type]?.phase === 'done') ?? false

  async function onFile(type: DocumentType, event: ChangeEvent<HTMLInputElement>) {
    const input = event.currentTarget
    const file = input.files?.[0]
    if (file === undefined) {
      return
    }
    const problem = documentProblem(file)
    if (problem !== undefined) {
      setState(type, { phase: 'error', message: problem })
      input.value = ''
      return
    }
    setState(type, { phase: 'uploading', percent: 0 })
    try {
      await uploadKycDocument(caseId, type, file, {
        onProgress: (percent) => {
          setState(type, { phase: 'uploading', percent })
        },
      })
      setState(type, { phase: 'done' })
    } catch (error) {
      setState(type, { phase: 'error', message: isApiError(error) ? error.messageWithReference : 'Upload failed.' })
    } finally {
      input.value = ''
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <ul className="flex flex-col gap-4">
        {DOCUMENT_TYPES.map((type) => {
          const state = states[type] ?? { phase: 'idle' }
          const id = `document-${type}`
          const statusId = `${id}-status`
          const isRequired = required?.includes(type) ?? false
          return (
            <li key={type} className="flex flex-col gap-2 rounded-lg border p-4">
              <div className="flex flex-wrap items-center gap-2">
                <Label htmlFor={id}>{DOCUMENT_LABELS[type]}</Label>
                {isRequired && <Badge variant="secondary">required</Badge>}
                {required !== undefined && !isRequired && <Badge variant="outline">optional</Badge>}
              </div>
              <Input
                id={id}
                type="file"
                accept={ACCEPT_ATTRIBUTE}
                disabled={state.phase === 'uploading'}
                aria-describedby={statusId}
                aria-invalid={state.phase === 'error'}
                onChange={(event) => void onFile(type, event)}
              />
              <div id={statusId} aria-live="polite" className="text-sm">
                {state.phase === 'idle' && <span className="text-muted-foreground">PDF, JPG or PNG, up to 5 MB.</span>}
                {state.phase === 'uploading' && (
                  <div className="flex items-center gap-3">
                    <Progress value={state.percent} label={`Uploading ${DOCUMENT_LABELS[type]}`} />
                    <span className="w-10 text-right tabular-nums">{state.percent}%</span>
                  </div>
                )}
                {state.phase === 'done' && (
                  <span className="flex items-center gap-1 text-emerald-700">
                    <CheckCircle2 className="size-4" aria-hidden /> Uploaded
                  </span>
                )}
                {state.phase === 'error' && <span className="text-destructive">{state.message}</span>}
              </div>
            </li>
          )
        })}
      </ul>
      {allRequiredDone && (
        <p role="status" className="text-sm font-medium">
          All required documents are uploaded. A compliance officer will review your KYC.
        </p>
      )}
    </div>
  )
}
