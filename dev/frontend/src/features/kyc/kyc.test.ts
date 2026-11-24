import type { KycCase, KycOverview } from '@/api/types'
import { documentProblem, MAX_DOCUMENT_BYTES } from './documents'
import { nextStep } from './kycState'
import { minKycSchema, PAN_PATTERN } from './schemas'

function kycCase(level: KycCase['level'], status: KycCase['status']): KycCase {
  return { id: `${level}-${status}`, level, status, maskedPan: null, rejectionReason: null, createdAt: '', updatedAt: '' }
}

describe('nextStep', () => {
  it.each<[KycOverview, string]>([
    [{ currentLevel: 'NONE', cases: [] }, 'min'],
    [{ currentLevel: 'MIN', cases: [kycCase('MIN', 'APPROVED')] }, 'full'],
    [{ currentLevel: 'MIN', cases: [kycCase('MIN', 'APPROVED'), kycCase('FULL', 'SUBMITTED')] }, 'upload'],
    [{ currentLevel: 'MIN', cases: [kycCase('MIN', 'APPROVED'), kycCase('FULL', 'VERIFIED')] }, 'review'],
    [{ currentLevel: 'MIN', cases: [kycCase('MIN', 'APPROVED'), kycCase('FULL', 'REJECTED')] }, 'rejected'],
    [
      { currentLevel: 'MIN', cases: [kycCase('FULL', 'REJECTED'), kycCase('FULL', 'SUBMITTED')] },
      'upload', // the newest full case decides
    ],
    [{ currentLevel: 'FULL', cases: [kycCase('FULL', 'APPROVED')] }, 'done'],
  ])('%j → %s', (overview, step) => {
    expect(nextStep(overview).step).toBe(step)
  })
})

describe('KYC rules mirror the backend', () => {
  it.each([
    ['ABCPE1234F', true],
    ['ABCCE1234F', true], // company
    ['ABCXE1234F', false], // X is not a holder type
    ['ABCPE1234', false],
    ['abcpe1234f', false], // the schema upper-cases first
    ['ABCPE12345', false],
  ])('PAN %s valid: %s', (pan, valid) => {
    expect(PAN_PATTERN.test(pan)).toBe(valid)
  })

  it('upper-cases and trims the PAN and the name before checking', () => {
    const parsed = minKycSchema('2026-11-24').parse({
      fullName: '  Test Customer ',
      dateOfBirth: '1995-04-15',
      mobile: '9876543210',
      pan: ' abcpe1234f ',
    })
    expect(parsed).toEqual({ fullName: 'Test Customer', dateOfBirth: '1995-04-15', mobile: '9876543210', pan: 'ABCPE1234F' })
  })

  it('refuses a date that does not exist or lies in the future', () => {
    const schema = minKycSchema('2026-11-24')
    const base = { fullName: 'A', mobile: '9876543210', pan: 'ABCPE1234F' }
    expect(schema.safeParse({ ...base, dateOfBirth: '1995-02-30' }).success).toBe(false)
    expect(schema.safeParse({ ...base, dateOfBirth: '2026-11-25' }).success).toBe(false)
    expect(schema.safeParse({ ...base, dateOfBirth: '1899-12-31' }).success).toBe(false)
  })
})

describe('documentProblem', () => {
  const sized = (name: string, type: string, size: number) => {
    const f = new File(['x'], name, { type })
    Object.defineProperty(f, 'size', { value: size })
    return f
  }

  it.each([
    [sized('a.pdf', 'application/pdf', 10), undefined],
    [sized('a.JPEG', 'image/jpeg', 10), undefined],
    [sized('a.png', '', 10), undefined], // no MIME type: the extension decides
    [sized('a.png', 'image/png', MAX_DOCUMENT_BYTES), undefined],
    [sized('a.png', 'image/png', MAX_DOCUMENT_BYTES + 1), 'This file is larger than 5 MB.'],
    [sized('a.png', 'image/png', 0), 'This file is empty.'],
    [sized('a.gif', 'image/gif', 10), 'Choose a PDF, JPG or PNG file.'],
    [sized('a.pdf', 'text/html', 10), 'Choose a PDF, JPG or PNG file.'],
  ])('%o → %s', (file, problem) => {
    expect(documentProblem(file)).toBe(problem)
  })
})
