import type { ReactNode } from 'react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { WhoAmICard } from './WhoAmICard'

/** The F1 shell of a role's home: a heading, what is coming, and the signed-in profile. */
export function SectionHome({
  title,
  intro,
  upcoming,
  children,
}: {
  title: string
  intro: string
  upcoming: readonly string[]
  children?: ReactNode
}) {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        <p className="text-muted-foreground">{intro}</p>
      </div>
      <div className="grid gap-6 lg:grid-cols-2">
        <WhoAmICard />
        <Card>
          <CardHeader>
            <CardTitle>Coming next</CardTitle>
            <CardDescription>Screens built in the next frontend days.</CardDescription>
          </CardHeader>
          <CardContent>
            <ul className="list-disc pl-5 text-sm">
              {upcoming.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </CardContent>
        </Card>
        {children}
      </div>
    </div>
  )
}
