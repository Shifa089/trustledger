import { formatMoney, percentUsed, toPaise } from './money'

describe('formatMoney', () => {
  it.each([
    [150050, '₹1,500.50'],
    [5, '₹0.05'],
    [0, '₹0.00'],
    [100, '₹1.00'],
    [99, '₹0.99'],
    [-150050, '-₹1,500.50'],
    [20000000, '₹2,00,000.00'],
  ])('formats %s paise as %s', (paise, expected) => {
    expect(formatMoney(paise)).toBe(expected)
    expect(formatMoney(BigInt(paise))).toBe(expected)
  })

  it('formats large values exactly with bigint', () => {
    // ₹1 lakh crore and one paisa: far more paise than Number.MAX_SAFE_INTEGER.
    expect(formatMoney(1_000_000_000_000_001n)).toBe('₹1,00,00,00,00,00,000.01')
    expect(formatMoney(9007199254740993n)).toBe('₹9,00,71,99,25,47,409.93')
    expect(formatMoney(Number.MAX_SAFE_INTEGER)).toBe('₹9,00,71,99,25,47,409.91')
  })

  it.each([1.5, 0.1, Number.MAX_SAFE_INTEGER + 2, Number.NaN, Number.POSITIVE_INFINITY])(
    'refuses %s, which is not a whole number of paise',
    (value) => {
      expect(() => formatMoney(value)).toThrow(RangeError)
    },
  )

  it('drops the paise only when asked (axis ticks)', () => {
    expect(formatMoney(150050, { wholeRupees: true })).toBe('₹1,500')
    expect(formatMoney(-150050, { wholeRupees: true })).toBe('-₹1,500')
  })

  it('converts safe integers to bigint unchanged', () => {
    expect(toPaise(42)).toBe(42n)
    expect(toPaise(7n)).toBe(7n)
  })
})

describe('percentUsed', () => {
  it.each([
    [0n, 1_000_000n, 0],
    [250_000n, 1_000_000n, 25],
    [999_999n, 1_000_000n, 99],
    [1_000_000n, 1_000_000n, 100],
    [2_000_000n, 1_000_000n, 100],
    [-5n, 100n, 0],
    [0n, 0n, 0],
    [1n, 0n, 100],
  ])('%s of %s is %s%%', (used, limit, percent) => {
    expect(percentUsed(used, limit)).toBe(percent)
  })
})
