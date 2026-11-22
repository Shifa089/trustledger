# ADR 0009: Field-level encryption with blind indexes, and erasure

- Status: Accepted
- Date: 2026-11-22 (records the design in use since M1; erasure added in M10 Day 21 B)
- Spec: docs/SPEC.md FR-PR-1, FR-PR-2, §4 `identity.users`, `kyc.kyc_cases`, §6 "Security", §8 M10

## Context

TrustLedger stores personal data that is dangerous to leak: e-mail, mobile number, full name, PAN, date of birth
and address. FR-PR-1 says this data is encrypted at rest and masked everywhere it is shown or logged. Disk
encryption (RDS storage encryption) is not enough on its own. It protects a stolen disk, but not a database dump, a
backup copied to the wrong place, a read replica given to analysts, or a SQL injection. Each of those reads the data
in clear.

Two needs pull against each other:

1. **Confidentiality.** Someone who can read the tables must not learn the values.
2. **Lookup.** Some values must still be found by equality: a P2P transfer finds the recipient by mobile number
   (FR-P-1), one mobile number may belong to one account only, and compliance can find the cases that share a PAN.

FR-PR-2 adds a third need. On request, a customer's personal data must be erased, while the ledger, the transfers
and the audit trail stay intact for their retention period.

## Decision

### 1. AES-256-GCM per column, in the application

`EncryptedStringConverter` (and `EncryptedLocalDateConverter` for the date of birth) is a JPA `AttributeConverter`
on each personal-data field (`*_enc` columns). `AesGcmCipher` stores `base64(iv[12] || ciphertext || tag[16])`:

- **A random 12-byte IV for every value.** Two customers with the same name get different ciphertexts, so equal
  values can't be spotted by comparing columns. Never reusing an IV under one key is a hard rule of GCM.
- **GCM authenticates.** A ciphertext that was changed, truncated or encrypted with another key fails to decrypt
  (an exception, never wrong plaintext).
- **Encrypted in the application, not with `pgcrypto`.** The key never travels to the database in a query, so it
  can't appear in `pg_stat_statements`, slow-query logs or a DBA's session.
- **Explicit per field** (`@Convert` on each one). A new personal field is a reviewed decision, not an accident of
  type.

Entities return plaintext from their getters. Masking (`Masking.name`, `Masking.phone`, ...) happens at the edges:
API views, logs and audit `before`/`after`. Entities never leave their module (CLAUDE.md), which keeps the number of
places that see plaintext small.

### 2. HMAC-SHA256 blind indexes for the fields we look up

`BlindIndex` stores `hex(HMAC-SHA256(indexKey, field + ":" + normalised value))` next to the ciphertext
(`email_hash`, `phone_hash`, `pan_hash`):

- **Normalised first.** `+91 98765 43210`, `09876543210` and `9876543210` give the same index; e-mail is
  lower-cased and trimmed.
- **Prefixed with the field type**, so a phone and a PAN that happened to share digits never produce the same
  index.
- **Keyed, not a plain hash.** There are only about 10^10 Indian mobile numbers; an unkeyed SHA-256 of each could be
  computed in minutes and reversed with a table. Without the index key, the HMAC can't be precomputed.
- **A separate key from the encryption key.** Leaking one doesn't leak the other's protection.
- **Unique constraints go on the index** (`uk_users_phone_hash`), so "one account per number" holds in the
  database even though the number itself is encrypted.

