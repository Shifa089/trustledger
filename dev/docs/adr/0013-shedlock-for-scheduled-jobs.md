# ADR 0013: ShedLock for scheduled jobs

- Status: Accepted
- Date: 2026-11-16
- Spec: docs/SPEC.md §2 FR-L-4, §8 M5 ("nightly reconciliation job with ShedLock"), M7 (pending top-up reconciler)

## Context

FR-L-4 needs a nightly reconciliation job, and M7 adds a pending top-up reconciler. In AWS (M14) core-app runs
as more than one ECS task, and Spring's `@Scheduled` fires on **every** instance. Two concurrent reconciliation
runs double the mismatch counter and the alerts. Two concurrent top-up reconcilers could ask the gateway about
the same payment twice. A scheduled job must run **once per trigger across the cluster**.

An admin can also start reconciliation manually (`POST /admin/reconciliation/run`). That run must not overlap a
nightly run either.

The SPEC names ShedLock for this, but CLAUDE.md requires an ADR for every library not already in the stack.

## Options

1. **ShedLock** (`shedlock-spring` + `shedlock-provider-jdbc-template`). A lock is a row in a small table: a run
   sets `lock_until` in the future with one conditional `UPDATE`, and the other instances see it taken and skip.
   `lockAtMostFor` frees the lock if the instance crashes; `lockAtLeastFor` stops a slightly late trigger on
   another instance from running the job a second time. It works with the PostgreSQL we already have.
2. **A PostgreSQL advisory lock written by hand** (`pg_try_advisory_lock`). No new library, but the lock belongs
   to a database session, so it needs a dedicated connection held for the whole run. A connection-pool hiccup
   silently releases it. There is no "at least" duration and no record of who ran last. All of that would be
   ours to write and test.
3. **A Redis lock** (Redisson, or `SET NX PX` by hand). Redis is in the stack, but rate limiting already fails
   open when Redis is down, and a lock that fails open would run the job everywhere. Redisson is also a much
   larger dependency than ShedLock.
4. **Quartz with a JDBC job store.** Clustered scheduling, misfire handling, persistent triggers. About ten
   tables and a lot of configuration for a couple of nightly jobs.
5. **One instance only (a scheduler task, or a leader flag).** This moves the problem into deployment
   configuration, where it is easy to get wrong, and doesn't cover the manual admin run.

## Decision

Option 1: ShedLock 7.10.1 (BOM `net.javacrumbs.shedlock:shedlock-bom`), built against Spring Framework 7.0 and
Spring Boot 4.1. 7.11.0 was less than two weeks old on Day 8.

- The lock table is `shared.shedlock` (`V9__shared_create_shedlock.sql`), the `shared` module's first table.
- `JdbcTemplateLockProvider` uses **`usingDbTime()`**: `lock_until` is compared with PostgreSQL's clock, not
  each instance's, so clock skew between tasks can't let two instances both think the lock has expired.
- Jobs take the lock **explicitly** through `LockingTaskExecutor`, not the `@SchedulerLock` annotation. The
  nightly trigger and the manual admin endpoint then share one code path and one lock name
  (`wallet-reconciliation`):
  - the nightly run uses `lock-at-least-for` (1 minute) so a late trigger elsewhere doesn't repeat it;
  - the manual run uses zero, so an admin can run it again right after;
  - a manual run while the lock is held returns 409 `REQUEST_IN_PROGRESS`.
- `@EnableScheduling` sits behind `trustledger.scheduling.enabled` (default true). Tests set it to false so a
  timed run can't interfere with an IT, while the lock and manual runs still work.

## Consequences

- One small library (two jars, no transitive dependencies beyond Spring), in the stack line of CLAUDE.md.
- Every future scheduled job (the M7 top-up reconciler, M10 retention jobs) uses the same `LockingTaskExecutor`
  pattern with its own lock name.
- `lock-at-most-for` (30 minutes) must stay longer than the slowest real run. If a run outlives it, a second
  instance could start. Reconciliation logs its duration; M12 can alert when it gets close.
- A crashed run keeps the lock until `lock_until`, so the next manual run may get 409 for up to 30 minutes.
- ShedLock is not a scheduler: missed triggers (all instances down at 02:00) are not caught up. For
  reconciliation that is acceptable: the next night checks everything again, and the admin endpoint exists.
