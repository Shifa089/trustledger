import { zodResolver } from '@hookform/resolvers/zod'
import { useRef, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { FormField } from '@/components/FormField'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'

/** Shared pieces of the staff screens (compliance, AML, admin, audit). */

export function PageHeader({ title, intro, children }: { title: string; intro: string; children?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        <p className="text-muted-foreground">{intro}</p>
      </div>
      {children}
    </div>
  )
}

/** Previous / next over a zero-based server page. */
export function Pager({
  page,
  totalPages,
  totalElements,
  noun,
  onPage,
}: {
  page: number
  totalPages: number
  totalElements: number
  noun: string
  onPage: (page: number) => void
}) {
  const pages = Math.max(totalPages, 1)
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between gap-4 text-sm">
      <Button
        variant="outline"
        disabled={page === 0}
        onClick={() => {
          onPage(page - 1)
        }}
      >
        Previous
      </Button>
      <span aria-live="polite">
        Page {Math.min(page + 1, pages)} of {pages} · {totalElements} {noun}
      </span>
      <Button
        variant="outline"
        disabled={page + 1 >= totalPages}
        onClick={() => {
          onPage(page + 1)
        }}
      >
        Next
      </Button>
    </nav>
  )
}

const STATUS_VARIANT: Readonly<Record<string, 'default' | 'secondary' | 'destructive' | 'outline'>> = {
  SUBMITTED: 'secondary',
  VERIFIED: 'default',
  APPROVED: 'outline',
  REJECTED: 'destructive',
  ACTIVE: 'outline',
  FROZEN: 'destructive',
  CLOSED: 'secondary',
  OPEN: 'secondary',
  INVESTIGATING: 'default',
  CLOSED_FALSE_POSITIVE: 'outline',
  ESCALATED: 'destructive',
  HIGH: 'destructive',
  MEDIUM: 'secondary',
  HELD_FOR_REVIEW: 'secondary',
}

/** A status or severity as a badge: "CLOSED_FALSE_POSITIVE" → "Closed false positive". */
export function StatusBadge({ value }: { value: string }) {
  const label = value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ')
  return <Badge variant={STATUS_VARIANT[value] ?? 'outline'}>{label}</Badge>
}

/** A long ID shown short, with the full value for hover and screen readers. */
export function Id({ value }: { value: string }) {
  return (
    <span className="font-mono text-xs" title={value} aria-label={value}>
      {value.slice(0, 8)}…
    </span>
  )
}

/**
 * A required free-text reason (KYC reject, freeze, unfreeze, AML close and escalate). The limit mirrors the backend's
 * @Size, and a ref-based in-flight guard stops a double click from sending the decision twice.
 */
export function ReasonForm({
  id,
  label,
  maxLength,
  submitLabel,
  pendingLabel,
  pending,
  destructive = false,
  hint,
  onSubmit,
  onCancel,
}: {
  id: string
  label: string
  maxLength: number
  submitLabel: string
  pendingLabel: string
  pending: boolean
  destructive?: boolean
  hint?: string
  onSubmit: (reason: string) => Promise<unknown>
  onCancel?: () => void
}) {
  const schema = z.object({
    reason: z
      .string()
      .trim()
      .min(1, 'Enter a reason.')
      .max(maxLength, `At most ${String(maxLength)} characters.`),
  })
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<{ reason: string }>({ resolver: zodResolver(schema), defaultValues: { reason: '' } })
  const inFlight = useRef(false)

  return (
    <form
      noValidate
      className="flex flex-col gap-3"
      onSubmit={(event) => {
        void handleSubmit(async ({ reason }) => {
          if (inFlight.current) return
          inFlight.current = true
          try {
            await onSubmit(reason)
          } catch {
            // The caller shows the mutation's error.
          } finally {
            inFlight.current = false
          }
        })(event)
      }}
    >
      <FormField id={id} label={label} hint={hint} error={errors.reason?.message}>
        <Textarea maxLength={maxLength} {...register('reason')} />
      </FormField>
      <div className="flex gap-2">
        <Button type="submit" variant={destructive ? 'destructive' : 'default'} disabled={pending}>
          {pending ? pendingLabel : submitLabel}
        </Button>
        {onCancel !== undefined && (
          <Button type="button" variant="outline" onClick={onCancel} disabled={pending}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  )
}

/** Shown while a list loads. */
export function LoadingRows({ label }: { label: string }) {
  return (
    <div role="status" aria-label={label} className="flex flex-col gap-2">
      {Array.from({ length: 4 }, (_, index) => (
        <div key={index} className="h-9 animate-pulse rounded-md bg-muted" />
      ))}
    </div>
  )
}

export function EmptyState({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-md border border-dashed p-8 text-center text-sm text-muted-foreground">{children}</p>
  )
}

export const tableClass = 'w-full text-left text-sm'
export const thClass = 'px-3 py-2 font-medium'
export const tdClass = 'px-3 py-2 align-top'
