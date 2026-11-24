import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo } from 'react'
import { useForm, type FieldPath } from 'react-hook-form'
import { isApiError } from '@/api/errors'
import { useSubmitMinKyc } from '@/api/kyc'
import { ErrorAlert } from '@/components/ErrorAlert'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { todayInIndia } from '@/lib/dates'
import { minKycSchema, type MinKycData, type MinKycValues } from './schemas'

const FIELDS: readonly FieldPath<MinKycValues>[] = ['fullName', 'dateOfBirth', 'mobile', 'pan']

/**
 * FR-KYC-1: name, date of birth, mobile and PAN. Valid data makes the user MIN_KYC at once (the OTP is simulated
 * by the backend) and creates the wallet. Errors show under each field; the server's own field errors
 * (VALIDATION_FAILED violations) land on the same fields.
 */
export function MinKycForm({ today = todayInIndia(), onDone }: { today?: string; onDone: () => void }) {
  const schema = useMemo(() => minKycSchema(today), [today])
  const submit = useSubmitMinKyc()
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<MinKycValues, unknown, MinKycData>({
    resolver: zodResolver(schema),
    defaultValues: { fullName: '', dateOfBirth: '', mobile: '', pan: '' },
    mode: 'onTouched',
  })

  const onSubmit = handleSubmit(async (data) => {
    try {
      await submit.mutateAsync(data)
      onDone()
    } catch (error) {
      if (isApiError(error)) {
        for (const violation of error.violations) {
          const field = FIELDS.find((name) => name === violation.field)
          if (field !== undefined) {
            setError(field, { message: violation.message })
          }
        }
      }
    }
  })

  return (
    <form noValidate onSubmit={(event) => void onSubmit(event)} className="flex max-w-lg flex-col gap-5" aria-label="Minimum KYC">
      <FormField id="fullName" label="Full name" error={errors.fullName?.message}>
        <Input autoComplete="off" {...register('fullName')} />
      </FormField>
      <FormField
        id="dateOfBirth"
        label="Date of birth"
        hint={'You must be 18 or older. Date in India time.'}
        error={errors.dateOfBirth?.message}
      >
        <Input type="date" max={today} autoComplete="off" {...register('dateOfBirth')} />
      </FormField>
      <FormField id="mobile" label="Mobile number" hint="10 digits, without +91." error={errors.mobile?.message}>
        <Input type="tel" inputMode="numeric" maxLength={10} autoComplete="off" {...register('mobile')} />
      </FormField>
      <FormField id="pan" label="PAN" hint="10 characters, for example ABCPE1234F." error={errors.pan?.message}>
        <Input className="uppercase" maxLength={10} autoComplete="off" spellCheck={false} {...register('pan')} />
      </FormField>
      {submit.isError && <ErrorAlert error={submit.error} title="Minimum KYC was not accepted" />}
      <div>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? 'Submitting…' : 'Submit minimum KYC'}
        </Button>
      </div>
    </form>
  )
}
