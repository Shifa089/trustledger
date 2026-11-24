import type { Transaction } from '@/api/types'
import { addDays, indianDay } from '@/lib/dates'
import { parseRupees } from '@/lib/money'

/** One Indian calendar day of the 30-day chart. Sums are bigint paise. */
export interface DayActivity {
  day: string
  inPaise: bigint
  outPaise: bigint
}

export interface ActivitySummary {
  days: DayActivity[]
  totalIn: bigint
  totalOut: bigint
}

/**
 * Money that really moved: captured top-ups and completed transfers. Held transfers (money reserved, not gone) and
 * failed or rejected ones are left out, so the chart matches the balance.
 */
function direction(transaction: Transaction): 'in' | 'out' | undefined {
  if (transaction.type === 'TOPUP') {
    return transaction.status === 'CAPTURED' ? 'in' : undefined
  }
  if (transaction.status !== 'COMPLETED') {
    return undefined
  }
  return transaction.type === 'TRANSFER_IN' ? 'in' : 'out'
}

/**
 * Y-axis ticks in paise, at whole-rupee "nice" steps (₹1, 2, 5, 10, 20, 50, …), so whole-rupee labels never repeat
 * (₹0, ₹0, ₹1, ₹1 when the library picks 50-paise steps). Bigint maths; about `count` intervals, never fewer than
 * needed to reach `maxPaise`.
 */
export function rupeeTicks(maxPaise: bigint, count = 4): bigint[] {
  if (maxPaise <= 0n) {
    return [0n, 100n]
  }
  const rough = (maxPaise + BigInt(count) - 1n) / BigInt(count)
  let step = 100n
  for (let magnitude = 100n; step < rough; magnitude *= 10n) {
    for (const factor of [1n, 2n, 5n]) {
      step = magnitude * factor
      if (step >= rough) break
    }
  }
  const ticks: bigint[] = []
  for (let tick = 0n; ; tick += step) {
    ticks.push(tick)
    if (tick >= maxPaise) break
  }
  return ticks
}

/**
 * The last `days` Indian days up to `today`, each with money in and out. Every day is present (zero when quiet), so
 * the chart's x axis is continuous. Rows outside the window are ignored.
 */
export function activityByDay(transactions: readonly Transaction[], today: string, days = 30): ActivitySummary {
  const buckets = new Map<string, DayActivity>()
  for (let offset = days - 1; offset >= 0; offset--) {
    const day = addDays(today, -offset)
    buckets.set(day, { day, inPaise: 0n, outPaise: 0n })
  }
  let totalIn = 0n
  let totalOut = 0n
  for (const transaction of transactions) {
    const way = direction(transaction)
    const bucket = buckets.get(indianDay(transaction.createdAt))
    if (way === undefined || bucket === undefined) {
      continue
    }
    const paise = parseRupees(transaction.amount)
    if (way === 'in') {
      bucket.inPaise += paise
      totalIn += paise
    } else {
      bucket.outPaise += paise
      totalOut += paise
    }
  }
  return { days: [...buckets.values()], totalIn, totalOut }
}
