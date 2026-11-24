import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react'
import type { Transaction } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { formatDateTime } from '@/lib/dates'
import { cn } from '@/lib/utils'
import { shortId, signedAmount, STATUS_LABELS, TYPE_LABELS, type SortField, type SortState } from './transactions'

/**
 * Transactions as a table. With `sort` and `onSort`, the Date and Amount headers are buttons that change the
 * server-side sort; `aria-sort` tells a screen reader which column is sorted.
 */
export function TransactionTable({
  transactions,
  caption,
  sort,
  onSort,
}: {
  transactions: readonly Transaction[]
  caption: string
  sort?: SortState
  onSort?: (field: SortField) => void
}) {
  const header = (field: SortField, label: string, className?: string) => {
    const active = sort?.field === field
    const ariaSort = active ? (sort.ascending ? 'ascending' : 'descending') : 'none'
    if (onSort === undefined) {
      return (
        <th scope="col" className={cn('px-3 py-2 font-medium', className)}>
          {label}
        </th>
      )
    }
    const Icon = !active ? ArrowUpDown : sort.ascending ? ArrowUp : ArrowDown
    return (
      <th scope="col" aria-sort={ariaSort} className={cn('px-3 py-2 font-medium', className)}>
        <button
          type="button"
          onClick={() => {
            onSort(field)
          }}
          className="inline-flex items-center gap-1 rounded hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          {label}
          <Icon className="size-3.5" aria-hidden />
          <span className="sr-only">
            {active ? `, sorted ${ariaSort}. Click to reverse.` : ', click to sort'}
          </span>
        </button>
      </th>
    )
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead className="border-b text-muted-foreground">
          <tr>
            {header('createdAt', 'Date')}
            <th scope="col" className="px-3 py-2 font-medium">
              Type
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              Details
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              Status
            </th>
            {header('amount', 'Amount', 'text-right')}
          </tr>
        </thead>
        <tbody className="divide-y">
          {transactions.map((transaction) => (
            <tr key={transaction.id}>
              <td className="px-3 py-2 whitespace-nowrap">{formatDateTime(transaction.createdAt)}</td>
              <td className="px-3 py-2">{TYPE_LABELS[transaction.type]}</td>
              <td className="px-3 py-2 text-muted-foreground">
                {transaction.counterpartyWalletId !== null && (
                  <span className="font-mono">
                    {transaction.type === 'TRANSFER_OUT' ? 'To' : 'From'} wallet {shortId(transaction.counterpartyWalletId)}
                  </span>
                )}
                {transaction.note !== null && transaction.note !== '' && <span> · {transaction.note}</span>}
              </td>
              <td className="px-3 py-2">
                <Badge variant="outline">{STATUS_LABELS[transaction.status] ?? transaction.status}</Badge>
              </td>
              <td className="px-3 py-2 text-right font-medium whitespace-nowrap tabular-nums">
                {signedAmount(transaction)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
