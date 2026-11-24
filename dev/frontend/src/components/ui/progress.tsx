import { cn } from '@/lib/utils'

/**
 * A progress bar (shadcn/ui Progress look) without Radix: a div with role="progressbar" and its aria values.
 * `value` is a whole percent, 0-100. `tone="warning"` when a limit is nearly used up.
 */
function Progress({
  value,
  label,
  valueText,
  tone = 'default',
  className,
}: {
  value: number
  /** Accessible name, e.g. "Daily transfers". */
  label: string
  /** Read out instead of the bare percent, e.g. "₹250.00 of ₹1,00,000.00". */
  valueText?: string
  tone?: 'default' | 'warning'
  className?: string
}) {
  const percent = Math.max(0, Math.min(100, Math.trunc(value)))
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      aria-valuetext={valueText ?? `${percent}%`}
      data-slot="progress"
      className={cn('relative h-2 w-full overflow-hidden rounded-full bg-secondary', className)}
    >
      <div
        className={cn('h-full rounded-full transition-[width]', tone === 'warning' ? 'bg-amber-600' : 'bg-primary')}
        style={{ width: `${percent}%` }}
      />
    </div>
  )
}

export { Progress }
