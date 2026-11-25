import { Fragment, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useAuditEvents, type AuditEvent } from '@/api/operations'
import { ErrorAlert } from '@/components/ErrorAlert'
import { FormField } from '@/components/FormField'
import {
  EmptyState,
  Id,
  LoadingRows,
  PageHeader,
  Pager,
  tableClass,
  tdClass,
  thClass,
} from '@/components/staff'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { formatDateTime, todayInIndia } from '@/lib/dates'
import { ACTIONS, AUDIT_FIELDS, ENTITY_TYPES, toAuditQuery, type AuditField } from './auditQuery'
import { AuditVerify } from './AuditVerify'

/**
 * /audit/events (FR-A-1/2), AUDITOR only, read-only: the audit trail with filters, and "Verify chain". Before and
 * after values arrive masked by the server; this page never unmasks anything.
 */
export function AuditEventsPage() {
  const [search, setSearch] = useSearchParams()
  const { query, problems } = toAuditQuery(search)
  const valid = Object.keys(problems).length === 0
  const events = useAuditEvents(query, valid)
  const today = todayInIndia()

  const update = (key: AuditField, v: string) => {
    const next = new URLSearchParams(search)
    if (v === '') next.delete(key)
    else next.set(key, v)
    next.delete('page')
    setSearch(next)
  }
  const filtered = AUDIT_FIELDS.some((key) => search.has(key))

  const field = (key: AuditField, label: string, props: Record<string, unknown> = {}) => (
    <FormField id={`audit-${key}`} label={label} error={problems[key]}>
      <Input
        value={search.get(key) ?? ''}
        autoComplete="off"
        spellCheck={false}
        onChange={(event) => {
          update(key, event.target.value)
        }}
        {...props}
      />
    </FormField>
  )

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Audit log"
        intro="Every state-changing action, newest first, hash-chained to the one before. Read-only; dates are India time."
      />
      <AuditVerify />
      <Card>
        <CardContent>
          <form
            aria-label="Filters"
            className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3"
            onSubmit={(event) => {
              event.preventDefault()
            }}
          >
            {field('entityType', 'Entity type', { list: 'audit-entity-types' })}
            {field('entityId', 'Entity ID')}
            {field('action', 'Action', { list: 'audit-actions' })}
            {field('actorId', 'Actor ID')}
            {field('from', 'From', { type: 'date', max: today })}
            {field('to', 'To', { type: 'date', max: today })}
            <datalist id="audit-entity-types">
              {ENTITY_TYPES.map((v) => (
                <option key={v} value={v} />
              ))}
            </datalist>
            <datalist id="audit-actions">
              {ACTIONS.map((v) => (
                <option key={v} value={v} />
              ))}
            </datalist>
            <div className="flex items-end">
              <Button
                type="button"
                variant="outline"
                disabled={!filtered}
                onClick={() => {
                  setSearch(new URLSearchParams())
                }}
              >
                Clear filters
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>

      <section aria-label="Audit events" aria-busy={events.isFetching} className="flex flex-col gap-4">
        {!valid ? null : events.isPending ? (
          <LoadingRows label="Loading audit events" />
        ) : events.isError ? (
          <ErrorAlert error={events.error} title="Couldn't load audit events" />
        ) : events.data.items.length === 0 ? (
          <EmptyState>{filtered ? 'No events match these filters.' : 'No audit events yet.'}</EmptyState>
        ) : (
          <>
            <EventsTable events={events.data.items} />
            <Pager
              page={query.page}
              totalPages={events.data.totalPages}
              totalElements={events.data.totalElements}
              noun="events"
              onPage={(page) => {
                const next = new URLSearchParams(search)
                if (page > 0) next.set('page', String(page + 1))
                else next.delete('page')
                setSearch(next)
              }}
            />
          </>
        )}
      </section>
    </div>
  )
}

function json(value: unknown): string {
  return value === null || value === undefined ? '-' : JSON.stringify(value, null, 2)
}

function EventsTable({ events }: { events: readonly AuditEvent[] }) {
  const [open, setOpen] = useState<number | undefined>()
  return (
    <div className="overflow-x-auto">
      <table className={tableClass}>
        <caption className="sr-only">Audit events</caption>
        <thead className="border-b text-muted-foreground">
          <tr>
            <th scope="col" className={thClass}>Seq</th>
            <th scope="col" className={thClass}>Time</th>
            <th scope="col" className={thClass}>Actor</th>
            <th scope="col" className={thClass}>Action</th>
            <th scope="col" className={thClass}>Entity</th>
            <th scope="col" className={thClass}>Correlation</th>
            <th scope="col" className={thClass}>
              <span className="sr-only">Details</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {events.map((event) => {
            const expanded = open === event.seq
            return (
              <Fragment key={event.seq}>
                <tr className="border-b last:border-0">
                  <td className={`${tdClass} tabular-nums`}>#{event.seq}</td>
                  <td className={tdClass}>{formatDateTime(event.occurredAt)}</td>
                  <td className={tdClass}>
                    {event.actorId === null ? 'System' : <Id value={event.actorId} />}
                    {event.actorRole !== null && (
                      <span className="block text-xs text-muted-foreground">{event.actorRole}</span>
                    )}
                  </td>
                  <td className={`${tdClass} font-mono text-xs`}>{event.action}</td>
                  <td className={tdClass}>
                    <span className="font-mono text-xs">{event.entityType}</span>
                    {event.entityId !== null && (
                      <span className="block">
                        <Id value={event.entityId} />
                      </span>
                    )}
                  </td>
                  <td className={tdClass}>{event.correlationId === null ? '-' : <Id value={event.correlationId} />}</td>
                  <td className={tdClass}>
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-expanded={expanded}
                      onClick={() => {
                        setOpen(expanded ? undefined : event.seq)
                      }}
                    >
                      {expanded ? 'Hide' : 'Details'}
                      {' '}<span className="sr-only">of event {event.seq}</span>
                    </Button>
                  </td>
                </tr>
                {expanded && (
                  <tr className="border-b bg-muted/40">
                    <td colSpan={7} className="px-3 py-3">
                      <div className="grid gap-4 lg:grid-cols-2">
                        <div>
                          <p className="text-xs font-medium text-muted-foreground">Before</p>
                          <pre className="overflow-x-auto text-xs">{json(event.before)}</pre>
                        </div>
                        <div>
                          <p className="text-xs font-medium text-muted-foreground">After</p>
                          <pre className="overflow-x-auto text-xs">{json(event.after)}</pre>
                        </div>
                      </div>
                      <dl className="mt-3 grid grid-cols-[max-content_1fr] gap-x-4 font-mono text-xs break-all">
                        <dt className="text-muted-foreground">hash</dt>
                        <dd>{event.hash}</dd>
                        <dt className="text-muted-foreground">prev</dt>
                        <dd>{event.prevHash ?? '-'}</dd>
                        <dt className="text-muted-foreground">ip</dt>
                        <dd>{event.ip ?? '-'}</dd>
                      </dl>
                    </td>
                  </tr>
                )}
              </Fragment>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
