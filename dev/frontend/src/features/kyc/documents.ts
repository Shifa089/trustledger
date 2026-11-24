import type { DocumentType } from '@/api/types'

/**
 * FR-KYC-2 checks made before any byte is sent: PDF, JPG or PNG, at most 5 MB. The server checks again (it reads
 * the file's magic bytes, not its name) and answers 413/415 if these ever drift.
 */

/** trustledger.kyc.documents.max-size: Spring's "5MB" is 5 × 1024 × 1024 bytes. */
export const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024

const ALLOWED: Readonly<Record<string, readonly string[]>> = {
  'application/pdf': ['.pdf'],
  'image/jpeg': ['.jpg', '.jpeg'],
  'image/png': ['.png'],
}

export const ACCEPT_ATTRIBUTE = Object.entries(ALLOWED)
  .flatMap(([mime, extensions]) => [mime, ...extensions])
  .join(',')

export const DOCUMENT_LABELS: Readonly<Record<DocumentType, string>> = {
  PAN_CARD: 'PAN card',
  ADDRESS_PROOF: 'Address proof',
  PHOTO: 'Photo',
}

/** Why a file can't be uploaded, or undefined when it may be. */
export function documentProblem(file: File): string | undefined {
  const name = file.name.toLowerCase()
  const extensionOk = Object.values(ALLOWED).some((extensions) => extensions.some((ext) => name.endsWith(ext)))
  // Some systems give no MIME type; then the extension decides, and the server's content check has the last word.
  const typeOk = file.type === '' || Object.hasOwn(ALLOWED, file.type)
  if (!extensionOk || !typeOk) {
    return 'Choose a PDF, JPG or PNG file.'
  }
  if (file.size === 0) {
    return 'This file is empty.'
  }
  if (file.size > MAX_DOCUMENT_BYTES) {
    return 'This file is larger than 5 MB.'
  }
  return undefined
}
