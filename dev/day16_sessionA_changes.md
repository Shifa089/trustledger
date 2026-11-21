# Changes

## DAY16 Session A (M8, FR-N-1): the notification module

**Goal:** finish M8 with the `notification` module (SPEC FR-N-1, §8 M8): `@ApplicationModuleListener` handlers for
`TransferCompleted` (sender and receiver), `TopupCaptured`, `KycLevelUpgraded` and `WalletFrozen`; plain-text
templates with masked details; `JavaMailSender` to Mailpit; a `notification.notifications` table; failures recorded,
never rolling back the business transaction.

Branch: `feature/day16-notifications` (created from `master`), merged into `master`.

Decisions approved in the plan:
- **New dependency `spring-boot-starter-mail`.** `JavaMailSender` comes from it. ADR 0017 is written and the
  CLAUDE.md stack updated.
- **One new way to get the e-mail address:** `IdentityApi.findNotificationContact(userId)`. It is the only path by
  which a plaintext address leaves the identity module, and it exists only to address mail. The table stores the
  user ID, never the address.
- **Failures are recorded, not thrown.** A `MailException` becomes a `FAILED` row with the exception class name
  only. Rethrowing would only make the outbox resend the event, on every restart, to a server that may still be
  down.
- **De-duplication by a unique `dedup_key`**, so an event republished after a restart sends no second mail.

---

### 1. Identity: who to write to

- `identity/NotificationContact` (new, public API record): `userId` and `email`. Its `toString()` masks the address
  (`n***@example.test`), so an accidental log line or assertion message can't leak it.
- `IdentityApi` gets two methods:
  - `findUser(userId)` returns the existing `UserView` (masked name and phone). It is used to name the other person
    in a transfer mail.
  - `findNotificationContact(userId)` returns the contact, or empty when the user is unknown, `ERASED` or has no
    e-mail.

### 2. The `notification` module

Layers as everywhere else (`ArchitectureRulesTest`, `ModularityTests` green). It depends on the public APIs and events
of `payments`, `wallet`, `kyc` and `identity`; nothing depends on it.

