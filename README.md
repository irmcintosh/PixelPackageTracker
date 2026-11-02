## 2026-10-28 Gmail shipment delivery estimates (source/build verified)

Inbox scans, refreshes and background updates now extract shipment delivery estimates from transactional mail: explicit English dates, tomorrow/today, weekdays, named-month ranges and revised estimates. Relative dates use the original email's local date/offset, not scan time. Missing years use that date with conservative December/January rollover. Gmail `internalDate` is retained as a timestamp fallback; without a usable Date-header timezone, relative dates and year inference are not guessed.

Estimates apply only to a uniquely verified shipment: saved tracking with compatible carrier, or verified retailer/exact order number with one unambiguous child shipment. A shared estimate is never spread across multiple packages. Promotions, actual-delivery dates, quoted replies and conflicting estimates are excluded or left for review. Existing status, shipped dates, tracking links/history and archive behavior remain separate.

Room **12 -> 13** adds only `estimatedDeliveryEndAt`, `estimatedDeliverySourceMessageId` and `estimatedDeliverySourceAt` to shipments. Existing ETA values with unknown provenance stay protected. Manual selection/clearing has priority; saving unrelated fields preserves a range and its provenance. Choosing a replacement manual date intentionally replaces the range with that single date.

Newer source-email timestamps revise Gmail estimates. Identical newer estimates advance freshness without a false value change; older/equal-time conflicting evidence cannot replace reliable state. History records linked-shipment changes; standalone changes use non-status tracking events. A targeted ETA pass reexamines historical messages still in Inbox without resetting parser markers, weakening version-1000 manual associations or replaying already-processed unrelated fields. Every list page remains `labelIds=INBOX`; downloaded responses with label IDs must still contain `INBOX`.

Validation: **188 JVM tests passed (17 new ETA tests), 0 failures/errors/skips**. Debug app and Android-test APK assembly succeeded using:

```powershell
.\scripts\Build-Safely.ps1 -Tasks ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest'
```

The 15 new Room/integration/migration tests and existing instrumentation tests **compiled but were NOT RUN**; they need separately authorized disposable-AVD testing. Live Gmail and on-device UI behavior are unverified. Parsing is deliberately English/rule-based: unsupported wording, ambiguous dates, missing timezone context and ambiguous packages leave existing estimates unchanged. No carrier API dependency was added.

Internal version remains **1.0.5-dev (29)**. Validation APKs were built only; no installation, distribution publishing or ZIP creation occurred. The previously published distribution APK referenced below has **not** been updated with this feature.

## 2026-10-27 Unified copy selection dialog

Orders, Shipments and Archive now use one Material copy icon in each card heading. The dialog starts empty and offers field checkboxes, Select all, Clear, Cancel and Copy (N). One field copies its raw value; multiple fields copy labeled lines. Primary/handoff identifiers and their carrier/URL choices remain independent, and unavailable values are omitted. Inline copy icons and old full-details copy buttons are removed. Final debug build and 171 JVM tests pass; Compose/clipboard tests compile but were NOT RUN because no disposable AVD is available. No application data/schema or other workflows changed. Updated main APK: [dist/PixelTrack-v1.0.9.apk](dist/PixelTrack-v1.0.9.apk). See [developer report](COPY_DIALOG_DEVELOPER_REPORT.md).

## Correct application APK for transfer (2026-10-27)

Use **[dist/PixelTrack-v1.0.9.apk](dist/PixelTrack-v1.0.9.apk)**. It is the verified main application (`com.example.pixelpackagetracker`), with the original icon, current features and the same signing certificate as the reference main APK. Do not transfer `app-debug-androidTest.apk`: it is the separate `.test` package.

Build/copy again with `powershell -File scripts/Release-Apk.ps1`. The script takes only the fixed main-app output, verifies it and publishes the one distribution APK. Default safe builds no longer also build the test APK. Main build succeeded; 158 fresh JVM tests passed. No working installation/data was changed. Device installation remains unverified. Internal version remains 1.0.5-dev (29); the filename is a distribution name. See [release verification report](APK_RELEASE_FIX_REPORT.md).

## 2026-10-27 Launcher icon and test safety

