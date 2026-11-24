import { Outlet } from 'react-router'
import { TestModeBanner } from '@/components/TestModeBanner'

/** Wraps every route, public or signed in, so the TEST MODE banner is on every page. */
export function RootLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground">
      <TestModeBanner />
      <Outlet />
    </div>
  )
}
