import { Landmark } from 'lucide-react'
import { Link } from 'react-router'

export function Logo({ to = '/' }: { to?: string }) {
  return (
    <Link to={to} className="flex items-center gap-2 font-semibold tracking-tight">
      <Landmark aria-hidden className="size-5 text-primary" />
      TrustLedger
    </Link>
  )
}