Original custom icon and adaptive/round configuration are intact; 11/11 PNG resources match the debug APK. No icon or runtime source change. Added a root Gradle device-task guard, configuration-cache protection, an allowlisted non-device build script and repository device-safety rules. Builds and 158 fresh JVM tests pass; inert guard probes pass. No devices are connected and no separate disposable AVD exists, so instrumentation and live app-drawer checks were NOT RUN. No working installation/data was changed in this round. See [report](ICON_AND_TEST_SAFETY_REPORT.md) and [safety rules](AGENTS.md).

## 2026-10-27 Email Accounts and archive corrections

Validation for this correction: 158 JVM tests passed; 164 emulator tests passed with 2 skips and no failures; debug build succeeded. Physical Pixel and live Gmail are unverified. Earlier Gradle UTP cleanup may have removed prior emulator app data; the final install -r/manual instrumentation run leaves the app installed. See the developer report for the incident and full evidence.

Implemented the compact Gmail account header, overflow disconnect confirmation, Connect/Reconnect and Scan / Import row, immediate Delivered shipment archiving, repair of older eligible archive records, shipment Archive/Delete, and transaction-safe Archive Delete All. Active shipments survive deletion of archived parents through safe detachment. Database stays version 12. See [developer report](EMAIL_ARCHIVE_DEVELOPER_REPORT.md) for exact files, validation, traceability and unverified live-device behavior.

# Order → shipment workflow (2026-10-26)
- Tabs: Orders, Shipments, Add, Archive, Settings. Orders end at Shipped (Placed → Processing → Preparing for Shipment → Shipped, or Cancelled); packages are tracked in Shipments until delivered. Preparing for Shipment means a label or tracking number exists; Shipped needs dispatch evidence (a shipped notice, carrier movement, or a status you set). An order archives once it is Shipped and a shipment with a tracking number or carrier is saved and associated; a Shipped order without one shows "Shipment information needed". Gmail Inbox scans and background sync import verified records automatically; ambiguous mail waits in Needs Review. See CHANGELOG.md and V1_0_5_IMPLEMENTATION_STATUS.md for verification and limits.

# Permanently remove an unassociable Needs Review email (2026-10-25)
- Use "Remove from review and don't scan again" to persistently exclude that Gmail message from later Inbox scans, updates and imports. It is not falsely associated with an order and does not change the Room schema. Latest validation: 110 JVM and 82 emulator tests passed.

# Needs Review items are held out of import (2026-10-25)
- An email that needs review is not part of its order's import group and is not marked used until you associate it. Failed create/associate attempts write nothing and leave the email in Needs Review. Confirmations ("Ordered ...", `auto-confirm@`) are never classified as shipping progress. 110 JVM and 81 emulator tests passed.

# Association screen: retailer then order number (2026-10-25)
- Pick the retailer, then pick from that retailer's saved order numbers (strongest match preselected), pick a number found in the email, or type a missing one. No separate "new order" button. Verification: 109 JVM and 78 emulator tests passed.

# Confirmation detection fix (2026-10-25)
- Confirmations whose subject starts with "Ordered" (for example "Ordered 1 item: Tops") or that come from an `auto-confirm@`-style sender now seed a new order group. Verification: 102 JVM and 78 emulator tests passed; real Gmail not re-tested.

# Needs Review order dropdown and test-run safety (2026-10-25)
- The association screen's Order dropdown defaults to the strongest verified/likely saved order and can be changed to any saved order or to a new order. Amazon / Amazon.com count as one retailer.
- Never run instrumentation (including manual `am instrument`) on the working emulator or physical Pixel. Use only a separately designated disposable AVD. The root Gradle guard blocks connected/device tests and install/uninstall tasks. Use `scripts/Build-Safely.ps1` for builds and JVM tests; see AGENTS.md.

# WP-02 Needs Review association screen (2026-10-25)
- Retailer, title and order number are prefilled from the email only when reliable; empty fields show prompts. Payment processors such as PayPal are not treated as the merchant unless the email names one.
- Pick retailer offers the suggested saved retailer, all saved retailers, and Add new retailer. Pick order lists saved orders (verified/likely first) and selecting one associates the email instead of creating a duplicate. New orders can be created from the editable prefilled values; an existing retailer + order number is blocked.
- Verification: 96 JVM and 77 emulator tests passed; dialog rendering was not automated and real Gmail was not tested.

