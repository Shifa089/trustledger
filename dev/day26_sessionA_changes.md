# Changes

## DAY26 Session A: final regression (audit only, nothing fixed)

**Goal:**
1. Run `./mvnw verify` and report any failing or skipped tests.
2. Go through SPEC §9 (the 16 must-pass tests) and SPEC §12 (definition of done), checking each item against the code.
3. Produce one table: item, status, evidence.
4. Mark the 4 cut features (SPEC §12 cut order) as **deferred**.

**Fix nothing yet.** This session only audits. It changes no code, test or config. The only new file is this one.

Branch: `feature/day26a-regression` (from `master`, which has Day 25 Session B).

Method: I didn't take the README's must-pass table on trust. For every item I opened the named test class and checked
that a test method exists for each part of the claim (for example: the same key with a different body gives 422; a
rule fires at its threshold and not one step below). Gaps and nuances are written down below.

---

### 1. `./mvnw verify`

**BUILD SUCCESS** on the whole reactor, in 7 min 40 s (exit code 0):

| Module | Unit tests | Integration tests | Failures / errors | Skipped | Lines (JaCoCo) | Branches |
| --- | --- | --- | --- | --- | --- | --- |
| event-contracts | 36 | | 0 | 0 | 100% | 100% |
| core-app | 1,344 | 816 | 0 | 0 | 96.6% | 89.1% |
| risk-aml-service | 112 | 62 | 0 | 0 | 94.1% | 85.1% |
| **Total** | **1,492** | **878** | **0** | **0** | | |

**2,370 tests, 0 failing, 0 skipped.** The counts and coverage match Day 25 B exactly, so nothing has drifted.

Checked beyond Surefire's "Skipped" column:
- **No `@Disabled`, `@EnabledIf` or `@DisabledIf` anywhere** in the test sources.
- **One assumption-gated test:** `TransferServiceIT.monthlyLimitIsRefused` calls `assumeThat(dayOfMonth > 1)`.
  - On the 1st of any month it is **silently skipped**, which leaves the monthly-limit rule untested that day.
  - It ran today (the 23rd).
  - Fix idea for later: put a fixed `Clock` into that test instead of the assumption.
- **Two `ERROR … AUDIT WRITE FAILED` log lines** in the output are expected. They come from
  `AuditedAspectTest`, which tests that the aspect logs a missing audit event.
- **CI:** the last `master` run (45184729010, the Day 25 B merge) passed all four jobs: `./mvnw verify`, Trivy,
  gitleaks and the image build.

### 2. SPEC §9: the 16 must-pass tests

