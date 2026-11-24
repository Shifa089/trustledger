import { TriangleAlert } from 'lucide-react'

/**
 * Public demo safety: on every page, not dismissible. Razorpay runs with TEST keys only and every account is
 * a fake demo user.
 */
export function TestModeBanner() {
  return (
    <div
      role="status"
      aria-label="Test mode"
      className="sticky top-0 z-50 flex items-center justify-center gap-2 bg-amber-400 px-4 py-1.5 text-center text-sm font-semibold text-amber-950"
    >
      <TriangleAlert aria-hidden className="size-4" />
      TEST MODE: no real money. Demo accounts and fake data only.
    </div>
  )
}
