import { Link, useSearchParams } from 'react-router'
import { RULES, SEVERITIES, useAlerts } from '@/api/aml'
import { ErrorAlert } from '@/components/ErrorAlert'
import { FormField } from '@/components/FormField'
import {
  EmptyState,
  LoadingRows,
  PageHeader,
  Pager,
  StatusBadge,
  tableClass,
  tdClass,
  thClass,
} from '@/components/staff'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input, Select } from '@/components/ui/input'
import { formatDateTime } from '@/lib/dates'
import { WalletLink } from '../compliance/HeldTransfersPage'
import { readAlertFilters } from './filters'
import { ruleName } from './timeline'

/** /compliance/aml (FR-AML-1/2): every alert the rules raised, newest first, filtered by rule, severity and wallet. */
export function AmlAlertsPage() {
  const [search, setSearch] = useSearchParams()
  const filters = readAlertFilters(search)
  const alerts = useAlerts(filters)
  const walletDraft = search.get('wallet') ?? ''
  const walletInvalid = walletDraft.trim() !== '' && filters.walletId === undefined

  const update = (key: 'rule' | 'severity' | 'wallet', value: string) => {
    const next = new URLSearchParams(search)
    if (value === '') next.delete(key)
    else next.set(key, value)
    next.delete('page')
    setSearch(next)
  }
  const filtered = filters.ruleCode !== undefined || filters.severity !== undefined || walletDraft !== ''

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="AML alerts" intro="Rule hits from the risk and AML service. Alerts for one wallet group into a case.">
        <Link to="/compliance/aml/cases" className="text-sm text-primary underline-offset-4 hover:underline">
          View cases
        </Link>
      </PageHeader>
      <Card>
        <CardContent>
          <form
            aria-label="Filters"
            className="grid gap-4 sm:grid-cols-[1fr_1fr_2fr_auto] sm:items-end"
            onSubmit={(event) => {
              event.preventDefault()
            }}
          >
            <FormField id="rule" label="Rule">
              <Select
                value={filters.ruleCode ?? ''}
                onChange={(event) => {
                  update('rule', event.target.value)
                }}
              >
                <option value="">All rules</option>
                {Object.entries(RULES).map(([code, name]) => (
                  <option key={code} value={code}>
                    {name}
                  </option>
                ))}
              </Select>
            </FormField>
            <FormField id="severity" label="Severity">
              <Select
                value={filters.severity ?? ''}
                onChange={(event) => {
                  update('severity', event.target.value)
                }}
              >
                <option value="">All severities</option>
                {SEVERITIES.map((value) => (
                  <option key={value} value={value}>
                    {value.charAt(0) + value.slice(1).toLowerCase()}
                  </option>
                ))}
              </Select>
            </FormField>
            <FormField
              id="wallet"
              label="Wallet ID"
              error={walletInvalid ? 'Enter a full wallet ID (a UUID).' : undefined}
            >
              <Input
                value={walletDraft}
                autoComplete="off"
                spellCheck={false}
                onChange={(event) => {
                  update('wallet', event.target.value)
                }}
              />
            </FormField>
            <Button
              type="button"
              variant="outline"
              disabled={!filtered}
              onClick={() => {
                setSearch(new URLSearchParams())
              }}
            >
              Clear filters
            </Button>
          </form>
        </CardContent>
      </Card>

      <section aria-label="Alerts" aria-busy={alerts.isFetching} className="flex flex-col gap-4">
        {alerts.isPending ? (
          <LoadingRows label="Loading alerts" />
        ) : alerts.isError ? (
          <ErrorAlert error={alerts.error} title="Couldn't load AML alerts" />
        ) : alerts.data.content.length === 0 ? (
          <EmptyState>{filtered ? 'No alerts match these filters.' : 'No alerts yet.'}</EmptyState>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className={tableClass}>
                <caption className="sr-only">AML alerts</caption>
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th scope="col" className={thClass}>Raised</th>
                    <th scope="col" className={thClass}>Rule</th>
                    <th scope="col" className={thClass}>Severity</th>
                    <th scope="col" className={thClass}>Wallet</th>
                    <th scope="col" className={thClass}>Details</th>
                    <th scope="col" className={thClass}>Case</th>
                  </tr>
                </thead>
                <tbody>
                  {alerts.data.content.map((alert) => (
                    <tr key={alert.id} className="border-b last:border-0">
                      <td className={tdClass}>{formatDateTime(alert.createdAt)}</td>
                      <td className={tdClass}>{ruleName(alert.ruleCode)}</td>
                      <td className={tdClass}>
                        <StatusBadge value={alert.severity} />
                      </td>
                      <td className={tdClass}>
                        <WalletLink id={alert.walletId} />
                      </td>
                      <td className={`${tdClass} text-muted-foreground`}>{alert.details ?? '-'}</td>
                      <td className={tdClass}>
                        {alert.caseId === null ? (
                          '-'
                        ) : (
                          <Link
                            to={`/compliance/aml/cases/${alert.caseId}`}
                            className="text-primary underline-offset-4 hover:underline"
                          >
                            Open case{' '}<span className="sr-only">for alert {alert.id}</span>
                          </Link>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager
              page={filters.page}
              totalPages={alerts.data.totalPages}
              totalElements={alerts.data.totalElements}
              noun="alerts"
              onPage={(page) => {
                const next = new URLSearchParams(search)
                if (page > 0) next.set('page', String(page + 1))
                else next.delete('page')
                setSearch(next)
              }}
            />
          </>
        )}
      </section>
    </div>
  )
}
