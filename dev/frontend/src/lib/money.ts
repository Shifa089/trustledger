/**
 * Money in the browser, without floating point (CLAUDE.md "Money: long paise", ADR 0003).
 *
 * The API sends and accepts amounts as decimal strings in rupees ("1250.50"). Here they become a bigint number of
 * paise, so adding, comparing and formatting never round. `Number`/`parseFloat` are never used for money: 0.1 + 0.2
 * is 0.30000000000000004, and a ₹ amount above 2^53 paise would silently lose precision.
 */

const AMOUNT = /^(-)?(\d+)(?:\.(\d{1,2}))?$/

/** "1250.5" → 125050n. Throws on anything that is not a plain decimal with at most two fraction digits. */
export function parseRupees(amount: string): bigint {
  const match = AMOUNT.exec(amount.trim())
  if (match === null) {
    throw new RangeError(`Not a rupee amount: "${amount}"`)
  }
  const [, sign, rupees = '0', fraction = ''] = match
  const paise = BigInt(rupees) * 100n + BigInt(fraction.padEnd(2, '0'))
  return sign === '-' ? -paise : paise
}

/** 125050n → "1250.50": the API's request format. */
export function toApiAmount(paise: bigint): string {
  const negative = paise < 0n
  const abs = negative ? -paise : paise
  const fraction = (abs % 100n).toString().padStart(2, '0')
  return `${negative ? '-' : ''}${(abs / 100n).toString()}.${fraction}`
}

/** Indian digit grouping: 12345678 → "1,23,45,678". */
function groupIndian(digits: string): string {
  if (digits.length <= 3) {
    return digits
  }
  const last3 = digits.slice(-3)
  const rest = digits.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',')
  return `${rest},${last3}`
}

/**
 * A paise count as a bigint. A `number` is accepted only when it is a safe integer (chart values, test literals):
 * 1.5 or 2^53 + 1 would already be wrong before any formatting, so they throw instead of rounding.
 */
export function toPaise(paise: bigint | number): bigint {
  if (typeof paise === 'bigint') {
    return paise
  }
  if (!Number.isSafeInteger(paise)) {
    throw new RangeError(`Not a whole number of paise: ${paise}`)
  }
  return BigInt(paise)
}

export interface FormatMoneyOptions {
  /** Drop the paise ("₹1,500"): for chart axis ticks only, never for an amount a user acts on. */
  wholeRupees?: boolean
}

/**
 * 150050 paise → "₹1,500.50", with Indian digit grouping (12345678 → "₹1,23,456.78"). Integer maths only: rupees
 * are `paise / 100n` and the paise `paise % 100n`, both bigint, so no value is ever rounded.
 */
export function formatMoney(paise: bigint | number, options: FormatMoneyOptions = {}): string {
  const value = toPaise(paise)
  const negative = value < 0n
  const abs = negative ? -value : value
  const rupees = groupIndian((abs / 100n).toString())
  const fraction = (abs % 100n).toString().padStart(2, '0')
  const sign = negative ? '-' : ''
  return options.wholeRupees === true ? `${sign}₹${rupees}` : `${sign}₹${rupees}.${fraction}`
}

/** An API amount string straight to display: "123456.78" → "₹1,23,456.78". */
export function formatRupees(amount: string): string {
  return formatMoney(parseRupees(amount))
}

/**
 * Whole percent of a limit used, for a progress bar: floor(used × 100 / limit), clamped to 0-100. Bigint division
 * truncates, so 99.9% shows as 99 and only a limit really reached shows 100. A zero or negative limit is 100 when
 * anything is used, else 0.
 */
export function percentUsed(used: bigint, limit: bigint): number {
  if (limit <= 0n) {
    return used > 0n ? 100 : 0
  }
  if (used <= 0n) {
    return 0
  }
  const percent = (used * 100n) / limit
  return percent >= 100n ? 100 : Number(percent)
}
