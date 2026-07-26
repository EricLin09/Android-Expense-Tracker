# Expense Tracker

A personal expense tracker for Android. Native Java, no backend, no analytics, no
third-party SDKs beyond AndroidX and Material Components.

Built for my own use in Australia, so it handles **CNY and AUD side by side** and
reads payment notifications from the apps I actually pay with. The UI is in Chinese.

---

## Why this one is interesting

Most expense trackers make you type everything in. This one tries to remove that
friction in two places, and the second one is the part worth reading:

**1. It reads payment notifications.** A `NotificationListenerService` watches
Alipay / WeChat Pay / UnionPay notifications and turns them into draft records.
`PaymentParser` pulls out the amount and currency with a regex that deliberately
*rejects* numbers preceded by words like 余额 (balance) or 积分 (points) — the
common way naive parsers record your account balance as a purchase.

**2. Categorisation is two-tier, and the model is the fallback, not the default.**

```mermaid
flowchart LR
    A[Payment notification] --> B[PaymentParser<br/>amount · currency · direction]
    B --> C{Merchant table<br/>271 rules, on-device}
    C -->|hit| D[Categorised instantly<br/>no network]
    C -->|miss| E["Uncategorised"]
    E -.->|user taps<br/>Batch classify| F[Local LLM on LAN<br/>llama.cpp + Qwen3-4B]
    F --> G[Category]
    F -.->|unreachable| E
```

The merchant table runs **in the notification callback**: pure string matching,
microseconds, no network. Only records it misses get offered to a language model,
and only when the user explicitly asks — so the common path never touches the
network at all, and a dead server degrades to "uncategorised" rather than to
wrong data.

The model runs on a **spare Android phone on the same LAN** (llama.cpp serving
Qwen3-4B-Instruct, Q4). Financial data never leaves the house and there is no
third-party API in the loop.

---

## Matching rules that survive real bank descriptions

The merchant table looked trivial until it met an actual CommBank statement.
Two defects it forced out, both of which had been silently writing wrong data:

**Latin keywords need word boundaries; CJK keywords must not have them.**
Plain substring matching made `UTS` fire on `KRISPY KREME DONUTS` (filing donuts
under Education) and `RENT` fire on `CURRENT ACCOUNT FEE`. Chinese has no word
boundaries, so the same rule cannot apply to both — the matcher branches on
whether the keyword is ASCII.

**Boundaries then have to bend in two specific ways**, or real merchants stop
matching:

| Case | Example | Handling |
|---|---|---|
| Possessive / plural | `MCDONALD` must hit `MCDONALDS` | accept a single trailing `s` |
| Run-together names | `GUSMAN` must hit `GusmanYGomezWestfiel` | relax the right boundary for keywords ≥ 6 chars |
| Asterisk separators | `UBER EATS` must hit `UBER *EATS` | normalise `*` to a space before matching |

That last one mattered: without it `UBER *EATS` fell through to the shorter
`UBER` rule and every food delivery got filed as **transport**.

Short keywords stay strict — `SHELL` must not match `SHELLEY`, `GYM` must not
match `GYMEA`. Reviewing the false positives showed almost all of them were
caught by the *left* boundary, which is why relaxing only the right one by
length is safe.

**Every case in this section is a unit test.** The boundary rules are three
competing concessions and it is easy to fix one merchant while silently breaking
another, so the false positives that motivated each rule are pinned as
assertions rather than described in a comment. 53 tests in total, plain JVM, no
device needed:

```bash
./gradlew test
```

Writing them immediately paid for itself twice. It surfaced that the trailing-`s`
concession is only reachable for keywords shorter than 6 characters — longer ones
hit the length relaxation first, so a test written with `MCDONALD` was not
exercising the rule it claimed to. It also caught a live bug in `PaymentParser`:
`尾号1234` (card *last four digits*) was being read as the amount whenever the
real amount had no decimal point, because the digits-after-a-hint rule demoted
card numbers to a *fallback* instead of discarding them, and an integer amount
could not displace an earlier fallback. Hints are now two sets — "not this
transaction's amount, but still money" (balance, points) versus "not money at
all" (card/order/reference numbers).

