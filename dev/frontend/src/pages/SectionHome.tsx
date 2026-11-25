import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { WhoAmICard } from './WhoAmICard'

export interface SectionLink {
  to: string
  label: string
  description: string
  /** A count or status shown on the card, if any. */
  value?: ReactNode
  testId?: string
}

/** A staff role's home: a heading, the signed-in profile, and a card per screen of the section. */
export function SectionHome({
  title,
  intro,
  links,
  children,
}: {
  title: string
  intro: string
  links: readonly SectionLink[]
  children?: ReactNode
}) {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        <p className="text-muted-foreground">{intro}</p>
      </div>
      <div className="grid gap-6 lg:grid-cols-2">
        {links.map((link) => (
          <Card key={link.to}>
            <CardHeader>
              <CardTitle>
                <Link to={link.to} className="underline-offset-4 hover:underline">
                  {link.label}
                </Link>
              </CardTitle>
              <CardDescription>{link.description}</CardDescription>
            </CardHeader>
            {link.value !== undefined && (
              <CardContent>
                <div className="text-3xl font-semibold tabular-nums" data-testid={link.testId}>
                  {link.value}
                </div>
              </CardContent>
            )}
          </Card>
        ))}
        {children}
        <WhoAmICard />
      </div>
    </div>
  )
}
