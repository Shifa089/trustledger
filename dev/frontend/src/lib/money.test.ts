import { formatPaise, formatRupees, parseRupees, toApiAmount } from './money'

describe('money', () => {
  it.each([
    ['0', 0n],
    ['1', 100n],
    ['1.5', 150n],
    ['1250.50', 125050n],
    ['0.01', 1n],
    ['-20.05', -2005n],
    // Larger than Number.MAX_SAFE_INTEGER paise: exact with bigint, wrong with floats.
    ['90071992547409.93', 9007199254740993n],
  ])('parses %s to %s paise', (input, paise) => {
    expect(parseRupees(input)).toBe(paise)
  })

  it.each(['', 'abc', '1.234', '1,000', '1e3', '.5', '₹10', 'NaN'])('rejects %j', (input) => {
    expect(() => parseRupees(input)).toThrow(RangeError)
  })

  it('adds without floating point error', () => {
    expect(toApiAmount(parseRupees('0.10') + parseRupees('0.20'))).toBe('0.30')
  })

  it.each([
    [0n, '0.00'],
    [5n, '0.05'],
    [125050n, '1250.50'],
    [-2005n, '-20.05'],
  ])('formats %s paise for the API as %s', (paise, expected) => {
    expect(toApiAmount(paise)).toBe(expected)
  })

  it.each([
    [0n, '₹0.00'],
    [99999n, '₹999.99'],
    [100000n, '₹1,000.00'],
    [12345678n, '₹1,23,456.78'],
    [20000000n, '₹2,00,000.00'],
    [-5000000n, '-₹50,000.00'],
  ])('displays %s paise with Indian grouping as %s', (paise, expected) => {
    expect(formatPaise(paise)).toBe(expected)
  })

  it('formats an API amount string', () => {
    expect(formatRupees('50000.00')).toBe('₹50,000.00')
  })
})
