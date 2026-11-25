/**
 * Calendar days in India. The backend counts limit days, the history's from/to and the minimum age in
 * Asia/Kolkata (SPEC §2), so the browser does too, whatever the device's time zone. A day is an ISO string
 * "yyyy-MM-dd": such strings compare in date order and never shift by a time zone the way a Date at midnight does.
 */

export const INDIA_TIME_ZONE = 'Asia/Kolkata'

const ISO_DAY = /^(\d{4})-(\d{2})-(\d{2})$/

const dayInIndia = new Intl.DateTimeFormat('en-CA', {
  timeZone: INDIA_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/** The Indian calendar day of an instant: 2026-11-23T18:30:00Z → "2026-11-24". */
export function indianDay(instant: Date | string): string {
  return dayInIndia.format(typeof instant === 'string' ? new Date(instant) : instant)
}

/** Today in India. */
export function todayInIndia(now: Date = new Date()): string {
  return indianDay(now)
}

function parts(day: string): [number, number, number] {
  const [y = 0, m = 0, d = 0] = day.split('-').map(Number)
  return [y, m, d]
}

/** Whether `value` is a real calendar day written yyyy-MM-dd (2026-02-30 is not). */
export function isIsoDay(value: string): boolean {
  if (!ISO_DAY.test(value)) {
    return false
  }
  const [y, m, d] = parts(value)
  const date = new Date(Date.UTC(y, m - 1, d))
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d
}

/** "2026-11-24" plus -29 days → "2026-10-26" (UTC arithmetic on a date only, so no DST or zone shift). */
export function addDays(day: string, days: number): string {
  if (!isIsoDay(day)) {
    throw new RangeError(`Not a day: "${day}"`)
  }
  const [y, m, d] = parts(day)
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10)
}

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0
}

/**
 * Whether someone born on `dateOfBirth` is at least `years` old on `today` (both yyyy-MM-dd). Same rule as the
 * backend's AgePolicy (LocalDate.plusYears): a 29 February birthday turns N on 28 February in a non-leap year.
 */
export function isAtLeastYearsOld(dateOfBirth: string, years: number, today: string): boolean {
  if (!isIsoDay(dateOfBirth) || !isIsoDay(today)) {
    return false
  }
  const [y, m, d] = parts(dateOfBirth)
  const year = y + years
  const day = m === 2 && d === 29 && !isLeapYear(year) ? 28 : d
  const birthday = `${String(year).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(day).padStart(2, '0')}`
  return birthday <= today
}

const dateTimeInIndia = new Intl.DateTimeFormat('en-IN', {
  timeZone: INDIA_TIME_ZONE,
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

/** An API instant for display, in Indian time: "24 Nov 2026, 03:30 pm". */
export function formatDateTime(instant: string): string {
  return dateTimeInIndia.format(new Date(instant))
}

const shortDay = new Intl.DateTimeFormat('en-IN', { timeZone: 'UTC', day: 'numeric', month: 'short' })

/** "2026-11-24" → "24 Nov". A day, not an instant, so it is formatted in UTC to avoid any shift. */
export function formatShortDay(day: string): string {
  const [y, m, d] = parts(day)
  return shortDay.format(new Date(Date.UTC(y, m - 1, d)))
}

/**
 * The instant an Indian calendar day starts, for APIs that filter by Instant (the audit log):
 * "2026-11-24" → "2026-11-23T18:30:00.000Z". India has no daylight saving, so the offset is always +05:30.
 */
export function startOfIndianDay(day: string): string {
  return new Date(`${day}T00:00:00+05:30`).toISOString()
}