What a blind index still leaks: equality. Anyone reading the table sees that two rows share a number. That is the
price of looking it up, and the reason only the fields we search have one (name, date of birth and address don't).

### 3. Keys

Both keys are 32 random bytes, base64, from the environment (`TRUSTLEDGER_CRYPTO_KEY`,
`TRUSTLEDGER_CRYPTO_INDEX_KEY`; `.env` locally, never in Git). Startup fails if either is missing, has the wrong
length, or both are equal. On AWS (M14) the encryption key becomes a KMS data key (envelope encryption): KMS keeps the
master key, and the service holds only the decrypted data key in memory.

### 4. Erasure (FR-PR-2)

`POST /me/erasure-request` runs in one transaction (`ErasureService`):

- **Identity (`identity.users`).** E-mail, phone and full name are set to `NULL`, **together with their blind
  indexes**, and the status becomes `ERASED`. Clearing the index matters. As long as `phone_hash` exists, anyone with
  the index key can still answer "was 98765 43210 a TrustLedger customer?" by computing one HMAC. A database check
  (`ck_users_erased_has_no_personal_data`, V23) refuses an ERASED row that still holds any of these columns.
- **Nulls, not placeholder text.** A placeholder ("Erased User", `erased-<id>@invalid`) would need encrypting, could
  collide with a unique index, and looks like real data in an export. `NULL` says plainly that nothing is there.
- **The row and its ID stay.** Journal lines, transfers and audit events refer to the user and wallet by ID. They
  are financial and regulatory records with their own retention periods, and an ID on its own says nothing about the
  person. The Keycloak subject stays too: it is a random identifier and keeps the account from being provisioned
  again as a new user.
- **Consents keep their evidence, not the IP address.** Who agreed to which policy version, and when, is proof we
  must keep. The IP address is personal data that proof doesn't need. V23 changes the append-only trigger so that
  exactly one change is allowed: `ip` from a value to `NULL`, with every other column unchanged. Any other UPDATE,
  any DELETE and TRUNCATE are still refused.
- **The wallet closes, but only when it is empty.** No balance, no hold, and no transfer (INITIATED or
  HELD_FOR_REVIEW) or top-up (CREATED or PENDING) that could still move money. Otherwise the request is refused with
  409 `WALLET_NOT_EMPTY` and a message that says what to do. The wallet is locked with `SELECT ... FOR UPDATE` before
  these checks, the same lock money movement takes, so no transfer can slip in between the check and the close. A
  FROZEN wallet is under a compliance hold and is refused with `WALLET_FROZEN`.
- **Not while an AML case is open** (409 `AML_CASE_OPEN`). An investigation, and a possible STR, need to know who
  the person is. core-app reads its own copy of the AML service's open cases (`privacy.aml_cases`, fed by
  `AmlCaseStatusChangedV1`; see the ADR 0007 amendment of 2026-11-22). So the check is a local query under the wallet
  lock, not a remote call.
- **KYC records are kept.** `kyc.kyc_cases` (name as on PAN, PAN, date of birth, address, all encrypted) and the
  documents in S3 are not erased, and neither is `pan_hash`. The Prevention of Money Laundering Act requires
  reporting entities to keep customer identification records for five years after the business relationship ends.
  They stay encrypted, and only compliance officers can read them. Deleting them when that period ends belongs to a retention job (M14), not to the
  customer's request.
- **Untouched:** ledger journal, transfers, top-ups and audit events. The ledger, the transfers and the audit trail
  hold no personal data: IDs and amounts only. Audit `before`/`after` values were masked when they were written
  (FR-PR-1).
- **Evidence of the erasure itself.** A COMPLETED `ERASURE` row in `privacy.data_requests`, and a
  `PERSONAL_DATA_ERASED` audit event written in the same transaction, with IDs and states only. A partial unique index
  allows one completed erasure per user, so two racing requests erase once.

## Consequences

- A database dump, a backup or a replica reveals no personal data without the keys. A SQL injection reads
  ciphertext.
- Encrypted columns can't be searched, sorted or partially matched (`LIKE`). Every new lookup needs a deliberate
  blind index, and the leak that comes with it.
- Key rotation needs a re-encryption job, and for the index key a recomputation of every index. Neither is built
  yet. With KMS envelope encryption, rotating the master key needs no re-encryption, because only the data key is
  re-wrapped.
- Erasure is irreversible and synchronous. When the endpoint returns 200, the data is gone from the live database.
  Backups still hold it until they expire. That has to be stated in the privacy policy, and backup retention has to
  stay short (M14).
- Not yet done:
  - The customer's Keycloak account still exists after erasure. Disabling it there needs the Keycloak admin API (a
    follow-up), until then an erased customer can still sign in, but sees an empty profile and a closed wallet.
  - Cases opened before this release reach core-app only once the AML service sends an event for them. Locally and
    in tests that history is empty; a real deployment would need a one-off backfill.