| # | Item | Status | Evidence (test class → method) |
| --- | --- | --- | --- |
| 1 | Unbalanced journal entry rejected by the service and by the DB | **Pass** | `LedgerPostingServiceIT.unbalancedEntryIsRejected`; `LedgerMigrationIT.unbalancedEntryIsRefusedAtCommit` (deferred trigger), `entryMayBeUnbalancedMidTransactionIfBalancedAtCommit` |
| 2 | UPDATE and DELETE on ledger and audit tables fail | **Pass** | `LedgerMigrationIT.journalEntriesRefuseUpdateAndDelete`, `journalLinesRefuseUpdateAndDelete`; `AuditMigrationIT.updateIsRefused`, `deleteIsRefused`, `truncateIsRefused` |
| 3 | Duplicate `Idempotency-Key` → original response; same key, other body → 422 | **Pass** | `IdempotencyInterceptorIT.sameKeyTwiceRunsOnceAndReturnsTheSameResponse`, `sameKeyWithAnotherBodyIs422`; `TransferControllerIT.sameKeyWithAnotherBodyIs422`; `IdempotencyServiceIT` |
| 4 | 100 concurrent transfers never overdraw; reconciliation reports zero mismatches | **Pass (note)** | `TransferConcurrencyIT.hundredConcurrentTransfersWithBalanceForFiftyExactlyFiftySucceed`: 50 succeed, 50 `INSUFFICIENT_FUNDS`, never negative, journal balances = cached balances. *Note:* it compares journal and cache directly and does not run `ReconciliationService`; that service's zero-mismatch and mismatch paths are tested in `ReconciliationIT` |
| 5 | Opposite-direction concurrent transfers never deadlock | **Pass** | `DeadlockFreedomIT.oppositeTransfersTwoHundredTimesNeverDeadlockAndPreserveTotals`, plus the control test `controlLockingInOppositeOrdersIsDetectedAsADeadlock` |
| 6 | Every transfer rejection rule returns the right error code | **Pass** | `TransferServiceIT`: frozen sender and receiver (`WALLET_FROZEN`), MIN tier and no wallet (`KYC_REQUIRED`), `INSUFFICIENT_FUNDS`, daily (one paisa over), monthly and receiver cap (`LIMIT_EXCEEDED`), self-transfer and zero amount (`VALIDATION_FAILED`), unknown recipient (`NOT_FOUND`). See the note on `monthlyLimitIsRefused` in §1 |
| 7 | High-value transfer held, then approved or rejected correctly | **Pass** | `TransferServiceIT.atTheHighValueThresholdTheAmountIsHeldAndNothingIsPosted`, `oneRupeeBelowTheThresholdCompletes`; `HeldTransferServiceIT` (approve, reject, `aDecisionIsFinal`, `concurrentDecisionsOnOneTransferProduceExactlyOne`, the freeze and cap checks again at approval) |
| 8 | Duplicate and invalid-signature webhooks handled safely | **Pass** | `RazorpayWebhookControllerIT.duplicateDeliveryIsAnswered200AndNotProcessedAgain`, `invalidSignatureIsRejectedStoredAsInvalidAndNotProcessed`, `missingSignatureIsRejectedAndStored`; `TopupWebhookSettlementIT.theSameEventDeliveredTwiceIsCreditedOnce`, `theSamePaymentUnderAnotherEventIdIsCreditedOnce` |
| 9 | Gateway timeout opens the circuit breaker and returns 503 | **Pass (note)** | `TopupControllerIT.gatewayTimeoutIs503AndTheTopupFails` (3 s time limit → 503 `GATEWAY_UNAVAILABLE`), `openCircuitFailsFastWithoutCallingTheGateway` (circuit `OPEN`, no further gateway call). *Note:* the test opens the circuit with 503 responses, not timeouts. `RazorpayGatewayAdapter.timeLimited` turns a timeout into the same `GatewayUnavailableException` the breaker records, but no single test opens the circuit by timeouts alone |
| 10 | KYC maker and checker must be different officers | **Pass** | `ComplianceKycIT.sameOfficerVerifyingAndApprovingIs409MakerCheckerViolation`, `anOfficerCannotReviewTheirOwnCase`; `KycReviewServiceTest.sameOfficerApprovingIsMakerCheckerViolationAndNothingChanges` |
| 11 | Security matrix 401/403/200 per endpoint group; no BOLA | **Pass** | `SecurityMatrixIT` (parameterised path × role matrix, 401/403 ProblemDetail); `AmlSecurityIT` (9 tests). BOLA: wallets are `/wallets/me` only (no wallet ID in the path); `TransferControllerIT.anotherCustomerCannotReadTheTransfer`, `TopupControllerIT.ownerCanReadTheTopupAndNobodyElseCan`, `TransactionHistoryIT.anotherCustomersRowsAreNeverReturned` |
| 12 | Each AML rule fires at its threshold, not just below; HIGH freezes the wallet | **Pass** | R1 `VelocityRuleTest` (5 no, 6th yes); R2 `StructuringRuleTest` (2 no, 3rd yes, both range ends); R3 `LargeAmountRuleTest.justBelowOneLakhDoesNotFire` / `oneLakhFires`; R4 `FanOutRuleTest` (9 no, 10 yes); window edges in `RuleEngineIT`. Freeze: `RuleEngineTest.aHighAlertWritesAFreezeRequestForItsWalletAndCase`, `FreezeRequestPublishingIT`, `AccountFreezeRequestedConsumerIT.aFreezeRequestFreezesTheWalletWithSourceAmlAndTellsAml` |
| 13 | Duplicate Kafka event processed once; poison message → DLT | **Pass** | `TransactionFactConsumerIT.theSameEventDeliveredTwiceIsStoredOnce`, `anUnreadableRecordGoesStraightToTheDeadLetterTopic`, `aFailureThatNeverClearsIsTriedThreeTimesThenDeadLettered`; core side: `AccountFreezeRequestedConsumerIT.aRedeliveredRequestIsProcessedOnce`, `aRecordThatCantBeReadGoesToTheDeadLetterTopic` |
| 14 | Tampering with an audit row detected by `/audit/verify` | **Pass** | `AuditApiIT.editingARowInSqlIsReportedAtThatExactSeq`, `deletingARowInSqlIsReportedAtTheRowAfterIt`, `anIntactChainVerifiesUpToTheLatestEvent` |
| 15 | Erasure anonymises personal data and keeps financial records | **Pass** | `ErasureIT.erasureAnonymisesThePersonAndLeavesLedgerTransfersAndAuditUntouched`, plus the refusal cases (money in the wallet, frozen wallet, an open AML case, a top-up still pending, a second erasure, a race) |
| 16 | Module boundary tests pass | **Pass** | `ModularityTests.verifiesModularStructure`; `ArchitectureRulesTest` (13 rules) |