A third defect came from the other direction — from actually running the thing.
Debug builds carry a **simulate-a-payment-notification** entry (real notifications
can only be posted by the payment apps themselves, so they cannot be reproduced on
demand), and it feeds a fixed notification text through the *same* `ingest` method
the listener calls. The first run showed the source as "支付" rather than "支付宝":
`appName` used case-sensitive `String.contains("alipay")` while Alipay's package is
`com.eg.android.AlipayGphone`. WeChat and UnionPay have all-lowercase package names,
so Alipay was the only one broken — and the one existing test for this covered
WeChat, which is exactly why it never showed up. All three sources are pinned now.

---

## What the numbers actually are

Measured against a real bank statement (72 expense rows), not a hand-made
fixture:

| | |
|---|---|
| Merchant table coverage, generic rules only | **27.8 %** |
| Model-only accuracy (30 labelled samples) | **83.3 %** |
| Decode speed, Qwen3-4B Q4 on Snapdragon 8 Elite | **~38 tok/s** (~2 s per record) |

The model's errors were not reasoning failures — they were knowledge gaps. Every
miss was a utility or a local chain (`SYDNEY WATER`, `TELSTRA`, `AGL`,
`OFFICEWORKS`), and it defaulted unknown local businesses to "transport". That is
the argument for a lookup table in front of the model rather than a bigger model
behind it.

Adding one's own frequent merchants pushes coverage well past 80 %, but that
number is fitted to the same statement it was measured on, so it is not quoted
here as a result.

---

## Privacy design

- Reading notifications, parsing amounts, and merchant lookup are **entirely
  on-device and offline**.
- The only network call is the optional LLM fallback, which goes to a host the
  user types in — nothing else, no telemetry.
- The built-in merchant table ships only **national chains and generic terms**.
  Your own corner shops go in a **personal merchant table** stored in the app's
  private directory, never committed and never shipped: a list of specific small
  businesses is enough to infer where somebody lives and studies.
- Records stay in local SQLite. Backup is CSV export to a folder you choose (SAF).

---

## Features

- Manual income/expense entry, editing, recurring transactions
- Dual currency (CNY / AUD) with per-currency and combined overview
- Category pie chart and drill-down by category; month and year views, with a
  six-month or six-year expense trend to match
- Custom categories (icon + colour), on top of seven presets
- CSV import/export with de-duplication; optional daily auto-backup
- 2×2 home screen widget
- Light/dark theme following the system

## Tech

Java · minSdk 26 / target 34 · SQLite (schema v4, migrating) · `NotificationListenerService` ·
Storage Access Framework · AppWidgetProvider · custom `View` for the pie chart ·
`HttpURLConnection` for the LLM call (no HTTP library) · JUnit unit tests on the
parsing and matching logic · R8 on release builds (4.3 MB → 1.6 MB)

## Build

Two flavours: `global` is the dual-currency original, `cn` is a China-only build
that drops AUD, the exchange-rate call, and the LLM fallback — it is meant for
people who would never run a model server. They differ only in a compile-time
constants file and their merchant tables, and install side by side.

```bash
./gradlew test                                    # unit tests, no device
./gradlew assembleGlobalDebug assembleCnDebug     # debug APKs
./gradlew assembleGlobalRelease assembleCnRelease # signed, shrunk
```

Debug builds get an `applicationIdSuffix` of `.debug`, so a debug APK installs
*alongside* a release one instead of colliding with it. Without the suffix the two
share an applicationId but not a signature, and installing the debug build fails
with a signature conflict whose only remedy is uninstalling the release build —
taking the ledger with it. They now keep separate databases, and the debug build
is the one carrying the simulate-a-notification entry described above.

Release builds are signed from `keystore.properties`, which is not committed —
without it the build still succeeds and simply produces an unsigned APK, so CI
and a fresh clone are never blocked. `local.properties` (your Android SDK path)
is likewise not committed; Android Studio creates it on first open.

## Limitations

- Notification parsing only works for apps that post a notification containing an
  amount — cash and silent transactions are invisible to it.
- The LLM fallback needs a server you run yourself; without one the app simply
  leaves records uncategorised.
- Exchange rates are fetched from a public API for display only and are not
  applied to stored amounts.
