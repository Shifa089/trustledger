/** Small helpers shared by the staff screens. */

/** A zero-based page number from the URL's 1-based `?page=`; anything odd is page 0. */
export function pageFrom(search: URLSearchParams): number {
  const page = Number.parseInt(search.get('page') ?? '1', 10)
  return Number.isSafeInteger(page) && page >= 1 ? page - 1 : 0
}

/** Only http(s) links are ever rendered: a javascript: or data: URL from a broken backend must not become a link. */
export function isSafeUrl(url: string): boolean {
  try {
    const protocol = new URL(url).protocol
    return protocol === 'https:' || protocol === 'http:'
  } catch {
    return false
  }
}

/** Saves a downloaded file (the STR CSV) through a temporary object URL; no new dependency. */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  anchor.rel = 'noopener'
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => {
    URL.revokeObjectURL(url)
  }, 0)
}
