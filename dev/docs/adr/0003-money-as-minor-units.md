# ADR 0003: Money as integer minor units (paise)

- Status: Accepted
- Date: 2026-11-16
- Spec: docs/SPEC.md §4 (schema), §6 (Money convention), FR-L-1

## Context

TrustLedger stores and moves rupees. Every balance, limit, ledger line and API amount has to be exact:
reconciliation (FR-L-4) compares two independently maintained numbers for exact equality, and the ledger's
deferred trigger requires debits to equal credits to the paisa. A rounding error of one paisa is a reconciliation
mismatch, a failed posting, or money created from nothing.

Things that make this harder than it looks:

- **Binary floating point can't represent most decimal fractions.** `0.1 + 0.2 == 0.30000000000000004` in
  `double`. Summing thousands of `double` amounts drifts, and the drift depends on the order of the sum.
- **`BigDecimal` is exact but easy to misuse.** `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is
  `false` (scale is part of equality); `divide` without a `MathContext` throws on non-terminating results;
  every arithmetic call allocates. It also maps to `numeric` in PostgreSQL, which is slower to sum and index.
- **Overflow.** A `long` that silently wraps around turns a huge credit into a huge debit.
- **The API boundary.** JSON numbers are parsed as `double` by many clients (JavaScript always does), so an
  amount sent as a JSON number may already be wrong before it reaches us.

## Options

1. **`double` / `float`.** Fast, simple, wrong. Rejected outright.
2. **`BigDecimal` everywhere** (`numeric(19,2)` in the database). Exact, but scale and equality pitfalls in
   every comparison, and a wider surface for mistakes in a codebase where money code must be obviously correct.
3. **JSR-354 (`javax.money`, Moneta).** A full money API with currencies and rounding. A new dependency and a
   large API for a single-currency wallet.
4. **Integer minor units in a small value type.** `Money(long amountMinor, Currency currency)`; `bigint` in the
   database; decimal strings at the API.

## Decision

Option 4.

- **Domain:** the `shared.Money` record holds `long amountMinor` (paise for INR) and a `Currency`. It refuses
  negative amounts (the ledger line's `direction` carries the sign) and refuses arithmetic across currencies.
  `plus`/`minus` use `Math.addExact`/`subtractExact`, so overflow throws instead of wrapping.
- **Database:** every amount column is `bigint` ending in `_minor` (`balance_minor`, `held_minor`,
  `amount_minor`). Sums use `coalesce(sum(...), 0)` and stay integers.
- **API:** amounts are JSON **strings**, e.g. `"1500.50"`. `Money.parse` accepts at most two decimals and
  rejects more instead of rounding (`"10.005"` is a 400, not `"10.01"`); signs, exponents and grouping are
  rejected too. Responses always have exactly two decimals (`Money.toDecimalString()`).
- **Configuration:** limits in `application.yml` are quoted decimal strings bound to `Money` by
  `MoneyPropertyConverter`, with the same no-rounding rule.
- **`BigDecimal`** appears only at the edges, to parse and format strings, never in calculations.
- **`double` and `float`** are not used anywhere near money.

## Consequences

- Equality is plain `long` equality, which is what reconciliation and the balanced-entry check need.
- A `long` holds about 9.2 × 10^18 paise (9.2 × 10^16 rupees), far beyond any wallet, ledger or national total.
  The exact-arithmetic methods turn the impossible case into an exception, not a wrong number.
- Fast: integer sums in Java and `bigint` sums in PostgreSQL.
- Only currencies with 2 minor digits are supported: the `Money` constructor refuses any other. INR, USD and
  EUR fit.
  JPY (0 digits) or KWD (3 digits) would need the scale taken from `Currency.getDefaultFractionDigits()`.
  That's a contained change inside `Money` if ever needed.
- Interest, fees as percentages and FX conversion would need an explicit rounding rule at that point
  (e.g. `RoundingMode.HALF_EVEN` once, at the end). None are in scope today.
- Clients must send strings, not numbers: request records for money endpoints (M6 transfers, M7 top-ups)
  declare amounts as `String` and parse them with `Money.parse`, so a client's float rounding can't slip in.
