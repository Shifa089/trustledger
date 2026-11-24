import { ExternalLink, PlayCircle } from 'lucide-react'
import { ROLE_LABELS, type Role } from '@/auth/roles'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { config } from '@/config'

interface DemoAccount {
  username: string
  role: Role
  note: string
}

/** The fixed demo users of the Keycloak realm (SPEC §8 M2). All fake people, all fake data. */
const DEMO_ACCOUNTS: readonly DemoAccount[] = [
  { username: 'customer1', role: 'CUSTOMER', note: 'Full KYC, ₹50,000 with the demo seed' },
  { username: 'customer2', role: 'CUSTOMER', note: 'Full KYC, ₹10,000 with the demo seed' },
  { username: 'officer1', role: 'COMPLIANCE_OFFICER', note: 'KYC maker or checker, AML cases' },
  { username: 'officer2', role: 'COMPLIANCE_OFFICER', note: 'The second officer for maker-checker' },
  { username: 'admin1', role: 'ADMIN', note: 'Reconciliation, ledger balances' },
  { username: 'auditor1', role: 'AUDITOR', note: 'Audit log and chain verification' },
]

const FEATURES: readonly { title: string; text: string }[] = [
  { title: 'Double-entry ledger', text: 'Every rupee is a balanced, append-only journal entry. Zero drift under load.' },
  { title: 'KYC with maker-checker', text: 'Minimum and full KYC; two different officers verify and approve.' },
  { title: 'Safe money movement', text: 'Idempotency keys, row locks in a fixed order, high-value holds for review.' },
  { title: 'AML rules service', text: 'Velocity, structuring, large-amount and fan-out rules over Kafka events.' },
  { title: 'Tamper-evident audit', text: 'Hash-chained audit events with masked personal data and chain verification.' },
  { title: 'Privacy by design', text: 'Encrypted personal data, consent records, data export and erasure.' },
]

export function LandingPage() {
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-12 px-6 py-10">
      <section className="flex flex-col gap-4">
        <Badge variant="outline">Fintech portfolio project</Badge>
        <h1 className="text-4xl font-bold tracking-tight">TrustLedger: a regulated digital wallet</h1>
        <p className="max-w-3xl text-lg text-muted-foreground">
          A prepaid wallet where customers complete KYC, add money through Razorpay (test mode) and send money to
          each other. Every rupee is recorded in a double-entry ledger, checked by AML rules and written to a
          tamper-evident audit trail.
        </p>
        <div className="flex flex-wrap gap-3">
          {config.githubUrl !== undefined && (
            <Button asChild variant="outline">
              <a href={config.githubUrl} target="_blank" rel="noopener noreferrer">
                <ExternalLink aria-hidden />
                Source on GitHub
              </a>
            </Button>
          )}
          {config.videoUrl !== undefined && (
            <Button asChild variant="outline">
              <a href={config.videoUrl} target="_blank" rel="noopener noreferrer">
                <PlayCircle aria-hidden />
                Watch the demo video
              </a>
            </Button>
          )}
        </div>
      </section>

      <section aria-labelledby="features" className="flex flex-col gap-4">
        <h2 id="features" className="text-2xl font-semibold">
          What it does
        </h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map((feature) => (
            <Card key={feature.title} className="gap-2">
              <CardHeader>
                <CardTitle>{feature.title}</CardTitle>
              </CardHeader>
              <CardContent className="text-sm text-muted-foreground">{feature.text}</CardContent>
            </Card>
          ))}
        </div>
      </section>

      <section aria-labelledby="architecture" className="flex flex-col gap-4">
        <h2 id="architecture" className="text-2xl font-semibold">
          Architecture
        </h2>
        <p className="max-w-3xl text-muted-foreground">
          A Spring Boot modular monolith (core-app) and a separate risk and AML service, connected by Kafka through
          a transactional outbox. Keycloak issues the tokens; PostgreSQL holds each module&apos;s own schema.
        </p>
        <img
          src="/architecture.svg"
          alt="Architecture: the browser signs in with Keycloak and calls core-app and the AML service; core-app publishes events to Kafka, which the AML service consumes; each service has its own PostgreSQL schema; core-app uses Redis, S3 and Razorpay."
          className="w-full rounded-xl border bg-white p-4"
          width={960}
          height={480}
        />
      </section>

      <section aria-labelledby="demo-accounts" className="flex flex-col gap-4">
        <h2 id="demo-accounts" className="text-2xl font-semibold">
          Demo accounts
        </h2>
        <Card>
          <CardHeader>
            <CardTitle>Sign in as any of these fake users</CardTitle>
            <CardDescription>
              Each role sees its own navigation. Locally the password is TRUSTLEDGER_DEV_USER_PASSWORD from .env;
              officers and the admin also need an OTP (see the README).
            </CardDescription>
          </CardHeader>
          <CardContent>
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground">
                <tr>
                  <th scope="col" className="py-2 font-medium">
                    Username
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    Role
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    What to try
                  </th>
                </tr>
              </thead>
              <tbody>
                {DEMO_ACCOUNTS.map((account) => (
                  <tr key={account.username} className="border-t">
                    <td className="py-2 font-mono">{account.username}</td>
                    <td className="py-2">{ROLE_LABELS[account.role]}</td>
                    <td className="py-2 text-muted-foreground">{account.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      </section>
    </main>
  )
}