# WP-02 order-centered grouping (2026-10-25; supersedes conflicting notes below)
- Model: verified retailer + exact order number -> Room order -> child shipment(s) -> tracking number(s) -> tracking events. Order number is the grouping key; tracking numbers attach as children.
- Inbox scan, Update All, single Update, background sync and import share one resolver. Existing orders show as existing groups with "Apply updates to this order"; only a recognized order confirmation can create a new order group.
- Several tracking numbers in one email are NOT assumed to be a carrier handoff. They are applied only with separate-package wording or carrier-handoff wording; otherwise the email is listed in Needs Review with candidate orders and the evidence for/against each. The order itself still imports.
- Tracking numbers are matched exactly after normalization (USPS routing prefix removed; original spelling preserved in history). Order numbers that differ by a leading letter, or different order numbers in one Gmail thread, are never merged automatically.
- Scan results report recognized confirmations, relevant and ignored counts, and list ignored messages that mention an order number, to explain why an order was not picked up. Individual unreadable messages are skipped and counted instead of failing the scan.
- Verification: 82 JVM and 72 emulator tests passed; debug build and data-preserving emulator install passed. Real Gmail results are not verified; rescan the Inbox and report any remaining misses.

# WP-02 source-association and review follow-up (2026-10-25)
- Inbox import is now confirmation-seeded: a verified retailer plus exact external order number establishes an order group. New Room orders cannot be created from shipping or carrier emails alone. Follow-ups are inspected individually and attached only by exact retailer/order identity or a verified tracking claim; Gmail thread membership and retailer/sender identity alone never establish ownership.
- Orderless carrier messages can update a shipment only when their exact tracking number is already claimed by a verified order or saved shipment. Other relevant orderless shipping messages appear under Needs Review; messages with no supported date, notes, items, status, or tracking information are ignored.
- Saved-order updates filter out order-number mentions that contain no supported update fields, so customer-service correspondence is not marked processed just because it repeats an order number.
- Exact-order follow-ups from a different sender are grouped only when that sender’s identity strongly verifies the same retailer. Emails with multiple new tracking numbers are split to Needs Review; the order confirmation remains independently importable so shipment ambiguity cannot roll back the order.
- Order-specific updates now show correspondence reviews scoped to that order; Update All keeps unresolved correspondence visible. Duplicate merge actions are kept in Inbox → Needs Review and are computed from saved Room orders, so they are available without another Gmail scan.
- Inbox grouping and import now share normalized exact external order-number and retailer-evidence matching with saved-order updates. Import first honors saved message-to-order associations, shipment/tracking ownership, then exact identity and thread/context evidence. Conflicts abort or go to review; records are never auto-merged.
- Correspondence grouping no longer contains retailer-specific Simpli Soda, eBay, namokiMODS, or DS order-name branches. Generic sender-domain and carrier classification handle those templates; known retailers remain regression fixtures.
- Per-email diagnostics include candidate saved order IDs, accepted evidence, rejected candidate reasons, changed fields, and whether that message's association/change committed. Review UI avoids full private email bodies.
- Inbox grouping now canonicalizes verified retailer aliases when keying exact order numbers and checks every extracted tracking number, so DSWATCH / DS Designer messages and carrier mail tied to a secondary tracking number can join the same correspondence group.
- Tracking candidates now require at least six digits when alphanumeric, or ten digits when numeric. This rejects ordinary text such as `20Documents` even when it follows a tracking label or appears in a carrier URL, preventing unrelated correspondence from being logged as an unmatched shipment.
- Same-retailer order numbers that differ only by one leading letter can follow a shared Gmail thread to one unique exact-matching saved order. If there is no saved order, the unique ID in a confirmation message is canonical for that thread. Without same-thread evidence or a unique confirmation/saved-order anchor, the messages remain separate for review; IDs are never stripped or broadly prefix-matched.
- Final verification: debug build passed; 73 JVM tests and 65 Room-backed emulator tests passed (25 parser, 40 matching, 8 scan-result); instrumentation test sources compiled; the final app installed and launched on the API 37 emulator using a data-preserving install. Real Gmail processing was not performed in this session.
- Added regressions for unrelated duplicate warnings during a PayPal-specific scan, Update All behavior, persisted association precedence, normalized identity parity between update/import, and diagnostic persistence. Version, Room schema, OAuth, and Inbox-only Gmail query remain unchanged. See implementation status for final verification.

# v1.0.3 changes (source implemented; Android build and emulator not verified)

