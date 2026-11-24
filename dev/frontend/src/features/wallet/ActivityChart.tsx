import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { formatShortDay } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { rupeeTicks, type ActivitySummary } from './activity'

/**
 * Two categorical series (dataviz reference palette slots 1 and 2, validated: CVD ΔE 24.7, contrast ≥ 3:1 on the
 * light surface). Identity is never colour alone: a legend names both series and the tooltip labels each value.
 */
const MONEY_IN = '#2a78d6'
const MONEY_OUT = '#eb6834'

/**
 * Recharts needs numbers for bar heights, so each day's bigint paise becomes a Number of paise: still an integer
 * (no division, so no rounding), and refused if beyond 2^53. Every label goes back through formatMoney.
 */
function chartValue(paise: bigint): number {
  if (paise > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new RangeError('Daily total too large to chart exactly')
  }
  return Number(paise)
}

function moneyLabel(value: unknown): string {
  return typeof value === 'number' && Number.isSafeInteger(value) ? formatMoney(value) : ''
}

/** The 30-day chart, with a caption summary and an optional table for screen readers and keyboard users. */
export function ActivityChart({ summary, truncated }: { summary: ActivitySummary; truncated: boolean }) {
  const data = summary.days.map((day) => ({
    day: day.day,
    label: formatShortDay(day.day),
    in: chartValue(day.inPaise),
    out: chartValue(day.outPaise),
  }))
  const quiet = summary.totalIn === 0n && summary.totalOut === 0n
  const highest = summary.days.reduce((max, day) => {
    const top = day.inPaise > day.outPaise ? day.inPaise : day.outPaise
    return top > max ? top : max
  }, 0n)
  const ticks = rupeeTicks(highest).map(chartValue)

  return (
    <figure className="flex flex-col gap-3">
      <figcaption className="text-sm text-muted-foreground" data-testid="activity-summary">
        Last 30 days: {formatMoney(summary.totalIn)} in, {formatMoney(summary.totalOut)} out (completed only).
        {truncated && ' Showing the newest 500 transactions.'}
      </figcaption>
      {quiet ? (
        <p className="rounded-md border border-dashed p-6 text-center text-sm text-muted-foreground">
          No money moved in the last 30 days.
        </p>
      ) : (
        <div className="h-56 w-full" aria-hidden>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} barGap={2} barCategoryGap="12%" margin={{ top: 4, right: 8, bottom: 0, left: 8 }}>
              <CartesianGrid vertical={false} stroke="var(--border)" />
              <XAxis
                dataKey="label"
                tickLine={false}
                axisLine={false}
                interval="preserveStartEnd"
                minTickGap={24}
                fontSize={12}
                stroke="var(--muted-foreground)"
              />
              <YAxis
                ticks={ticks}
                domain={[0, ticks.at(-1) ?? 0]}
                allowDecimals={false}
                tickLine={false}
                axisLine={false}
                width={72}
                fontSize={12}
                stroke="var(--muted-foreground)"
                tickFormatter={(value: number) => (Number.isSafeInteger(value) ? formatMoney(value, { wholeRupees: true }) : '')}
              />
              <Tooltip
                cursor={{ fill: 'var(--muted)' }}
                formatter={(value, name) => [moneyLabel(value), name]}
                contentStyle={{ borderRadius: 8, borderColor: 'var(--border)', fontSize: 12 }}
              />
              <Legend iconType="square" wrapperStyle={{ fontSize: 12 }} />
              <Bar dataKey="in" name="Money in" fill={MONEY_IN} radius={[4, 4, 0, 0]} maxBarSize={12} />
              <Bar dataKey="out" name="Money out" fill={MONEY_OUT} radius={[4, 4, 0, 0]} maxBarSize={12} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
      {!quiet && (
        <details className="text-sm">
          <summary className="cursor-pointer text-muted-foreground">Show as table</summary>
          <table className="mt-2 w-full text-left">
            <caption className="sr-only">Money in and out per day, last 30 days</caption>
            <thead className="text-muted-foreground">
              <tr>
                <th scope="col" className="py-1 font-medium">
                  Day
                </th>
                <th scope="col" className="py-1 text-right font-medium">
                  Money in
                </th>
                <th scope="col" className="py-1 text-right font-medium">
                  Money out
                </th>
              </tr>
            </thead>
            <tbody>
              {summary.days
                .filter((day) => day.inPaise !== 0n || day.outPaise !== 0n)
                .map((day) => (
                  <tr key={day.day}>
                    <td className="py-1">{formatShortDay(day.day)}</td>
                    <td className="py-1 text-right tabular-nums">{formatMoney(day.inPaise)}</td>
                    <td className="py-1 text-right tabular-nums">{formatMoney(day.outPaise)}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </details>
      )}
    </figure>
  )
}
