import { RULES, SEVERITIES } from '@/api/aml'
import { pageFrom } from '@/lib/staff'
import { UUID_PATTERN } from '../transfer/schemas'

/** Filters from the URL; unknown values are dropped so a hand-edited URL never causes a 400. */
export function readAlertFilters(search: URLSearchParams) {
  const rule = search.get('rule') ?? ''
  const severity = search.get('severity')
  const wallet = (search.get('wallet') ?? '').trim()
  return {
    ruleCode: Object.hasOwn(RULES, rule) ? rule : undefined,
    severity: SEVERITIES.find((value) => value === severity),
    walletId: UUID_PATTERN.test(wallet) ? wallet.toLowerCase() : undefined,
    page: pageFrom(search),
  }
}
