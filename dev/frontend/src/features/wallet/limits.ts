import type { LimitUsage, MyWallet } from '@/api/types'
import { parseRupees } from '@/lib/money'

export interface LimitRow {
  label: string
  used: bigint
  limit: bigint
}

/** A bar turns amber from this percent, so a customer sees a limit coming before a payment is refused. */
export const WARN_AT = 90

/** FR-W-2 limits with how much is used. `usage` is undefined while GET /wallets/me/limit-usage loads. */
export function limitRows(wallet: MyWallet, usage: LimitUsage | undefined): LimitRow[] {
  const rows: LimitRow[] = [
    { label: 'Wallet balance', used: parseRupees(wallet.balance), limit: parseRupees(wallet.limits.maxBalance) },
  ]
  if (usage !== undefined) {
    rows.push({
      label: 'Added this month',
      used: parseRupees(usage.addedThisMonth),
      limit: parseRupees(wallet.limits.monthlyLoad),
    })
    if (wallet.limits.p2pAllowed && wallet.limits.dailyTransfer !== null) {
      rows.push({
        label: 'Sent today',
        used: parseRupees(usage.transferredToday),
        limit: parseRupees(wallet.limits.dailyTransfer),
      })
    }
    if (wallet.limits.p2pAllowed && wallet.limits.monthlyTransfer !== null) {
      rows.push({
        label: 'Sent this month',
        used: parseRupees(usage.transferredThisMonth),
        limit: parseRupees(wallet.limits.monthlyTransfer),
      })
    }
  }
  return rows.map((row) => ({ ...row, used: row.used < 0n ? 0n : row.used }))
}
