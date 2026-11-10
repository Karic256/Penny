# Penny 0.4 — SMS accounts and money patterns

Private Android finance tracker for Centenary Bank and MTN Mobile Money, processed on the phone. No email import, internet, contacts, location, or SMS-sending permission. Financial SMS and edits stay in local SQLite; Android backup is disabled.

## Dashboard

**Last reported balance** comes only from an explicit SMS balance, with report time, freshness, time source and balance type (available, ledger or unspecified reported balance). It is never debited again. Newer transaction timestamps take precedence over inbox arrival/import order.

**Combined last-reported total** sums one latest report per tracked account. It is partial when balances or account identifiers are missing. Mixed balance types are labelled. It is never a live balance.

**Cash flow** is separate: completed external income, principal spending, explicit fees, spending including known fees, and net cash flow. Unknown fees stay unknown. Pending, failed, approval and conflicting records are excluded. Reversed principal is removed; an explicit fee is retained unless its refund is stated. Missing activity never becomes an invented fee.

Filters: Today, Monday-based This Week, This Month, inclusive Custom Dates, and All Accounts / Bank / Mobile Money. East Africa time is used. Balances always cover all tracked accounts; charts and cash flow follow the filters. Trends, category/account spending and repeated descriptions use reconciled completed records. History is labelled incomplete; repeated descriptions are not subscription predictions.

## Parsing and reconciliation

`TransactionParser` has separate Centenary and MTN adapters. Extend the adapter list with tests for another provider; unsupported SMS are not guessed into the ledger.

Centenary signed `trxn of` amounts, masked A/C, dates/times, EFT payer text and Bal/available/ledger balances are supported. Positive bank amounts are incoming; salary requires user confirmation. MTN deduction/payment/receipt wording, explicit fees including zero, message descriptions, IDs and decimal balances are supported. Repeated currency markers, separators, negative sign spaces, and day-first/ISO/text-month dates are normalized. Telephone numbers, balances and IDs are never selected as transaction amounts.

States: completed, pending, failed, reversed, approval, unknown. Explicit original IDs link settlement/reversal events in the same provider/account, including a reversal of a settled authorization. Unlinked events remain visible for review. A pending card's available balance is shown without counting it as spending. A settlement with the same ID counts once. Explicit balance-only statements can supply a balance without inventing a completed payment; projected/estimated/requested balances are excluded. A balance-only second confirmation can refine a completed transaction's precision through its shared ID.

Deduplication uses provider + account + transaction ID. Missing account identifiers can be reconciled through a unique matching provider/ID with a known account. Without an ID, sender + SMS source timestamp + normalized body is idempotent. Receiver and inbox import share the service-center timestamp (`DATE_SENT`); arrival time is retained separately. Separate no-ID messages remain, with potential duplicates flagged, so repeated purchases are not silently removed. Conflicting amounts/directions/fees are excluded for review. Consistent integer/decimal duplicate balances retain the more precise value; conflicting reports are not used as reliable balances. Both available and ledger balances in one SMS are preserved.

Limitations: identical same-second no-ID SMS cannot be distinguished by the fields currently read. If the SMS provider omits `DATE_SENT`, inbox imports fall back to arrival time; a receiver/inbox no-ID pair may remain separate and flagged. Wallets without identifiers cannot be separated; the grouping and total are labelled incomplete. Reused IDs in one account may need review.

## Transfers and corrections

Tap a record to inspect every original SMS and parsed field, correct the category, confirm salary or identify an internal transfer. Manual pairing requires equal principal, opposite directions and different accounts, with user confirmation of ownership. An explicitly confirmed single leg remains flagged. Automatic pairing requires one unique opposite leg, different account, shared explicit reference, transfer wording and a 30-minute window. Generic `Funds Transfer (Mobile) / Ebanking` alone is insufficient. Principal is excluded from income/spending; explicit fees remain spending.

## Upgrade and privacy

The old schema discarded SMS bodies and represented unknown balances as zero. Database v2 preserves the old `transactions` archive but excludes it, including email receipts, from the SMS dashboard. Choose **⋮ → SMS permissions → Import financial SMS** to reconstruct accounts. Deleted source SMS cannot be reconstructed. Import/parsing run off the UI thread; inbox scans are atomic.

The compact menu also provides CSV export, flagged-record review and privacy/history information. CSV contains reconciled records and notes; full SMS bodies stay local. Choose **⋮ → Export for ChatGPT (CSV)** to save a separate report with bank and Mobile Money balance snapshots and categorized transactions. Upload that CSV to a chat to analyze it. The report uses temporary account labels and omits account numbers, transaction references, descriptions and SMS text.

Installing updates requires the same signing certificate; GitHub's default debug key is not durable release signing. Export records before reinstalling or changing signing keys.

## Build and validation

GitHub Actions runs JVM regressions, builds app/test APKs and runs migration, reimport, category-persistence and dashboard smoke tests on a disposable API 30 emulator. Synthetic screenshots cover balances, cash flow, recent records and SMS detail. Never run instrumented tests on a phone containing personal Penny data: tests delete the test installation's database.

Artifacts: **Penny-debug-apk**, **Penny-unit-tests**, **Penny-Android-QA**. Actions → Build Penny APK → Run workflow, or PR/main builds. Local prerequisites: Java 17, SDK 35, Gradle 8.9.

```
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Regression cases include bank signed amounts/EFT payers, the duplicate 6,500 purchase and 4,663 / 4,663.36 precision, repeat imports, repeated purchases, approvals, lifecycle states, settlement/reversal links, internal/uncertain transfers, fees, decimals, date formats, missing/conflicting fields, filters and out-of-order balances. Fixtures use synthetic account suffixes and IDs; no personal messages or credentials are included. Textual examples were available; additional provider variants need anonymized examples and tests.
