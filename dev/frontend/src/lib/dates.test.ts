import { addDays, formatShortDay, indianDay, isAtLeastYearsOld, isIsoDay, todayInIndia } from './dates'

describe('dates in India', () => {
  it('takes the Indian calendar day, not UTC', () => {
    // 18:29:59Z is 23:59:59 IST; 18:30Z is midnight IST on the next day.
    expect(indianDay('2026-11-23T18:29:59Z')).toBe('2026-11-23')
    expect(indianDay('2026-11-23T18:30:00Z')).toBe('2026-11-24')
    expect(todayInIndia(new Date('2026-12-31T19:00:00Z'))).toBe('2027-01-01')
  })

  it.each([
    ['2026-11-24', true],
    ['2024-02-29', true],
    ['2026-02-29', false],
    ['2026-13-01', false],
    ['26-11-24', false],
    ['', false],
  ])('%j is a day: %s', (value, expected) => {
    expect(isIsoDay(value)).toBe(expected)
  })

  it('adds days across months and years', () => {
    expect(addDays('2026-11-24', -29)).toBe('2026-10-26')
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(addDays('2024-03-01', -1)).toBe('2024-02-29')
    expect(() => addDays('nope', 1)).toThrow(RangeError)
  })

  it('is 18 on the 18th birthday, not the day before', () => {
    expect(isAtLeastYearsOld('2008-11-24', 18, '2026-11-24')).toBe(true)
    expect(isAtLeastYearsOld('2008-11-25', 18, '2026-11-24')).toBe(false)
  })

  it('turns 18 on 28 February when born on 29 February (as LocalDate.plusYears)', () => {
    expect(isAtLeastYearsOld('2008-02-29', 18, '2026-02-27')).toBe(false)
    expect(isAtLeastYearsOld('2008-02-29', 18, '2026-02-28')).toBe(true)
    expect(isAtLeastYearsOld('2004-02-29', 20, '2024-02-28')).toBe(false)
    expect(isAtLeastYearsOld('2004-02-29', 20, '2024-02-29')).toBe(true)
  })

  it('refuses a malformed date of birth', () => {
    expect(isAtLeastYearsOld('2008-02-30', 18, '2026-11-24')).toBe(false)
  })

  it('formats a day without shifting it', () => {
    expect(formatShortDay('2026-11-24')).toBe('24 Nov')
  })
})
