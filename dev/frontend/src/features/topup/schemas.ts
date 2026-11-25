import { z } from 'zod'
import { amountField } from '../transfer/schemas'

/** POST /topups: a positive rupee amount with at most 2 decimals (@ValidAmount); limits are the server's job. */
export const topupSchema = z.object({ amount: amountField })

export type TopupValues = z.input<typeof topupSchema>
export type TopupData = z.output<typeof topupSchema>
