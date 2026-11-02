## 2026-10-28 Gmail shipment ETA extraction

- Added strict `java.time` delivery-date extraction for exact dates, tomorrow/today, same-day/next weekdays, named-month ranges, missing-year rollover and explicit revisions. Date labels may have their value immediately below them. Historical resolution uses the email Date header and offset; Gmail `internalDate` is retained only as a timestamp fallback, not an invented sender timezone.
- Added the additive Room **12 -> 13** migration: nullable `estimatedDeliveryEndAt`, non-null `estimatedDeliverySourceMessageId` defaulting to empty, and nullable `estimatedDeliverySourceAt`. Existing records/ETAs remain intact; unknown-provenance ETAs are protected.
- Applied estimates after verified child-shipment resolution and through a shared standalone/linked ETA helper. Multi-package ambiguity and conflicting equal-time estimates never trigger guessed replacement. Newer identical evidence refreshes provenance without reporting an ETA change.
- Manual date selection and deliberate clearing block automatic replacement. Unrelated edits retain ranges/provenance. Cards, details and clipboard show the same single-date/range value using stable date-only formatting for new estimates; legacy date interpretation is preserved.
- Historical ETA backfill is independent of ordinary parser-version replay. Existing version-3 and authoritative version-1000 associations remain intact. Linked revisions use order history with stable shipment IDs; standalone revisions use non-status events. ETA-only updates count correctly and do not imply shipment movement or trigger status notifications.
- All Gmail pages remain Inbox-only. Downloads whose response labels no longer contain Inbox are excluded before decoding/processing; no unrestricted search or thread expansion was added.
- Validation: **188 JVM tests passed, zero failures/errors/skips** (17 ETA tests); main debug APK and debug Android-test APK assembled with the safe script. An early new tomorrow test failed, the connector-boundary bug was fixed, and subsequent full suites passed.
- **Not run:** 15 new Room/integration/migration tests and all device instrumentation; compilation is not execution. Live Gmail and on-device UI/upgrade behavior remain unverified. Existing coroutine opt-in/deprecated Compose API warnings remain. No device, app data, published distribution APK or ZIP was changed. Internal app version remains 1.0.5-dev (29).

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

