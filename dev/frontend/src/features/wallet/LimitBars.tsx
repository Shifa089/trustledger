import type { LimitUsage, MyWallet } from '@/api/types'
import { Progress } from '@/components/ui/progress'
import { formatMoney, formatRupees, percentUsed } from '@/lib/money'
import { limitRows, WARN_AT } from './limits'

export function LimitBars({
  wallet,
  usage,
  usageError,
}: {
  wallet: MyWallet
  usage: LimitUsage | undefined
  usageError: boolean
}) {
  return (
    <div className="flex flex-col gap-4">
      <ul className="flex flex-col gap-4">
        {limitRows(wallet, usage).map((row) => {
          const percent = percentUsed(row.used, row.limit)
          const text = `${formatMoney(row.used)} of ${formatMoney(row.limit)}`
          return (
            <li key={row.label} className="flex flex-col gap-1.5">
              <div className="flex items-baseline justify-between gap-2 text-sm">
                <span className="font-medium">{row.label}</span>
                <span className="text-muted-foreground tabular-nums">{text}</span>
              </div>
              <Progress
                value={percent}
                label={row.label}
                valueText={`${text}, ${percent}%`}
                tone={percent >= WARN_AT ? 'warning' : 'default'}
              />
            </li>
          )
        })}
      </ul>
      {usage === undefined && !usageError && <p className="text-sm text-muted-foreground">Loading limits used…</p>}
      {usageError && <p className="text-sm text-destructive">Couldn't load how much of your limits is used.</p>}
      {!wallet.limits.p2pAllowed && (
        <p className="text-sm text-muted-foreground">Sending money needs full KYC.</p>
      )}
      {wallet.limits.highValueThreshold !== null && (
        <p className="text-sm text-muted-foreground">
          Transfers of {formatRupees(wallet.limits.highValueThreshold)} or more are held for a compliance review.
        </p>
      )}
    </div>
  )
}