- Extract explicit purchase dates from order emails into Placed. Email received dates are not substituted.
- Extract labeled item, total, and shipping details into Notes, and an HTTPS order link when available.
- Backfill missing fields on existing orders without overwriting user notes or purchase dates.
- Require stronger past-tense evidence before automatically marking a shipment Delivered.
- Display date and time for order and shipment status updates; shipment update source is shown.
- Existing incorrectly marked Delivered records require review; Gmail alone cannot verify carrier truth.
- No database schema changes.
- Android build, emulator, Gmail integration and device behavior are NOT verified.

# Pixel Package Tracker v1.0.0

Version 1 source release for emulator acceptance testing. Not build-verified or device-tested in this environment.

Includes Orders-tab Gmail actions, scoped order updates, WorkManager periodic Inbox sync with configurable interval, and notifications when enabled and permitted. Android 13+ notification permission is requested from Settings → Notifications ("Allow notifications"); status notifications follow the per-status switches. Background Google authorization may require interactive reconnection. Gmail scan is limited to the most recent 100-message pages available from Inbox. No carrier APIs.

# Pixel Tracker v0.5.0-dev9.3 — Six-hour Default

Open this folder in Android Studio. **Scan / Import** now lives in Settings → Email Accounts (next to **Connect to Email**) and leads to the existing Gmail authorization, full-INBOX scan, grouped review, and per-order import actions; Orders and Shipments keep their Update / Update All actions. Imported records appear in Orders via Room. Existing manual order and shipment features are retained.

**Limitations:** Android build and device behavior have not been verified. Update All and per-order Update route to Inbox review; they do not automatically update saved statuses. No carrier API tracking is implemented. The import flow is not yet a completed MVP.

Do not uninstall the installed app or clear its data during testing. Test one known order first, restart the app, and check duplicates before importing more.

### v0.5.0-dev9.2 import review
After an Inbox scan, use **Import This Order** for individual records or **Import All** to process eligible groups. Each card shows its latest import outcome. Import All requires confirmation and reports newly imported, already-saved, and attention-needed counts. This source release has not been compiled or device-tested here.

### v0.5.0-dev9.3 settings correction
The default tracking/check interval is **6 hours** for fresh installs and users without a saved interval. Existing saved interval selections are preserved. The Settings description now explicitly says background Gmail rescanning is not yet implemented. This is a settings-only update; no WorkManager schedule or silent Gmail reauthorization has been added.

## dev9.4 beta test notes
This release adds Cancel Editing and an unsaved-change confirmation for edited orders, a Beta/About roadmap, and an 8-hour interval preference. Gmail scanning and reviewed import remain manual. Background Gmail sync, automatic shipment status updates, and notifications are **not implemented** in this build. This is a source test candidate, not a production-ready APK.


## v0.5.0-dev9.5 partial test candidate
- Default refresh preference for **new installations** is 8 hours. Existing saved preference remains unchanged.
- Inbox Import All only offers new orders; when none are new, Exit is shown.
- Future roadmap: other email-provider support, then free authorized carrier API calls.
- **Not ready for everyday-use release:** manual Update and Update All are still routed to Inbox; background Gmail synchronization, reconnection alerts and status notifications are not implemented.
- Source changed; no Gradle compilation or Pixel test performed.

### dev9.5 repair candidate warning
This source archive contains manual Gmail workflow repairs but is **not** the production-ready v1.0 release. See BUILD_READINESS.md before building. Future priorities: Yahoo/other email providers and authorized free carrier APIs.

### v1.0.4 notes
Order cards now show Last synced at top right. It reflects successful Gmail Inbox checks, not carrier API checks. Purchase date falls back to an order-confirmation email's sent date only when the message is identified as a confirmation. Order Notes extraction is heuristic; missing fields remain unknown. See V1_0_4_TESTING.md for emulator checks and known limitations.


### WP-02 exception and Inbox-scope follow-up (2026-10-25)
- Root cause: delivery-exception emails were classified by the parser, but their status was discarded by the write path. Since exception messages were also excluded from the shipping-message guard, a matched message could be marked processed without updating a shipment or remaining in Needs Review.
- A uniquely matched exception now updates its shipment to `Exception`, records the change, and commits its processed marker with that update. If no shipment can receive it, the email remains unprocessed and appears in Needs Review. A later delivery or out-for-delivery message can still advance the shipment; an exception cannot replace `Delivered`.
- The Gmail list path is centralized and regression-tested for the exact `labelIds=INBOX` query on initial and paginated requests. Gmail discovery, manual updates, background sync, and parser-version reanalysis continue to use the same Inbox-only fetch path.
- Update results now include expandable per-email diagnostics: retailer, order number, carrier, tracking number, matching evidence, association outcome, and fields changed. Needs Review shows the extracted retailer/order/carrier/tracking evidence and reason without displaying full message bodies.
- Added JVM parser/query assertions and Room-backed scan tests for persisted exceptions and unmatched exception review. Validation in this session: debug APK build passed; all 58 JVM tests passed (24 parser, 26 matching, 8 scan-result); Android instrumentation sources compiled. Instrumentation execution was attempted but the API 37 AVD stayed `OFFLINE`, so no instrumentation test ran. Real Gmail/OAuth and physical Pixel behavior were not tested. Earlier WP-02 results below remain historical and were not used as this session's result.