- **Migration `V17__notification_create_notifications.sql`.** `notification.notifications` has:
  - `id`, `user_id`, `type`, `dedup_key`, `status`, `failure_reason`, `created_at`, `updated_at`, `sent_at`;
  - a unique `dedup_key`;
  - CHECKs on type and status, plus "SENT has `sent_at`" and "FAILED/SKIPPED have a reason";
  - no foreign key to `identity.users` (other module's schema);
  - an index per user and a partial index on FAILED for the later retry job.

  It holds no personal data: no address, no body.
- **Domain:**
  - `Notification` entity: `pending(...)`, then exactly one of `markSent` / `markFailed` / `markSkipped`. No
    setters. The reason is cut to the column size.
  - `NotificationType` builds the de-duplication key:
    - `TRANSFER_SENT:<transferId>` and `TRANSFER_RECEIVED:<transferId>`;
    - `TOPUP_CAPTURED:<topupId>`;
    - `KYC_UPGRADED:<userId>:<level>`, because the event has no ID of its own and a user reaches each level once;
    - `WALLET_FROZEN:<eventId>`, because a wallet can be frozen again after an unfreeze.
  - `NotificationTemplates`: pure plain-text templates. They show:
    - the amount as `INR 1500.50`;
    - the counterparty's masked name (`R*** P***`, or "another TrustLedger user");
    - `ending 3f2a` instead of a full ID;
    - the time in Asia/Kolkata.

    They never include an address, a phone number, a full name or a full ID. **The frozen-wallet mail never says
    why or who:** telling a customer that AML froze the wallet would warn a suspect (tipping off).
  - `EmailSender` port and `NotificationRepository` port.
- **Application:**
  - `NotificationListeners`: four `@ApplicationModuleListener` methods. They resolve wallet → user through
    `WalletApi.findById`, render the template and hand a `NotificationRequest` to the service. A transfer gives two
    requests: the sender's and the receiver's.
  - `NotificationService.send(...)`:
    1. If the key already exists, the event was delivered again: return `DUPLICATE` and send nothing.
    2. If there is no contact, record SKIPPED (`NO_CONTACT`).
    3. Otherwise insert PENDING and **flush before sending**, which claims the key. Then send and record SENT, or
       FAILED on a `MailException`.

    Other exceptions propagate, so the publication stays incomplete and is retried on restart. Logs show type, ID,
    user ID and exception class, never the address or an exception message (SMTP errors quote addresses).
  - `NotificationProperties` (`trustledger.notification.from`, `zone`): startup fails on a malformed sender or a
    missing zone.
- **Infrastructure:**
  - `SmtpEmailSender`: a `SimpleMailMessage` (plain text) through Boot's `JavaMailSender`.
  - `JpaNotificationRepository`.

### 3. Configuration

- `application.yml`:
  - `trustledger.notification.from`: `TRUSTLEDGER_MAIL_FROM`, default `no-reply@trustledger.local`;
  - `trustledger.notification.zone: Asia/Kolkata`;
  - `spring.mail.host/port`: `TRUSTLEDGER_SMTP_HOST/PORT`, default `localhost:1025`, which is the compose Mailpit,
    so the local profile needs nothing extra;
  - UTF-8;
  - connect, read and write timeouts of 5 s, so a hanging server fails fast and is recorded FAILED instead of
    holding the listener thread and its DB connection.
- Test config: the same timeouts at 2 s.

### 4. Tests

**Unit (+28):**
- `NotificationTemplatesTest` (7):
  - every template's subject and body;
  - masked counterparty, with a fallback when unknown;
  - `ending xxxx` instead of the full ID;
  - local time in IST;
  - the frozen mail never mentions AML, compliance or a reason;
  - every mail is plain text with the footer.
- `NotificationTest` (7): the state machine (finishes once), reason truncation, input checks, dedup keys.
- `NotificationServiceTest` (6):
  - sent, with PENDING flushed *before* the send;
  - a redelivered event sends nothing;
  - no contact → SKIPPED;
  - a `MailSendException` whose message contains the address is recorded as `MailSendException` only and not
    thrown;
  - a bug is not swallowed;
  - `NotificationContact.toString()` hides the address.
- `NotificationListenersTest` (5):
  - a transfer → two requests, each naming the other party masked;
  - an unknown wallet → nothing;
  - top-up, KYC and freeze keys.
- `NotificationPropertiesTest` (3).

**Integration (+7), `NotificationIT` against a Mailpit Testcontainer** (`axllent/mailpit:v1.33.1`, same as compose,
added to `TestcontainersConfiguration` with `MailpitClient`, a small helper that reads mail through Mailpit's HTTP API
with the JDK `HttpClient`):
- a real transfer → one mail to the sender ("You sent INR 250.50 … to R*** P***") and one to the receiver. Neither
  contains the other person's name, the address or the full transfer ID. Both rows are SENT with the right keys;
- a captured top-up (real `TopupSettlementService.capture`) → one mail and a `TOPUP_CAPTURED` row;
- a KYC upgrade → one "Your KYC is approved" mail;
- a freeze by an officer → one mail without the officer's reason, "COMPLIANCE" or "AML";
- **the same `WalletFrozen` delivered twice → one mail and one row**;
- **Mailpit paused** (`docker pause`, so the SMTP connection hangs until the timeout):
  - the freeze is still committed (wallet FROZEN);
  - the row is FAILED with an exception class name and no `@`;
  - the notification's outbox publication is completed, so it is not resent forever;
  - Mailpit received nothing;
- a user without an e-mail → SKIPPED `NO_CONTACT`.

**Changed:** `KafkaExternalizationIT` and `KafkaRepublishOnRestartIT` select the Kafka externalizer's outbox row by
excluding the test recorder. They now also exclude the notification listener, which has its own row for each
`TransferCompleted`, `TopupCaptured` and `WalletFrozen`.

### 5. Docs

- `docs/adr/0017-email-via-javamailsender.md` (new). It covers:
  - the options: Spring Mail, a provider SDK, raw Jakarta Mail;
  - the decision;
  - failure handling: recorded, not thrown, and why;
  - consequences: a rare duplicate after a crash, no retry of FAILED rows yet, SES SMTP in AWS without code changes.
- `CLAUDE.md` stack: Spring Mail (ADR 0017).
- `docs/SPEC.md` FR-N-1: the four events, masked details, no tipping off, the table and ADR 0017.

### 6. Verification

`./mvnw verify` (all modules): **BUILD SUCCESS** (4 min 17 s).
- event-contracts: 31 unit tests.
- core-app: 1082 unit tests (Surefire, +28) and 613 integration tests (Failsafe, +7). Every IT now also has a
  Mailpit container.
- risk-aml-service: 1.

0 failures, 0 errors. `ModularityTests` and `ArchitectureRulesTest` are green with the new module.

### Still open

- FAILED notifications are not retried yet (a job with backoff and a maximum attempt count, plus alerting in M12).
- `TopupCaptured` was not in FR-N-1's original list; it was added on this session's brief and is now in the SPEC.

### What to learn from this

1. **"After commit" is the whole design.** `@ApplicationModuleListener` = `@TransactionalEventListener(AFTER_COMMIT)`
   + `@Async` + a new transaction + an outbox row. The transfer is final before the first SMTP byte, so no mail
   problem can undo it. The outbox row means a crash still delivers the event later.
2. **Decide what a failure means for the outbox.** Throwing from a listener says "try this event again". That is
   right for a bug, but wrong for "the mail server is down": the event is fine, the side channel isn't. Recording the
   failure in our own table keeps the outbox clean and leaves retry policy to us.
3. **Idempotency needs a key you can reproduce.** Not every event carries an ID (`KycLevelUpgraded` doesn't), so
   the key comes from the business meaning: user + level. A freeze can happen twice, so there the event ID is the
   key. Flushing the PENDING row *before* sending turns a concurrent duplicate into a constraint error instead of a
   second e-mail.
4. **Masking isn't only about logs.** An e-mail leaves our control forever. So it gets the minimum: masked names,
   the last four characters of an ID, and nothing that explains a freeze (tipping off is a legal risk, not just a UX
   choice). Even exception messages are treated as personal data, because SMTP errors quote addresses.
5. **Test the side channel with the real thing.** Pausing the Mailpit container gives a real hanging SMTP server,
   which proves the timeouts work and that the business change survives. A mocked `JavaMailSender` that throws
   would prove neither.