**16 of 16 pass.** Items 4 and 9 pass with a small gap between the wording of the test and the wording of the SPEC
(see the notes).

### 3. SPEC §12: definition of done

| Item | Status | Evidence |
| --- | --- | --- |
| `./mvnw verify` passes locally and in CI, including the 16 must-pass tests | **Pass** | §1 above (today's local run); CI run 45184729010 on `master`, all jobs green |
| `docker compose up -d` plus two run commands start everything on a clean machine | **Pass (not re-run today)** | `infra/docker-compose.yml`, README §7, `scripts/smoke.sh`. Day 25 B ran the `apps` profile and the smoke test passed. No fresh-clone run in this session |
| Every endpoint in the API tables is implemented, secured and in `http/` | **Partial** | Every built endpoint is secured (the chain ends in `denyAll()`, `SecurityMatrixIT`) and has a request in `http/` (`HttpCollectionTest`). **Not implemented:** `GET /wallets/me/statements/{yyyy-MM}` and `POST /aml/cases/{id}/ai-summary` (both **deferred**, see §4); `GET /aml/alerts` and `GET /admin/ledger/accounts/{code}/balance` (**open gaps, not on the cut list**) |
| Swagger UI documents both services | **Fail** | core-app only (`springdoc-openapi-starter-webmvc-ui` in `core-app/pom.xml`, `SwaggerPublicLocalTest`, `ApiVersioningIT`). `risk-aml-service` has **no springdoc dependency and no `/v3/api-docs`** |
| Grafana shows a cross-service trace and the custom metrics | **Pass (note)** | `infra/grafana/trustledger-dashboard.json` (`trustledger_transfers_total`, `trustledger_transfer_amount`, `trustledger_topups_total`, …); `KafkaTracingIT` (both services); trace seen in Tempo on Day 23 A (`day23_sessionA_changes.md`). *Note:* by design, a freeze request starts a new trace instead of continuing the transfer's trace |
| `docs/perf.md` has real measured numbers | **Pass** | `docs/perf.md`: k6, 200 seeded wallets, p99 82 ms at 10/s and 397 ms at 50/s. The SPEC target (p99 < 300 ms at 100/s) is **not met**, and the file says so |
| `docs/security-checklist.md` covers all OWASP API Top 10 items | **Pass** | API1:2023 … API10:2023 are all present |
| All 9 ADRs written | **Pass** | `docs/adr/0001`–`0009` plus 0010–0022 (22 in total), each 0001–0009 with a Day 25 B code check |
| README complete with diagram and demo video link | **Partial** | `README.md` has the Mermaid diagram and all 10 §11 parts. The **demo video link is a placeholder** (the video and `docs/demo-script.md` are not made) |
| No secrets in Git history | **Pass** | The gitleaks job in CI scans the full history (`.github/workflows/ci.yml`, `.gitleaks.toml`, ADR 0021) and was green on the last `master` run. I didn't run the manual `git log -p \| grep -i secret` this session |
| You can explain every module, and you wrote the ledger posting and transfer core yourself | **For you to confirm** | Not something the code can show |
| `LEARNING.md` complete, used as your revision sheet | **Fail** | `LEARNING.md` is **0 bytes** (created on Nov 10, never filled). The lessons exist, scattered across the "What to learn" sections of `day*_changes.md` |

### 4. The 4 cut features (SPEC §12 cut order): deferred

| Cut | Feature | Status | Evidence |
| --- | --- | --- | --- |
| 1 | M15: AI-assisted AML case summary (`POST /aml/cases/{id}/ai-summary`) | **Deferred** | Refused by deny-all (403); README "Not yet built"; `http/aml-cases.http` labels it NOT BUILT YET |
| 2 | M14, AWS half: ECR push, ECS/RDS/MSK, KMS envelope key, live URL | **Deferred** | CI builds the images but pushes nowhere (ADR 0022); README "Not yet built"; ADR 0009 code check (KMS key, KYC retention job) |
| 3 | M11: PDF statements (`GET /wallets/me/statements/{yyyy-MM}`) | **Deferred** | 404 today; `http/history.http` labels it NOT BUILT YET |
| 4 | M10: Hibernate Envers entity history | **Deferred** | Not a dependency; the hash-chained audit trail (ADR 0008) covers who changed what |

### 5. Open items found (not fixed; ranked by how much they matter for "done")

1. **`LEARNING.md` is empty.** It's an explicit DoD item, and it's your revision sheet. You could fill it from the
   day files' "What to learn" sections.
2. **No Swagger in `risk-aml-service`.** An explicit DoD item. It would need springdoc added (already an ADR'd
   dependency, ADR 0010) plus a security rule for `/v3/api-docs` and `/swagger-ui` in the AML chain, with
   `AmlSecurityIT` rows.
3. **Two endpoints that are not on the cut list are not built:** `GET /aml/alerts` and
   `GET /admin/ledger/accounts/{code}/balance`. You need to decide: build them, or cut them explicitly in SPEC/README.
4. **The demo video link** in the README (the rest of M16).
5. **`monthlyLimitIsRefused` skips on the 1st of the month.** A fixed `Clock` in that test would make it run every day.
6. Minor gaps between test wording and SPEC wording (items 4 and 9). Optional:
   - a test that opens the circuit by timeouts only;
   - a call to `ReconciliationService` at the end of `TransferConcurrencyIT`.

Already recorded in the ADRs and the README (unchanged): `lock_timeout`, alerts on DLT traffic, parked top-ups and
stale outbox rows, the Keycloak account of an erased customer, the 100/s perf target.

### 6. Verification status

- `./mvnw verify`: **BUILD SUCCESS**, 2,370 tests, 0 failures, 0 errors, 0 skipped (7 min 40 s).
- Changed in this session: this file only. No code, test, config or migration was touched.

### What to learn from it

- **"Skipped: 0" is not the whole truth.** An `assumeThat` that depends on the date can hide a test on some days.
  Search for assumptions and `@Disabled` as well as reading the Surefire summary.
- **Check the claim, not the test's name.** "Opens the circuit on timeout" and "opens the circuit on 503s, and a
  timeout gives 503" are two different statements. Reading the test body is what shows the difference. Interviewers
  ask exactly this.
- **A definition of done is a contract with yourself.** All 16 must-pass tests are green, but two plain checklist items
  (`LEARNING.md`, Swagger on the second service) are not. Those cost very little to finish, and a reviewer would
  notice them first.
- **Cut deliberately, and write it down.** The four cut features are listed with how they behave today (403/404). Two
  other endpoints are missing with no decision recorded. The second case is the one to fix, by building them or by
  cutting them on paper.