# v1.0.5-dev (2026-10-27) — Gmail scan memory fix
- Root cause: Kotlin `Regex.findAll` and `Regex.replace(input) { }` open a new `java.util.regex.Matcher` for every match, and on Android each Matcher copies the whole input string into native memory that is released only by a later GC. `identifierTokens` ran `findAll("[A-Za-z0-9]+")` over every email body and kept the results in a list (one Matcher per word), so a 120K-character email held gigabytes of native memory. The Gmail fetch and MIME parsing were not the cause (they stayed under 40 MB native).
- Fix: `Regex.scan` and `Regex.replaceEach` (GmailParsing.kt) reuse one Matcher and return detached results; every `findAll` and replace-with-lambda in the Gmail parsing and matching code uses them. Matching results are identical (verified against Kotlin's own results in a test). No schema or workflow change.
- Emulator, real mailbox (40 messages): Scan / Import native heap peak 51-60 MB over three repeated scans (was 1-2.4 GB and killed 4 of 5 times); Orders Update All 46 MB (was ~1.1 GB); Shipments Update All 49 MB. The app process stayed alive through all runs.

# v1.0.5-dev (2026-10-27) — Copyable details, email-based dates, Scan / Import moved to Settings
- Copy: order and shipment cards are selectable, with copy icons beside order numbers, tracking numbers, order URL and tracking URL, and "Copy order details" / "Copy shipment details" in the expanded view (active and archived records). Copying uses the Android clipboard only and writes nothing to Room.
- Order date: an order confirmation's own Gmail message date is the order date (previously a body label won); without a confirmation the earliest applied order email is used and labeled approximate. An established date is not overwritten, and manual dates are untouched.
- Shipped date: set only by the dispatch / carrier-acceptance email that touched the shipment (added "accepted by carrier" wording); label or tracking-number mail, and later In Transit / Out for Delivery / Delivered mail, never set or change it. Previously the earliest dispatch mail of the whole scan dated every touched shipment.
- Scan Inbox removed from the toolbar and Orders tab; Settings → Email Accounts shows Connect to Email, then Scan / Import (opens the existing panel; Back returns to Settings). No schema change; existing fields (`orders.placedAt`, `shipments.shippedAt`, history suffix " (approximate)") were sufficient.
- Performance: dispatch evidence is computed once per email in `buildEvidence`, and the Scan panel no longer regroups the whole scan on every recomposition.

# v1.0.5-dev (2026-10-26) — Shipments tab: Update and Update All
- Each active shipment card has Update, and the tab has Update All via Inbox. Both open the existing Inbox panel (reconnect handling unchanged), fetch the Inbox once (Update All reuses that one message list for every shipment) and call `updateShipmentsFromEmails`. Only mail naming a shipment's saved tracking number with a compatible carrier is used; shipments without a saved tracking number are skipped and reported; nothing is created.
- Standalone shipments use the existing standalone update (one tracking event per Gmail message, forward-only status). Order-linked shipments, including those of archived orders, go through the same per-order update as Orders → Update, so the existing order status, history and handoff rules apply to a shipment that really moves; no order rule was changed.
- Cards show "Status updated" and "Last checked". Last-checked times live in their own DataStore file (no Room migration) so a check never re-emits Settings, whose collector re-applies the background schedule; the Tracking Interval and its WorkManager job are untouched.
- Real status changes (shipment and any resulting order change) go through `withStatusNotifications`, so the existing switches and Android permission apply.

# v1.0.5-dev (2026-10-26) — Four-stage Orders progress and order-status notifications
- Orders lifecycle is Placed → Processing → Preparing for Shipment → Shipped (Cancelled separate). Preparing for Shipment is derived when a shipment has a tracking number or Label Created status; a tracking-number-only email is now stored as Label Created (previously In Transit) and never marks an order Shipped. Shipped still comes only from dispatch wording, shipment movement or a status you set; archiving still needs Shipped plus a saved shipment with a tracking number or carrier. No schema change (status is a text column).
- Order cards show four-stage progress from the order status only; shipment delivery progress stays in Shipments.
- Missing notifications, root cause: only the WorkManager worker ever posted notifications, and it compared shipment statuses only. Order statuses were never compared, and foreground (manual) scans never posted anything. Both paths now go through `withStatusNotifications`, which snapshots order and shipment statuses around the scan's database work and posts one notice per status that actually changed (so repeat scans, new records and ambiguous mail post nothing). Foreground and background scans share a lock so a change is announced once.
- Settings: new "Order progress" switch for Processing and Preparing for Shipment (default on). Shipped follows the Shipped switch, Cancelled the exception switch, "Every tracking update" covers all, and Android permission remains required.

# v1.0.5-dev (2026-10-26) — Tracking Interval audit and background-sync fixes
- Audit found the interval, periodic job, Gmail import path and archived-order matching already correct; they were not rewritten. Fixed: notifications could never fire (the hidden `notifications` master flag defaulted off and the Android 13+ permission was never requested). Settings → Notifications now has "Allow notifications"; the per-status switches decide, and "Every tracking update" now works.
- Worker: a transient Google authorization failure is retried (previously treated as "reconnect needed"); a missing grant alerts once a day without retrying; failures retry with backoff up to 3 runs, then wait for the next period; cancellation is no longer converted to a retry.
- Orders tab: active orders show purchase status only (no shipment delivery status, progress stages or "Update status"); archived orders and the Shipments tab keep them.
- JVM 148 and emulator 118 tests passed (new: `BackgroundSyncTest`, `TrackingIntervalSchedulingTest`). Gmail authorization in the background, live retries and notification delivery are not tested.

# v1.0.5-dev (2026-10-26) — Order → shipment workflow: handoff, Shipments tab, automatic import
- Shipments tab (all active shipments, including those of archived orders and standalone ones; search; Active/Delivered/Archived). Orders and Archive tabs remain; active standalone shipments moved to the Shipments tab.
- Handoff: an order archives only when it is Shipped AND has at least one saved, associated shipment with a tracking number or carrier. Evaluated after order-status changes, shipment creation/association (manual and Gmail) in the same transaction. A Shipped order without shipment information stays active and shows "Shipment information needed / Tap Shipped to add shipment details". Archiving never touches shipments.
- Order statuses are Order Placed, Processing, Shipped, Cancelled; Delivered and Partially Shipped are no longer selectable and delivery never changes an order (legacy values preserved). Gmail reads Processing and Cancelled notices (forward only, never over a manual status).
- Automatic Gmail import: manual Inbox scans and background sync import verified orders and standalone shipments without confirmation; ambiguous mail stays in Needs Review. Scan summary: orders created/updated, shipments created/updated, needs review, ignored.
- A standalone shipment is now associated with an existing (including archived) order when an email ties its tracking number to the exact order number; archived orders are offered in manual association. Additional packages ("package 2 of 2") add a shipment without reactivating the order.
- Purchase date fallback: earliest applied order email, labeled "(approximate)"; replaced by an explicit date later. New nullable `shipments.shippedAt` (Room 11 → 12, additive) with date pickers.
- Retention: an archived order expires only when every one of its shipments is itself archived and past the cutoff, so the cascade can never delete a live shipment or its tracking history.
- JVM 145 and emulator 117 tests passed (`am instrument`; app not uninstalled). Live Gmail ran only through the background worker on the emulator (see status file).

# v1.0.5-dev (2026-10-26) — Gmail discovery no longer requires a confirmation; standalone shipments; stored carrier
- A verified retailer notice with one order number and at least two order-specific details can establish an order (shared decision for scan, import and UI). Genuine shipments with no order become standalone shipments. Needs Review is limited to ambiguous or insufficient evidence.
- Shipments store their carrier without a tracking number (Room 11, additive migration with carrier backfill); Needs Review keeps typed titles and prefills them from the subject; Gmail updates never rename an order.
- JVM 140 and emulator 99 tests passed; existing emulator data migrated intact. Live Gmail not tested.

# v1.0.5-dev (2026-10-26) — Out for Delivery / Delivered emails can create an order from review
- An order with no shipment now gets one from an Out for Delivery or Delivered notice (as already done for shipped notices), so "Create order … and associate" no longer fails for such emails. Exception notices still need review.

# v1.0.5-dev (2026-10-26) — Scan results show only groups you can act on
- Groups that cannot be imported or applied (e.g. an eBay shipping update with no confirmation or saved order) no longer appear as scan cards; they are listed under Needs review with Associate/Create and Remove actions.

# v1.0.5-dev (2026-10-26) — Order card progress follows persisted shipment status
- Purchase progress shows the exact shipment status (`4 of 5 · Out for Delivery`), aggregate labels and counts for multiple shipments, explicit Customs/Exception, a manual-status note, per-shipment `Shipment: <carrier> · <status>` lines, and a three-stage progress bar on standalone shipments (`OrderProgress.kt`).
- Gmail shipment-status transitions no longer let stale mail reopen an exception, clear a newer exception, or regress Customs; Delivered stays terminal. No Room schema change.
- Build passed; JVM 128 passed; emulator 88 passed via `am instrument`; app data preserved. UI checked only for Ordered and In Transit cards.

# v1.0.5-dev (2026-10-25) — Permanently remove unassociable review emails
- Needs Review email cards now have "Remove from review and don't scan again". This persists the Gmail message ID in DataStore, without assigning it to an order or changing Room schema. Dismissed messages are excluded from Inbox scan results, saved-order updates and imports. Failed dismissal is reported and the card stays visible.
- A Room-backed regression verifies dismissal survives filtering for scan/update/import, adds no order marker, and skips the group on import. JVM tests: 110 passed; emulator tests: 82 passed via `am instrument`; app install time unchanged.

# v1.0.5-dev (2026-10-25) — Review items are never imported; confirmations are never read as shipping
- Cause of "Ordered 1 item: Tops reports shipping progress but no single shipment...": `classifyEmail` only treated a message as a confirmation when the subject matched the older "confirmed/placed" wording, so an Amazon confirmation whose body said "on its way" or "Shipped with..." was classified as shipping and creating the order was (correctly) rolled back. The classifier now treats an "Ordered ..." subject or an `auto-confirm@`-style sender as a confirmation (`hasConfirmationHeader`), shared with order-confirmation detection.
- Emails that need review are held out of their order's Inbox group at scan time (several tracking numbers without separate-package or handoff wording appear only in Needs Review), so importing an order can never apply them before the user associates them. Review groups are never importable.
- A rolled-back create/associate attempt writes nothing, leaves no processed marker and says so ("The email was not used and stays in Needs Review"). Once an email is associated it is marked processed, not offered again, and cannot be associated or used to create another order.
- Previous verification count: 110 JVM and 81 emulator tests; the later persistent-dismiss change is verified above with 82 emulator tests.

# v1.0.5-dev (2026-10-25) — Association screen follows retailer → order number
- The "New order from this email" button/Order dropdown is removed. Retailer is a dropdown (suggested saved retailer, saved retailers, the retailer found in the scanned email, Add new retailer). Order number is a dropdown of only that retailer's saved orders (strongest verified/likely match first and selected by default), then order numbers found in the email that are not saved yet, then "Enter an order number" for typing one that is missing.
- Choosing a saved order leads to Review association (never creates a duplicate). Choosing or typing a new number shows Title and Create order. Changing retailer reselects that retailer's strongest order or falls back to the email's number; a number the user typed is kept. A payment-processor email with no merchant starts on the verified saved order's retailer when one exists.
- Verification: 109 JVM and 78 emulator tests passed (emulator via `am instrument`; the app install time was unchanged and survived an emulator restart). Dialog layout not automated; real Gmail not re-tested.

# v1.0.5-dev (2026-10-25) — Amazon-style confirmations were not recognised
- Root cause of "order number is present, but no matching confirmation": an Amazon confirmation (`"Amazon.com" <auto-confirm@amazon.com>`, subject "Ordered 1 item: Tops") was not detected because only the literal subject prefix "Ordered:" counted. A subject starting with "Ordered" and a sender such as `auto-confirm@` / `order-confirmation@` are now confirmation signals (an order number is still required and shipping notices are still excluded), so the email seeds a new order group instead of landing in Needs Review.
- Needs Review cards show a clean retailer name instead of the quoted sender display name.
- Verification: 102 JVM and 78 emulator tests passed (emulator run via `am instrument`; the app was not uninstalled by the run). The emulator had been restarted before this run and the app was installed fresh, so no earlier saved orders are present on it. Real Gmail not re-tested.

# v1.0.5-dev (2026-10-25) — Needs Review: Order dropdown defaults to the strongest match
- The association screen has one Order dropdown listing saved orders ranked Verified, Likely, Same retailer, plus "New order from this email". It defaults to the strongest verified/likely match (otherwise a new order), warns when two orders are equally strong, and shows the evidence. Choosing a different order or a new order is always possible; the create fields appear only for a new order.
- Retailer names differing only by a domain suffix (Amazon / Amazon.com) are one retailer for prefill, ranking and the duplicate guard.
- Correction: earlier entries claiming "data preserved" were wrong. `connectedDebugAndroidTest` uninstalls the app afterwards, which erased the emulator's saved orders (the Orders table was empty, so the Amazon email had nothing to match). Instrumentation is now run with `adb install -r` plus `am instrument` so the app is never uninstalled, and the alias regression test restores the app's real alias settings.
- Verification: 100 JVM and 77 emulator tests passed (emulator run via `am instrument`; app first-install time unchanged and a marker file in app data survived).

# v1.0.5-dev (2026-10-25) — WP-02 Needs Review association screen refinement
- Retailer, Title and Order number are prefilled only from reliable email evidence (sender, single item, single order number); anything else stays empty with a prompt. Several order numbers are offered in Pick order, not guessed.
- Payment processors (PayPal, Stripe, Square, Venmo, Klarna, Affirm, Afterpay, Braintree) are no longer treated as the merchant: the merchant is used only when the email names it, otherwise Retailer is left empty with a hint. Personal mail senders do not prefill a retailer.
- Pick retailer: suggested saved retailer (saved spelling) first, every saved retailer once, the extracted new retailer, and Add new retailer (editable). Pick order lists saved orders ranked Verified, Likely, Same retailer; choosing one goes to the association confirmation rather than creating an order. Creating an order that matches a saved retailer (alias-aware) and exact order number is blocked in the UI and in the database call, with an Associate-instead action.
- Form state is held in one `AssociationFormState` created per email, so recomposition and picks never overwrite typed values (picking a title only fills an empty retailer). `senderDomain` now reads the address in the final `<...>` (fixes the garbled `Com" <service@paypal` retailer).
- Verification: 96 JVM and 77 Room-backed emulator tests passed; debug build passed; data-preserving install and launch passed. No Compose UI test library is available in this project, so dialog rendering was not automated.

# v1.0.5-dev (2026-10-25) — WP-02 order-centered grouping (Order → Shipment → Tracking)
- Inbox preview now resolves saved orders through the same `assignEmails()` resolver used by Update, background sync and import, so an existing verified order appears as an existing order ("Apply updates to this order") with its saved shipments and tracking numbers beneath it, never as a new import.
- A genuine confirmation (broader detection: confirmation subjects, "Ordered:", "Thank you for your order", "We received your order", "Receipt for order", or order wording/purchase details beside one order number; never a shipping notice) is the only thing that seeds a new order. Previously only a narrow subject pattern did, which could hide new orders.
- Multiple tracking numbers are no longer assumed to be a carrier handoff. They are applied only with explicit separate-package wording (one child shipment each) or carrier-handoff wording (primary + handoff on one shipment); otherwise the email goes to Needs Review and the order is still imported/updated. Ambiguous emails no longer roll back a verified order import.
- Tracking numbers match exactly after normalization; the only rewrite is the USPS routing prefix (`420` + ZIP). Prefix/suffix fragments are no longer treated as the same number. Original spelling is kept in history.
- Reverses the earlier same-thread order-number-variant joining: a one-letter order-number difference is never merged automatically (same thread or not); it appears as a conflict/Needs Review candidate.
- Needs Review cards list candidate parent orders with evidence (retailer, order number, tracking). Scan results show recognized-confirmation/relevant/ignored counts and an expandable list of ignored messages that mention an order number. One message that cannot be fetched no longer aborts the scan; the skipped count is reported.
- Verification: 82 JVM tests and 72 Room-backed emulator tests passed; debug build passed; app installed with `install -r` (data preserved) and launched. No real Gmail was processed here.

# v1.0.5-dev (2026-10-25) — WP-02 source association and review repair
- Make verified retailer + exact external order number the primary identity. New Inbox orders require a confirmation; shipping/carrier-only messages cannot create order records.
- Ignore messages that merely repeat an order number without supported notes/date/items/status/tracking information. Associate orderless mail only through an exact tracking number already claimed by a verified order or saved shipment; retailer/thread-only evidence stays in Needs Review.
- Group exact-order follow-ups from alternate senders only when sender identity strongly verifies the retailer. Split single emails containing multiple new tracking numbers to Needs Review while leaving the confirmation independently importable.
- Scope update review items to correspondence relevant to the requested order; Update All retains unresolved correspondence results. Duplicate merge actions now live in Inbox → Needs Review, derived from current Room orders without requiring Gmail scanning.
- Share normalized exact retailer/order identity resolution between Inbox grouping/import and saved-order updates. Prior saved message associations are authoritative before inferred tracking, order-number, thread, or retailer context; conflicting owners remain ambiguous.
- Remove known-retailer matching/title special cases from correspondence grouping. Generic domain/carrier evidence is retained, with named retailers represented only in test fixtures.
- Extend scan diagnostics with candidate Room order IDs, rejected-candidate explanations, commit state, and changed fields, without displaying full message bodies.
- Canonicalize verified aliases during Inbox order grouping and join by every extracted tracking number, not only the first tracking candidate.
- Reject low-digit alphanumeric “tracking numbers” (for example `20Documents`) from message fields and recognized carrier URLs; unrelated messages no longer appear as tracking-review groups.
- For same-retailer order IDs differing by one leading letter, use a shared Gmail thread with a unique saved order when available, or the unique order ID in the thread’s confirmation when importing a new order. Otherwise keep the groups separate and flag them for review.
- Add a Room/ViewModel regression for the PayPal-specific update versus the unrelated DS duplicate, plus normalized import/update matching and persisted association tests.
- Verification: 73 JVM tests passed (25 parser, 40 matching, 8 scan-result); 65 Android instrumentation tests passed on API 37; debug build and instrumentation-source compilation passed; final debug app installed/launched with app data preserved. No real Gmail correspondence was processed here.

# v1.0.5-dev (2026-10-25) — WP-02 exception persistence and Inbox query regression
- Persist shipment exception evidence when a shipment is uniquely identified; unmatched exception emails remain in Needs Review and are not marked processed.
- Keep `Delivered` terminal against a later exception message, while allowing subsequent transit/delivery progress after an exception.
- Centralize the Gmail list request path and add a regression for Inbox-only discovery across pagination. The query remains `labelIds=INBOX`; OAuth scope and Room schema are unchanged.
- Add scan-only per-email diagnostics for retailer, extracted identifiers, matching evidence, outcome, and changed fields; show extracted evidence in Needs Review without exposing full email bodies.
- Added JVM classification/query tests and Room-backed scan regressions. Verified in this session: debug APK build passed, 58 JVM tests passed, and instrumentation test sources compiled. Instrumentation execution was blocked because the API 37 AVD remained `OFFLINE`; no emulator test result is claimed.

# v1.0.5-dev (versionCode 29) — WP-02 association regression repair (Simpli Soda, eBay, DSWATCH); build and tests run, no real Gmail/device verification

- Gmail parsing: HTML links are kept (`[link: url]`), all entities and non-breaking/zero-width characters are normalized, encoded headers, charsets and attachment-backed text parts are handled, plain-text `<https://...>` links survive.
- Tracking numbers: found in sentences ("Putting the tracking number below into FedEx: 38429466112"), split lines and authorized carrier tracking links; order/invoice/reference numbers and retailer "Track package" links are never tracking numbers. Carrier detection uses word boundaries and evidence order (no more "groups" = UPS).
- Shipping evidence no longer depends on the subject; confirmation emails and conditional sentences ("we'll email you when it ships") never count as shipped.
- Association is staged (order number + retailer, Gmail thread, single open order of the retailer, carrier mail via claimed tracking number, carrier mail naming one retailer); anything that fits several orders goes to review. Shipping mail without a tracking number creates a `Retailer · Carrier` shipment and no invented number.
- Retailer aliases (built-in DSWATCH = DS Designer; user-confirmed groups in DataStore) are used by matching, import, manual duplicate check; unverified names are never merged automatically.
- New Needs Review section under Update results: associate an email with an order/shipment after a preview (remembered through a processed marker with version 1000 in the existing table), and merge duplicate orders after explicit confirmation (items, shipments, tracking, events, history and markers are preserved). Association and merge are separate operations.
- Processed-message handling: shipping/delivery mail that could not be applied is no longer marked processed; `GMAIL_PARSER_VERSION` is now 3 so earlier markers are reanalyzed by a manual Update.
- `Label Created` shipment status for a tracking number without movement evidence (does not count as shipped for the order roll-up). Item names, quantity and price are filled from explicit `Item:` lines when an order has none.
- Tests: 23 JVM (GmailParsingTest) and 24 emulator tests (Wp02AssociationRegressionTest) added. Executed here: 57 JVM and 56 emulator tests pass.
- No Room schema, versionName, versionCode or dependency change. No retailer API, order-page fetching or carrier API was added (none verified/permitted); see V1_0_5_IMPLEMENTATION_STATUS.md.
- Not verified: real Gmail/OAuth and real emails, Compose UI interaction, physical Pixel.

# v1.0.5-dev (versionCode 29) — WP-02 complete (Gmail matching, import safety, history); build and tests run, no device/Gmail verification

- Matching: retailer + exact normalized order number; whole-token identifier comparison (no substring/prefix collisions); cross-retailer collisions and tracking numbers claimed by several orders route to review instead of guessing; unique tracking numbers can break ties and attach carrier mail.
- Grouping: Gmail message-ID deduplication, retailer-aware saved-order matching, retailer emails with shipment evidence but no tracking number are supported without inventing a number.
- Import now runs in one transaction (with the same lock as scans), rolls back fully on review or failure, records processed-message markers with the data, and records history for existing orders.
- History: Gmail, import, manual order/shipment edits, tracking changes and order-status changes record old/new values with provenance; bookkeeping-only changes are not recorded. Manual saves no longer bump timestamps when nothing changed.
- Manual operations (save, edit shipment, add tracking, link, status changes) read fresh rows in a transaction and refuse a tracking number that already belongs to another shipment; the refusal is shown on screen.
- Order status chosen manually is never advanced by Gmail (documented rule); shipment status is independent.
- Parser versioning: `GMAIL_PARSER_VERSION = 2`; manual Update reanalyzes older markers as backfill, background sync does not.
- Tests added: 26 JVM tests (GmailMatchingTest) and 25 in-memory Room instrumented tests (Wp02ImportSafetyTest). Executed here: 34 JVM tests and 32 instrumented tests pass on an API 37 emulator image.
- No Room schema, versionName or versionCode change; DAO gained read-only queries and one version-upgrade update query.
- Not verified: real Gmail API/OAuth, Compose UI interaction, physical Pixel, schema migration 9 to 10 test, WP-01/WP-03 items.

# v1.0.5-dev (versionCode 29) — WP-02 targeted fixes (source implemented; superseded by the entry above)

- Gmail update scans now return per-order outcomes (updated, unchanged, review required, failed) with per-field before/after, source email, applied time and change kind; shown on the Update screen.
- Each order updates in one transaction; results list only committed changes, and a failure rolls the order back. Timestamp-only and repeated identical values are not reported as updates. Review-required emails are not applied.
- Orders now reflect Gmail updates: order status advances (never downgrades) from persisted shipment status, update logic reads fresh database state, scans are serialized and not cancelled mid-apply.
- Added JVM unit tests and in-memory Room instrumented tests (not run). No schema, versionName or versionCode change.
- Known: Gradle/Android SDK unavailable here, so no compilation or test result exists.

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

Source implemented: v1.0.0 version, periodic WorkManager Gmail sync, notification channel, and previous Orders-tab Gmail repair. Build verification and emulator testing remain pending. Known issue: Android 13+ notification runtime permission request UI not implemented.

## v0.5.0-dev9.5 — partial test candidate (not release-ready)
- Changed new-install default interval to 8 hours; existing preferences preserved.
- Import bulk action filters previously saved orders and offers Exit when none new.
- About roadmap specifies future email providers and authorized carrier APIs.
- Known incomplete: Gmail update actions, background sync, notifications, reconnection.
- Build/device validation not performed.

## v0.5.0-dev9.4 — source beta candidate (unverified build)
- Added Cancel Editing and discard confirmation for changed order forms (including Android Back).
- Added About · Beta section with planned next features.
- Added 8-hour interval option; setting does not yet schedule background work.
- No carrier API, scraping, Yahoo Mail or paid service added.
- Existing Gmail Inbox scan/import retained; automatic sync is still pending.
- Build verification: not run; device test: not run.

## v0.5.0-dev9.3 — Tracking interval default
- Changed the default check interval from 3 to 6 hours (including DataStore fallback).
- Preserved previously selected intervals.
- Clarified that automatic background Inbox rescanning is not yet implemented; carrier APIs remain deferred.
- Updated displayed app version and build metadata.
- Source/package verified; Android build and device tests not performed.

# Changelog

## v0.5.0-dev9.1 (compile-fix candidate)
- Fixed Kotlin visibility error: importScanGroup publicly exposed a private ScanGroup type.
- Kept GmailPreview and ScanGroup internal and made importScanGroup internal.
- Build not verified in this environment; user to rebuild in Android Studio.

## v0.5.0-dev9 (source candidate)
- Corrected release directory and version.
- Exposed Inbox scan/import from Orders tab.
- Show linked tracking and external View Tracking on unified order cards.
- Preserved read-only Gmail and existing Room import logic.
- Known: automated status refresh remains incomplete; build and device testing pending.

## v0.5.0-dev9.2 — Per-order import feedback and Import All (source implemented)
- Added a visible result to each scanned order after individual or bulk import.
- Added Import All with a confirmation dialog and running summary of imported, existing, and attention-needed orders.
- Kept per-order import available and prevented simultaneous import operations.
- Cleared previous scan outcomes on new scans or Gmail session reset.
- No schema change. Android build and device testing not performed in this environment.

## dev9.5 repair candidate (source only; not validated)
- Routed Inbox Import, individual Update and Update All through an Orders workflow rather than Settings.
- Added conservative existing-order Gmail matching and shipment association for manual updates.
- Added silent token request on manual updates with interactive reconnect fallback.
- Corrected Gradle versionName/versionCode to 0.5.0-dev9.5 / 23.
- WorkManager, Android notifications, and full emulator testing remain incomplete. Do not classify as production-ready.

## v1.0.1 — Kotlin build error correction (2026-10-24)
- Changed `GmailPreview` from `internal` to public to resolve the reported Kotlin visibility error in `updateOrdersFromEmails` (MainActivity.kt:394).
- Bumped versionName to 1.0.1 and versionCode to 25.
- Validation: ZIP integrity and source inspection only; Android Gradle build and emulator execution not available in this environment.

## v1.0.2
- Restored Gmail account connection/status in Settings, while preserving Orders import connection.
- Added session disconnect and future-provider labels.
- Corrected About version and stale Settings text.

## v1.0.4 — Gmail detail and sync display corrections
- Added `orders.lastSyncedAt` with non-destructive Room database migration 8→9.
- Displayed Last synced in the top-right of each order card, independent of expanded details.
- Successful Gmail order checks update Last synced even if no order content changes.
- Expanded purchase date extraction: order confirmation Date header fallback when explicit order date is missing.
- Expanded notes extraction labels and preserved HTML block line breaks for email parsing.
- Continued matching existing orders via retailer/order number or known tracking, preserving manually entered details.
- Kept conservative delivered-evidence interpretation and separate order status-change timestamps.
- Android build/emulator test NOT verified in this environment.

### v1.0.4 build correction (2026-10-25)
- Added missing `androidx.compose.ui.Alignment` import in `MainActivity.kt` to address unresolved `Alignment` references reported at lines 971 and 973.
- Source-only correction; Android build and emulator execution have not been verified in this environment.

## v1.0.5 development preview (unverified)
- Added persisted 30-day archived retention preference and Storage & Maintenance dropdown. Automatic deletion intentionally not enabled. Other v1.0.5 features pending.

## v1.0.5-dev (2026-10-25, source only)
- Added Room v10 entities and migration for Gmail message IDs, field change history, deletion markers and archive timestamp.
- Added source-level Gmail field auditing, update-card history and tighter Notes filtering.
- Added archive maintenance worker, cache cleanup, storage display and confirmed full-data deletion controls.
- Replaced shipment estimated-date text editing with Material 3 date picker.
- Not build-verified or emulator-tested. See 16-item audit for incomplete work.

## v1.0.5-dev — targeted source update (2026-10-25; not build verified)
- Preserve archive and sync timestamps on manual order save; only advance order status timestamp when status changes.
- Accept monetary-only totals in extracted Gmail notes.
- Refresh persisted shipment/tracking state between sequential email updates to reduce duplicates and stale status transitions.
- Correct selected purchase-date audit source selection.
- Remaining WP-02 issues and missing Codex WP-01 test files documented in V1_0_5_IMPLEMENTATION_STATUS.md.

### v1.0.5-dev — WP-02 targeted Gmail association (source only; unverified)
- Added carrier-context numeric tracking extraction for Simpli Soda/FedEx email patterns.
- Grouped carrier and retailer correspondence through shared tracking identifiers, with merchant-aware order-ID grouping.
- Tightened direct order matching to extracted order identifiers rather than arbitrary substring matches.
- Added shipment creation from explicit retailer shipping evidence when no tracking number is exposed.
- Expanded saved-order update correspondence to carrier messages sharing retailer-confirmed tracking numbers.
- No schema/version changes. Build, automated tests, and emulator behavior not verified.

### WP-02 follow-up — Gmail import safety (source only; unverified)
- Preflight checks inside the import transaction reject ambiguous saved-order identities and tracking ownership conflicts before any order/backfill write.
- Existing-order Gmail import backfills now record field-level date, notes, and URL history in the same transaction.
- Newly inserted tracking identifiers from import receive an audit-history entry.
- Update scans reject a tracking number already associated with another saved shipment and request review.
- No schema or version change. No build, JVM tests, instrumentation, or device tests were run for this package.

## WP-02 follow-up — Gmail discovery visibility (source change; not build verified)
- Preserve the user-required Inbox-only Gmail scan (`labelIds=INBOX`); archived and other non-Inbox mail is excluded.
- Surface unmatched shipping correspondence in Needs Review for manual association rather than silently discarding it.
- Scan progress and Settings wording must describe Inbox-only scanning.
- Build, device behavior, and real Gmail association have not been verified in this environment.
