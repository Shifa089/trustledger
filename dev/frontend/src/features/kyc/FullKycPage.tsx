import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { useKycOverview, useSubmitFullKyc } from '@/api/kyc'
import type { DocumentType } from '@/api/types'
import { ErrorAlert } from '@/components/ErrorAlert'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Textarea } from '@/components/ui/input'
import { DemoDataNotice } from './DemoDataNotice'
import { DocumentUploads } from './DocumentUploads'
import { nextStep } from './kycState'
import { fullKycSchema, MAX_ADDRESS_LENGTH, type FullKycValues } from './schemas'

interface OpenCase {
  id: string
  required: readonly DocumentType[] | undefined
}

/**
 * /customer/kyc/full (FR-KYC-2): step 1 the address (creates a SUBMITTED case), step 2 the documents. A customer
 * who comes back to a SUBMITTED case goes straight to step 2.
 */
export function FullKycPage() {
  const overview = useKycOverview()
  const [created, setCreated] = useState<OpenCase | undefined>()

  let body
  if (created !== undefined) {
    body = <UploadStep openCase={created} />
  } else if (overview.isPending) {
    body = <p className="text-sm text-muted-foreground">Loading your KYC status…</p>
  } else if (overview.isError) {
    body = <ErrorAlert error={overview.error} title="Couldn't load your KYC status" />
  } else {
    const next = nextStep(overview.data)
    switch (next.step) {
      case 'min':
        body = (
          <p className="text-sm">
            Complete{' '}
            <Link to="/customer/kyc/min" className="underline underline-offset-4">
              minimum KYC
            </Link>{' '}
            first.
          </p>
        )
        break
      case 'upload':
        body = <UploadStep openCase={{ id: next.fullCase.id, required: undefined }} />
        break
      case 'review':
      case 'done':
        body = (
          <p className="text-sm">
            Nothing to do here: see your{' '}
            <Link to="/customer/kyc" className="underline underline-offset-4">
              KYC status
            </Link>
            .
          </p>
        )
        break
      case 'full':
      case 'rejected':
        body = (
          <AddressStep
            onCreated={(fullCase) => {
              setCreated({ id: fullCase.id, required: fullCase.requiredDocuments })
            }}
          />
        )
        break
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Full KYC</h1>
        <p className="text-muted-foreground">
          Your address and documents, reviewed by two compliance officers.{' '}
          <Link to="/customer/kyc" className="underline underline-offset-4">
            Back to KYC status
          </Link>
        </p>
      </div>
      <DemoDataNotice />
      {body}
    </div>
  )
}

function AddressStep({ onCreated }: { onCreated: (fullCase: { id: string; requiredDocuments: DocumentType[] }) => void }) {
  const submit = useSubmitFullKyc()
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FullKycValues>({ resolver: zodResolver(fullKycSchema), defaultValues: { address: '' }, mode: 'onTouched' })

  const onSubmit = handleSubmit(async ({ address }) => {
    try {
      onCreated(await submit.mutateAsync(address.trim()))
    } catch {
      // Shown below from submit.error.
    }
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle>Step 1 of 2: address</CardTitle>
        <CardDescription>Stored encrypted and shown to officers only.</CardDescription>
      </CardHeader>
      <CardContent>
        <form noValidate onSubmit={(event) => void onSubmit(event)} className="flex max-w-lg flex-col gap-5" aria-label="Full KYC address">
          <FormField id="address" label="Address" hint={`At most ${MAX_ADDRESS_LENGTH} characters.`} error={errors.address?.message}>
            <Textarea rows={3} autoComplete="off" {...register('address')} />
          </FormField>
          {submit.isError && <ErrorAlert error={submit.error} title="Full KYC was not accepted" />}
          <div>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Submitting…' : 'Continue to documents'}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  )
}

function UploadStep({ openCase }: { openCase: OpenCase }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Step 2 of 2: documents</CardTitle>
        <CardDescription>
          {openCase.required === undefined
            ? 'Upload your PAN card and address proof (and a photo if asked). Uploading a type again adds another copy.'
            : 'Upload the required documents. Each file is checked before it is sent.'}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <DocumentUploads caseId={openCase.id} required={openCase.required} />
        <p className="text-sm">
          <Link to="/customer/kyc" className="underline underline-offset-4">
            Done: back to KYC status
          </Link>
        </p>
      </CardContent>
    </Card>
  )
}
