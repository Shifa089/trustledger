/** Response shapes of the endpoints the app calls (SPEC §5). Personal fields arrive masked (FR-PR-1). */

/** GET /api/v1/me (core-app MeController.MeResponse). */
export interface Me {
  id: string
  email: string | null
  phone: string | null
  fullName: string | null
  status: string
  roles: string[]
}

/** A page of results, as the paginated list endpoints return it. */
export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
