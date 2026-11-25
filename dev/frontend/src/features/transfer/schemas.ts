import { z } from 'zod'
import type { TransferRequest } from '@/api/types'
import { parseRupees, toApiAmount } from '@/lib/money'
import { MOBILE_PATTERN } from '../kyc/schemas'

/**
 * Client-side copies of the backend's payment rules, so mistakes show at once. The backend still checks everything
 * (it is the authority); its VALIDATION_FAILED violations land on the same fields if these ever drift.
 */

/** Money.parse: digits, optionally a dot and 1-2 decimals. No sign, exponent, grouping or spaces. */
export const AMOUNT_PATTERN = /^\d+(\.\d{1,2})?$/
/** Transfer.MAX_NOTE_LENGTH. */
export const MAX_NOTE_LENGTH = 140
/** Wallet IDs are UUIDs (any version: the backend uses v7). */
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
/** Far above every wallet limit; keeps a pasted 30-digit number from reaching bigint maths or the server. */
const MAX_AMOUNT_DIGITS = 12

/**
 * @ValidAmount: a positive rupee amount as the API takes it, normalised to two decimals ("250" → "250.00").
 * Zod 4 runs every check even after one fails, so each one guards itself against values the pattern refused.
 */
export const amountField = z
  .string()
  .trim()
  .min(1, 'Enter an amount.')
  .regex(AMOUNT_PATTERN, 'Enter an amount in rupees, for example 250 or 250.50 (at most 2 decimals).')
  .refine((value) => (value.split('.')[0] ?? '').replace(/^0+/, '').length <= MAX_AMOUNT_DIGITS, 'That amount is too large.')
  .refine((value) => !AMOUNT_PATTERN.test(value) || parseRupees(value) > 0n, 'The amount must be more than ₹0.')
  .transform((value) => (AMOUNT_PATTERN.test(value) ? toApiAmount(parseRupees(value)) : value))

export type RecipientType = 'mobile' | 'walletId'

/** Send money (FR-P-1). `ownWalletId`: sending to yourself is refused here as well as on the server. */
export function transferSchema(ownWalletId?: string) {
  return z
    .object({
      recipientType: z.enum(['mobile', 'walletId']),
      recipient: z.string().trim(),
      amount: amountField,
      note: z.string().trim().max(MAX_NOTE_LENGTH, `Use at most ${MAX_NOTE_LENGTH} characters.`),
    })
    .superRefine((data, ctx) => {
      if (data.recipientType === 'mobile') {
        if (!MOBILE_PATTERN.test(data.recipient)) {
          ctx.addIssue({
            code: 'custom',
            path: ['recipient'],
            message: 'Enter a 10-digit Indian mobile number starting with 6, 7, 8 or 9 (no +91).',
          })
        }
      } else if (!UUID_PATTERN.test(data.recipient)) {
        ctx.addIssue({
          code: 'custom',
          path: ['recipient'],
          message: 'Enter a wallet ID like 0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b.',
        })
      } else if (data.recipient.toLowerCase() === ownWalletId?.toLowerCase()) {
        ctx.addIssue({ code: 'custom', path: ['recipient'], message: 'You cannot send money to your own wallet.' })
      }
    })
}

export type TransferValues = z.input<ReturnType<typeof transferSchema>>
export type TransferData = z.output<ReturnType<typeof transferSchema>>

/** The POST /transfers body: exactly one recipient field; a blank note is left out. */
export function toTransferRequest(data: TransferData): TransferRequest {
  const request: TransferRequest =
    data.recipientType === 'mobile'
      ? { recipientMobile: data.recipient, amount: data.amount }
      : { recipientWalletId: data.recipient.toLowerCase(), amount: data.amount }
  if (data.note !== '') {
    request.note = data.note
  }
  return request
}