### v1.0.5 WP-02 association regression repair (Simpli Soda / eBay / DSWATCH)
Version unchanged (1.0.5-dev, versionCode 29, Room schema 10, no schema change). Details, root causes and limits: V1_0_5_IMPLEMENTATION_STATUS.md.
- **Automatic association first:** retailer + exact order number; otherwise the Gmail thread, a single open order of that retailer, or a carrier email through a tracking number that exactly one order claims. Several plausible orders are never guessed; they appear under **Needs review** below the Update results.
- **Parsing:** HTML link targets are kept, entities and non-breaking spaces normalized, tracking numbers found in sentences and authorized carrier links (FedEx, UPS, USPS, DHL, OnTrac, Cainiao hosts only). A retailer shipping notice with no tracking number creates a shipment without inventing one.
- **Manual fallback:** Needs review lets you associate an email with an order or shipment after a preview; the choice is remembered (processed marker, version 1000) so it is not asked again. Duplicate orders can be merged only after confirmation. **Association** (connect an email/tracking number to a record) and **merge** (combine two records of one purchase, keeping items, shipments, tracking, events, history and markers) are different actions.
- **Retailer aliases:** DSWATCH = DS Designer is built in; other names are merged only if you confirm they are one retailer (Settings lists them).
- **After updating the app:** run **Update All via Inbox** once so messages recorded by the earlier build are reanalyzed (background checks do not reanalyze).
- **Retailer enrichment:** only from Gmail. No retailer API, order-page retrieval or carrier API is implemented or claimed; `View Order` links are stored and opened in the browser.
- **Verification (this machine):** 57 JVM tests and 56 in-memory Room emulator tests pass (Medium_Phone_API_37.0). Real Gmail, real emails, Compose UI flows and a physical Pixel were **not** tested.

### v1.0.5 WP-02 (Gmail matching, import safety, history) — source complete, partly verified
Version unchanged: versionName 1.0.5-dev, versionCode 29, Room schema 10 (no schema change). See V1_0_5_IMPLEMENTATION_STATUS.md for the exact list of what is verified.
- **Matching:** an email is applied to an order only when retailer and order number agree (exact normalized token; no substring or prefix matches). Equal order numbers at different retailers never cross over. A tracking number saved on exactly one order is strong evidence; anything that could belong to several orders is shown as *review required* and not applied.
- **Import:** one transaction per import; review or failure rolls everything back, including processed-message markers. Concurrent scans and imports are serialized.
- **History:** Gmail scans, Inbox import, manual order/shipment edits, tracking-number changes and order-status changes are recorded with old/new values and provenance (Gmail message, manual edit, derived from shipment status, inbox import). Bookkeeping-only changes (Last synced, identical values) are never recorded.
- **Manual order status rule:** an order status you pick yourself is never advanced by Gmail. Shipment status keeps updating from Gmail; Gmail may advance an order status only if you have not chosen it manually.
- **Reanalysis:** processed messages carry the parser version. Manual Update / Update All reanalyze messages from older parser versions (reported as *Backfill*); background checks do not.
- **Verification (this machine):** Gradle 8.11.1 + JDK 21 build; JVM tests and in-memory Room instrumented tests run on an API 37 emulator image (Medium_Phone_API_37.0). Real Gmail, Compose UI flows and physical Pixel behavior were **not** tested.

## v1.0.5-dev warning
This source snapshot compiles and passes its JVM and in-memory Room instrumented tests (see the WP-02 section above), but it has not been tested with a real Gmail account or on a physical Pixel. The 16-item checklist is not fully complete. Review V1_0_5_16_ITEM_AUDIT.md before testing; do not install over irreplaceable data without a backup.
