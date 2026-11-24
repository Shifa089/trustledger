import { z } from 'zod'
import { isAtLeastYearsOld, isIsoDay } from '@/lib/dates'

/**
 * Client-side copies of the backend's KYC rules (FR-KYC-1/2), so mistakes show at once. The backend still checks
 * everything: these only spare a round trip, and a 400 VALIDATION_FAILED is shown if they ever drift.
 */

/** @ValidPan: 5 letters, 4 digits, 1 letter; the 4th letter is the holder type (P for a person). */
export const PAN_PATTERN = /^[A-Z]{3}[ABCFGHLJPT][A-Z][0-9]{4}[A-Z]$/
/** @ValidIndianMobile: 10 digits starting 6-9, no +91 or spaces. */
export const MOBILE_PATTERN = /^[6-9][0-9]{9}$/
/** trustledger.kyc.min-age-years; the backend refuses less than 18 at startup. */
export const MIN_AGE_YEARS = 18
/** KycCase.MAX_NAME_LENGTH and MAX_ADDRESS_LENGTH. */
export const MAX_NAME_LENGTH = 100
export const MAX_ADDRESS_LENGTH = 500

/** Minimum KYC; `today` is the Indian calendar day the age is counted on (yyyy-MM-dd). */
export function minKycSchema(today: string) {
  return z.object({
    fullName: z
      .string()
      .trim()
      .min(1, 'Enter your full name.')
      .max(MAX_NAME_LENGTH, `Use at most ${MAX_NAME_LENGTH} characters.`),
    dateOfBirth: z
      .string()
      .min(1, 'Enter your date of birth.')
      .refine(isIsoDay, 'Enter a valid date.')
      .refine((dob) => dob < today, 'The date of birth must be in the past.')
      .refine((dob) => dob >= '1900-01-01', 'Enter a valid date.')
      .refine((dob) => isAtLeastYearsOld(dob, MIN_AGE_YEARS, today), `You must be at least ${MIN_AGE_YEARS} years old.`),
    mobile: z
      .string()
      .trim()
      .regex(MOBILE_PATTERN, 'Enter a 10-digit Indian mobile number starting with 6, 7, 8 or 9 (no +91).'),
    pan: z
      .string()
      .trim()
      .toUpperCase()
      .regex(PAN_PATTERN, 'Enter a valid PAN, for example ABCPE1234F.'),
  })
}

export type MinKycValues = z.input<ReturnType<typeof minKycSchema>>
export type MinKycData = z.output<ReturnType<typeof minKycSchema>>

export const fullKycSchema = z.object({
  address: z
    .string()
    .trim()
    .min(1, 'Enter your address.')
    .max(MAX_ADDRESS_LENGTH, `Use at most ${MAX_ADDRESS_LENGTH} characters.`),
})

export type FullKycValues = z.input<typeof fullKycSchema>
