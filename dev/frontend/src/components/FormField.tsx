import { cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react'
import { Label } from '@/components/ui/label'

interface ControlProps {
  id?: string
  'aria-invalid'?: boolean
  'aria-describedby'?: string
}

/**
 * A labelled form control (CLAUDE.md: every input has a label). The control gets the id, `aria-invalid` and
 * `aria-describedby` pointing at the hint and the error, so a screen reader reads both with the field.
 */
export function FormField({
  id,
  label,
  hint,
  error,
  children,
}: {
  id: string
  label: string
  hint?: ReactNode
  error?: string | undefined
  children: ReactElement<ControlProps>
}) {
  const hintId = hint === undefined ? undefined : `${id}-hint`
  const errorId = error === undefined ? undefined : `${id}-error`
  const describedBy = [hintId, errorId].filter((value) => value !== undefined).join(' ')
  const control = isValidElement(children)
    ? cloneElement(children, {
        id,
        'aria-invalid': error !== undefined,
        ...(describedBy === '' ? {} : { 'aria-describedby': describedBy }),
      })
    : children
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      {control}
      {hint !== undefined && (
        <p id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </p>
      )}
      {error !== undefined && (
        <p id={errorId} className="text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
