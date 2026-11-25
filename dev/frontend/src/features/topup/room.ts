import type { LimitUsage, MyWallet } from '@/api/types'
import { parseRupees, toApiAmount } from '@/lib/money'

/**
 * The most the wallet can take right now, for a hint: the smaller of the room under the balance cap and what is
 * left of this month's load limit. Bigint paise; never below zero. The server's own check (which also counts open
 * top-ups) decides.
 */
export function roomLeft(wallet: MyWallet, usage: LimitUsage | undefined): string {
  const underCap = parseRupees(wallet.limits.maxBalance) - parseRupees(wallet.balance)
  const loadLeft =
    usage === undefined ? underCap : parseRupees(wallet.limits.monthlyLoad) - parseRupees(usage.addedThisMonth)
  const room = underCap < loadLeft ? underCap : loadLeft
  return toApiAmount(room < 0n ? 0n : room)
}
