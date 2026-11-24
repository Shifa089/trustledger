import type { Transaction } from '@/api/types'
import { activityByDay, rupeeTicks } from './activity'

function tx(type: Transaction['type'], status: string, amount: string, createdAt: string): Transaction {
  return { id: createdAt + type, type, status, amount, currency: 'INR', counterpartyWalletId: null, note: null, createdAt }
}

describe('activityByDay', () => {
  it('has every one of the 30 days, oldest first, ending today', () => {
    const { days } = activityByDay([], '2026-11-24')
    expect(days).toHaveLength(30)
    expect(days[0]?.day).toBe('2026-10-26')
    expect(days.at(-1)?.day).toBe('2026-11-24')
    expect(days.every((d) => d.inPaise === 0n && d.outPaise === 0n)).toBe(true)
  })

  it('buckets by the Indian day and sums in bigint paise', () => {
    const summary = activityByDay(
      [
        // 18:30Z on the 23rd is already the 24th in India.
        tx('TOPUP', 'CAPTURED', '0.10', '2026-11-23T18:30:00Z'),
        tx('TRANSFER_IN', 'COMPLETED', '0.20', '2026-11-24T10:00:00Z'),
        tx('TRANSFER_OUT', 'COMPLETED', '1500.50', '2026-11-23T18:29:59Z'),
      ],
      '2026-11-24',
    )
    const today = summary.days.at(-1)
    const yesterday = summary.days.at(-2)
    expect(today?.inPaise).toBe(30n) // 0.10 + 0.20 is exactly 0.30
    expect(yesterday?.outPaise).toBe(150050n)
    expect(summary.totalIn).toBe(30n)
    expect(summary.totalOut).toBe(150050n)
  })

  it('counts only money that moved', () => {
    const summary = activityByDay(
      [
        tx('TOPUP', 'PENDING', '100.00', '2026-11-24T05:00:00Z'),
        tx('TOPUP', 'FAILED', '100.00', '2026-11-24T05:00:00Z'),
        tx('TRANSFER_OUT', 'HELD_FOR_REVIEW', '60000.00', '2026-11-24T05:00:00Z'),
        tx('TRANSFER_OUT', 'REJECTED', '60000.00', '2026-11-24T05:00:00Z'),
      ],
      '2026-11-24',
    )
    expect(summary.totalIn).toBe(0n)
    expect(summary.totalOut).toBe(0n)
  })

  it('ignores rows outside the window', () => {
    const summary = activityByDay([tx('TOPUP', 'CAPTURED', '5.00', '2026-10-25T06:00:00Z')], '2026-11-24')
    expect(summary.totalIn).toBe(0n)
  })
})

describe('rupeeTicks', () => {
  it.each<[bigint, bigint[]]>([
    [0n, [0n, 100n]],
    [200n, [0n, 100n, 200n]], // ₹2: ₹1 steps, never 50 paise
    [150n, [0n, 100n, 200n]],
    [1_000_000n, [0n, 500_000n, 1_000_000n]], // ₹10,000: ₹5,000 steps
    [4_750_000n, [0n, 2_000_000n, 4_000_000n, 6_000_000n]],
  ])('%s paise → %s', (max, ticks) => {
    expect(rupeeTicks(max)).toEqual(ticks)
  })

  it('always uses whole rupees, so labels never repeat', () => {
    for (const max of [1n, 99n, 333n, 12_345n, 9_999_999n]) {
      const ticks = rupeeTicks(max)
      expect(ticks.every((t) => t % 100n === 0n)).toBe(true)
      expect((ticks.at(-1) ?? 0n) >= max).toBe(true)
    }
  })
})
