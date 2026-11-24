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

/** 12345678n paise → "₹1,23,456.78". */
export function formatPaise(paise: bigint): string {
  const negative = paise < 0n
  const abs = negative ? -paise : paise
  const rupees = groupIndian((abs / 100n).toString())
  const fraction = (abs % 100n).toString().padStart(2, '0')
  return `${negative ? '-' : ''}₹${rupees}.${fraction}`
}

/** An API amount string straight to display: "123456.78" → "₹1,23,456.78". */
export function formatRupees(amount: string): string {
  return formatPaise(parseRupees(amount))
}
