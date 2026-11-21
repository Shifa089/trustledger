# ADR 0017: Notification e-mails through JavaMailSender over SMTP

- Status: Accepted
- Date: 2026-11-21
- Spec: docs/SPEC.md FR-N-1, §8 M8 ("notification module: email via Mailpit"); CLAUDE.md Stack (changed by this ADR)

## Context

FR-N-1 asks for e-mails when a transfer is sent or received, a top-up is captured, KYC is approved and a wallet is
frozen. Locally the compose file already runs Mailpit (an SMTP sink with a web UI and an HTTP API). We need a way to
send mail from core-app, and the dependency it brings is not in the CLAUDE.md stack.

Constraints:
- no personal data in logs; e-mail addresses are personal data;
- a mail problem must never roll back or slow down a money movement;
- tests must use a real server (Testcontainers), not mocks, like the rest of the integration tests;
- production on AWS (M13) should not need code changes.

## Options

1. **`spring-boot-starter-mail` (`JavaMailSender`, Jakarta Mail over SMTP).** Boot auto-configures it from
   `spring.mail.*`. It talks to Mailpit locally and in tests, and to Amazon SES's SMTP endpoint in AWS with
   different properties only. One small, standard dependency managed by the Boot BOM.
2. **A provider SDK (AWS SES v2 SDK, SendGrid, ...).** Better delivery feedback (bounces, message IDs), but
   locally it needs an emulator (LocalStack SES does not deliver mail anywhere we can read it as easily) and it
   ties the module to one provider.
3. **Raw `jakarta.mail` without Boot.** Same protocol as option 1, more code and no auto-configuration.

## Decision

Option 1. `spring-boot-starter-mail` is added to core-app and to the CLAUDE.md stack.

- The `notification` module owns an `EmailSender` port (domain); `SmtpEmailSender` (infrastructure) implements it
  with a plain-text `SimpleMailMessage`. No HTML, so nothing to escape and no remote content.
- `spring.mail.host/port` come from `TRUSTLEDGER_SMTP_HOST/PORT` (default `localhost:1025` = compose Mailpit).
  Connect, read and write timeouts are 5 seconds (2 in tests).
- The sender address is `trustledger.notification.from`.
- Tests: a Mailpit Testcontainer (`axllent/mailpit`, the compose image) in `TestcontainersConfiguration`; tests read
  received mail through Mailpit's HTTP API with the JDK `HttpClient`. No extra test dependency.

## How failures are handled

- Listeners are `@ApplicationModuleListener`s: they run after the business transaction commits, asynchronously and
  in their own transaction. A failing SMTP server can't undo or delay a transfer.
- A `MailException` is **recorded** (`notification.notifications.status = FAILED`, exception class name only) and
  not rethrown. Rethrowing would leave the outbox publication incomplete, and it would be resent on every restart
  to a server that may still be down. Other exceptions are bugs and do propagate.
- Every attempt has a unique `dedup_key`, so a republished event sends no second mail.

## Consequences

- At-least-once is narrowed, not removed: a crash after the SMTP server accepted the mail but before the listener's
  transaction committed rolls back the row, and the republished event sends the mail again. Rare, and a duplicate
  "you received ₹500" mail is harmless compared with a lost one.
- FAILED rows are not retried yet. A retry job (with backoff and a maximum) belongs to a later session, together
  with alerting on the FAILED count (M12).
- In AWS, SES SMTP credentials will be `spring.mail.username/password` from Secrets Manager; no code change.
