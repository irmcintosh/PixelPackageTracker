## IMPLEMENTED 2026-10-28: Inbox-derived shipment delivery estimates

Source and build verified; device/migration execution pending. The opened working source remains 1.0.5-dev (29); Room is now **13**. `TARGET_ORDER_SHIPMENT_WORKFLOW.md` remains absent.

### Source traceability
- [DeliveryEstimates.kt](app/src/main/java/com/example/pixelpackagetracker/DeliveryEstimates.kt): strict date/range candidates, email-local relative resolution, original-offset timestamp parsing and `internalDate` fallback, quote/promotion/actual-delivery exclusions, freshness/manual/legacy protection, date-only formatting and editor conversion.
- [GmailParsing.kt](app/src/main/java/com/example/pixelpackagetracker/GmailParsing.kt): retained Gmail `internalDate` and response Inbox-label check; paginated Inbox listing unchanged.
- [GmailMatching.kt](app/src/main/java/com/example/pixelpackagetracker/GmailMatching.kt): ETA correspondence enters the existing relevance/association pipeline, without a new order-identity rule.
- [MainActivity.kt](app/src/main/java/com/example/pixelpackagetracker/MainActivity.kt): shared transactional ETA application after shipment resolution; exact unique tracking/compatible carrier or verified retailer/exact order/single child; no estimate broadcast across packages; targeted historical ETA-only pass including manual associations; meaningful history/events and update signatures; range display; explicit editor dirty flag preserving unrelated edits and manual clearing.
- [PackageData.kt](app/src/main/java/com/example/pixelpackagetracker/PackageData.kt): registered additive migration 12 -> 13. Only three shipment columns are added: nullable `estimatedDeliveryEndAt`, `estimatedDeliverySourceMessageId TEXT NOT NULL DEFAULT ''`, nullable `estimatedDeliverySourceAt`. No destructive fallback/table replacement/reset in this migration.
- [CopyFields.kt](app/src/main/java/com/example/pixelpackagetracker/CopyFields.kt): consistent range/date-only clipboard output.

### Compatibility and policies
- Unknown-provenance non-null legacy ETAs are never automatically replaced. Manual selection/clearing marks priority for linked and standalone shipments; unrelated edits preserve range and source. Selecting a replacement date collapses the range deliberately.
- Source email timestamps, not estimated arrival dates or scan times, order revisions. Newer same-value mail updates freshness without history/count changes; older/equal-time conflicting evidence leaves reliable values intact. Conflicting newest candidates do not cause fallback to an older estimate.
- Dates are resolved against historical Date-header calendar/offset context. Without that context, `internalDate` can order explicit fully dated estimates but cannot authorize timezone-dependent relative/year inference. Weekdays use same-day/next occurrence. Missing-year dates more than 31 days before the email are rejected, with conservative December/January rollover.
- Existing parser version stays 3; markers are not reset/upgraded just for ETA backfill. Version-1000 manual ownership remains authoritative. Previously processed unrelated fields are not replayed by the targeted ETA pass.
- Standalone ambiguity leaves estimates unapplied; order-linked ambiguity uses existing Needs Review. Existing duplicate protections, order/shipment separation, notifications and archive/handoff rules remain.
- List requests remain `labelIds=INBOX` on every page. Downloaded responses with supplied labels must contain `INBOX`; a response without labels still relies on the restricted listing. No archived-mail/thread expansion or carrier API dependency.

### Executed validation
```powershell
.\scripts\Build-Safely.ps1 -Tasks ':app:testDebugUnitTest'
.\scripts\Build-Safely.ps1 -Tasks ':app:testDebugUnitTest', ':app:assembleDebugAndroidTest'
.\scripts\Build-Safely.ps1 -Tasks ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest'
```
The initial baseline was up-to-date (not a fresh test execution). Changed-source validation reran tests. The first new ETA suite exposed a tomorrow connector-boundary bug (186 tests, 1 failure); it was fixed. Final XML results: **188 tests, 0 failures, 0 errors, 0 skips**, including **17 new JVM ETA tests**. Final main/debug-test APK builds succeeded. Only pre-existing coroutine opt-in and deprecated Compose API warnings were observed.

### Tests added/updated and remaining verification
- [DeliveryEstimatesTest.kt](app/src/test/java/com/example/pixelpackagetracker/DeliveryEstimatesTest.kt): executed parsing, ranges, relative/weekday dates, rollover, offsets/midnight, invalid/ambiguous/quoted/promo dates, timestamp fallback, freshness, manual/legacy protection, stable formatting/editor/copy output and Inbox-label policy.
- [DeliveryEstimatesRoomTest.kt](app/src/androidTest/java/com/example/pixelpackagetracker/DeliveryEstimatesRoomTest.kt): **15 compiled, NOT RUN** tests for linked/multiple/standalone shipments, repeat scans, counts/history, unchanged status/archive/notifications, manual values/clearing/unrelated edits, version-3/1000 historical backfill, download JSON labels/timestamp and populated Room 12 -> 13 migration.
- Updated the existing 10/11 upgrade fixtures to remove the new test-only columns before simulating their older versions and register migration 12 -> 13. Updated shipment UI test callbacks for the ETA dirty flag.
- **Pending separate authorization:** Room migration/integration and UI instrumentation execution on a designated disposable AVD. Live Gmail, on-device date editing/display, upgrade/data preservation and scheduled background execution have not been verified for this feature.
- English/rule-based extraction intentionally leaves unsupported or ambiguous expressions untouched. Historical standalone manual clearing that predates provenance cannot be reconstructed; existing non-null legacy values are conservatively protected.
- No ADB/device/connected/install tasks ran; no working app/database/emulator data changed. Main/test APKs are validation outputs only; the distribution APK was not republished, and no ZIP was generated. This feature is **not release-complete**.

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

# v1.0.5 development status — latest follow-up 2026-10-27

## FIXED 2026-10-27: Gmail scan native memory
- Measured with temporary per-phase probes (`Debug.getNativeHeapAllocatedSize`, Java heap) on the emulator's real mailbox: fetch + MIME parse of 39 messages stayed at 20-40 MB native / 8-22 MB Java; the jump to 1,078 MB native (Java 22 MB) happened inside `updateOrdersFromEmails` before the first order was processed, i.e. while building email evidence. An on-device probe showed one `buildEvidence` of a 123K-character synthetic email crashing the process, while whole-text `containsMatchIn` cost ~0.25 MB per call.
- Root cause: Kotlin `Regex.findAll`/`replace { }` create a Matcher per match, and each Android Matcher holds a native copy of its entire input until GC. `identifierTokens` kept one per word.
- Change: `Regex.scan` / `Regex.replaceEach` reuse a single Matcher (GmailParsing.kt); used in GmailParsing.kt, GmailMatching.kt and AssociationForm.kt. Probes were removed.
- Results: JVM 158 passed; emulator 145 passed (new `RegexNativeMemoryTest`: 10 analyses of a 120K-character email peak +5 MB native, and the helpers match Kotlin's results); real mailbox Scan / Import x3 peak 51-60 MB native, Orders Update All 46 MB, Shipments Update All 49 MB, same process throughout; the first scan applied one real new email (Overstock shipment In Transit -> Out for Delivery), later scans changed nothing.
- Remaining: whole-body regex calls still create one native input copy per call (about 0.25 MB per 120K body), released by normal GC; the background worker uses the same code but a scheduled run after the fix was not observed; the physical Pixel is not tested.

## IMPLEMENTED 2026-10-27: copyable details, email-based order/shipped dates, Scan / Import in Settings
Source: CopyDetails.kt (new), MainActivity.kt (OrderCard, ShipmentDetailCard, Settings, toolbar, Orders tab, `extractOrderDetails`, `applyAssignedEmails`), GmailMatching.kt (`EmailEvidence.dispatch`), GmailParsing.kt (carrier-acceptance wording).
- Verified (JVM 158, emulator 143 via `install -r` + `am instrument`, app not uninstalled): exact clipboard round-trip of identifiers with leading zeros/special characters and no record changes (`ClipboardCopyTest`, plus UI copy then paste into a search field on the emulator for an order number, an archived order's tracking number and a full shipment block); confirmation date as order date, approximate earliest-email date replaced by a later-found confirmation, no overwrite by later status mail or repeated scans, manual and scan-date protection; label/tracking-only mail and later movement never set or change the shipped date, manual shipped date kept, standalone shipments the same (`EmailDatesRoomTest`, `EmailDatesTest`); Orders tab and toolbar no longer show Scan; Settings shows Connect to Email then Scan / Import; Scan / Import opens the existing panel and Back returns to Settings.
- Live Gmail on the emulator: Scan Gmail from Settings completed once (39 messages, 0 created/updated, 1 need review) but the app was killed by Android's low-memory killer in 4 other attempts. Native heap peaks of 1-2.4 GB occur while fetching/parsing and importing the mailbox (the fetch alone peaks near 1 GB in Update All too). The fetch, parse and import code predates this change; I cut redundant work (dispatch evidence computed once, grouping memoized) but did not rewrite it. Treat the memory use as an open issue before relying on Scan / Import on a small-RAM device.
- Not verified: a real mailbox confirmation/dispatch email through the UI, physical Pixel clipboard behavior, reconnect handling from the Settings entry (the panel's own Connect button is unchanged). View Order and View Tracking were tapped on the emulator and open Chrome.
- TARGET_ORDER_SHIPMENT_WORKFLOW.md is not in the workspace; the request text was the specification.

## IMPLEMENTED 2026-10-26: Shipments tab Update and Update All
Source: MainActivity.kt (`TrackerVm.updateShipmentsFromEmails`, `InboxOrdersPanel` modes `shipment`/`shipments`, `ShipmentDetailCard`, Shipments tab), ScanUpdateResults.kt (`ShipmentUpdateSummary`), Preferences.kt (`shipmentChecks`, `markShipmentsChecked`).
- Verified (JVM 148, emulator 133 via `install -r` + `am instrument`, app not uninstalled): individual update advances a standalone shipment with one event per message and keeps manual events; repeat and mail for another number or carrier change nothing; a shipment without a saved tracking number is skipped and nothing is created; Update All handles a standalone and an archived-order shipment from one message list without changing the archived order's status or archive flag, adds no history or shipments on repeat; one notice per real status change and none on repeat or when the switch is off; a check does not re-emit settings and leaves the WorkManager job id, interval and next run time unchanged. On the emulator with the real mailbox: Update All (4 checked, 2 skipped, 0 changes) and an individual Update ran from the UI, last-checked times appeared, and the job delay stayed ~7h for the 8 h interval.
- Not verified: a real mailbox status change through the new buttons (the emulator mailbox had nothing new), a physical Pixel, the reconnect dialog from these entry points, and order-linked update for an active order that would move to Shipped (rules are the existing ones; no test of that specific path from the Shipments tab).
- Interpretation to confirm: order-linked shipments use the order update path, so a genuinely moving shipment can advance its active order exactly as an Orders Update would. A strictly shipment-only update would leave that order stuck, because order status only advances when the scan itself advances the shipment.

## IMPLEMENTED 2026-10-26: four-stage Orders progress and order-status notifications
Source: OrderProgress.kt (`purchaseProgress`, `PREPARING_FOR_SHIPMENT`), ScanUpdateResults.kt (`derivedOrderStatus`), GmailParsing.kt (`statusForEmail`, `confirmsDispatch`), GmailMatching.kt, MainActivity.kt (`derivedStatusFor`, scan call sites, Settings), BackgroundSync.kt (`statusNotices`, `withStatusNotifications`), Preferences.kt (`notifyOrder`).
- Verified (JVM 148, emulator 127 via `install -r` + `am instrument`, app not uninstalled): four stages and Cancelled; tracking number alone gives Preparing for Shipment, never Shipped, never archived; dispatch evidence gives Shipped and archives with the shipment kept in Shipments; a Shipped order without shipment information stays active until a shipment is saved; foreground (`autoImportFromMessages`) and background (`syncOrdersFromMessages`) paths each produce the order notices for Processing, Preparing for Shipment, Shipped and Cancelled, a repeated scan produces none, unrelated mail produces none, switches suppress them, shipment notices still fire, and a notice is really posted to the system on channel `shipment_updates` (permission granted on the emulator).
- Not verified: a notification from a real mailbox change on the emulator or Pixel, the `InboxSyncWorker` class itself posting (it calls the same function; `work-testing` is not available offline), Needs Review confirmations (user actions) do not notify, and the permission-denied path was reasoned from code only.
- The stored `notifications` flag still has no UI and defaults off; it is not used as a gate. Android permission plus the per-status switches decide. Existing Shipped orders that predate handoff stay active.

## AUDITED 2026-10-26: Tracking Interval and background sync (BackgroundSync.kt, MainActivity.kt)
- Implemented and unchanged: `BackgroundSync.schedule()` enqueues unique periodic `InboxSyncWorker` (NetworkType.CONNECTED, `ExistingPeriodicWorkPolicy.UPDATE`) from `MainActivity` whenever settings emit; the worker runs `autoImportFromMessages(reanalyze = false)` (import of new orders/standalone shipments, update of all saved orders including archived ones, update of saved standalone shipments); Gmail is the only integration (no carrier APIs).
- Verified by test: interval change replaces the single periodic job with the new period (emulator `TrackingIntervalSchedulingTest`; invalid value falls back to 8 h; the saved interval is restored); `jobscheduler` shows the InboxSyncWorker job with a network constraint on the emulator; import/update/no-repeat behavior (`Wp02AssociationRegressionTest.backgroundSyncAppliesNewMailThroughTheSameAssociationPath`, archived-order association tests); notification category rules (`BackgroundSyncTest`).
- Broken and fixed: notifications never fired (master `notifications` flag had no UI and defaulted off; POST_NOTIFICATIONS never requested); retry handling (transient authorization failure was reported as "reconnect", unbounded retries, cancellation turned into retry); the `notifyEvery` switch was ignored.
- Not verified on emulator/device: that `authorize()` returns a token while the app is closed (needs a real signed-in account), execution of a scheduled run after process death or reboot, retry/backoff and give-up behavior (`work-testing` is not in the offline Gradle cache), an actual notification after granting permission, Doze/battery-optimization effects. WorkManager timing is best-effort. Background runs do not reanalyze older parser markers, and archive-after-delivery still runs only while the app is open.
- Orders tab now shows purchase status only for active orders; the Shipments tab and archived orders show delivery statuses. Handoff rules (Shipped + saved shipment with tracking/carrier archives; otherwise "Shipment information needed") were already implemented and covered by `OrderShipmentWorkflowRoomTest`; the card change is covered only by build and the existing suite, not by a UI test or screenshot.
- Results: JVM 148 passed; emulator 118 passed (`install -r` + `am instrument`, app not uninstalled).
- Emulator checks (screens and `dumpsys`): active Orders cards show purchase status only (no delivery progress, no "Update status"); an archived Tempur-Pedic order's In Transit shipment is listed under Shipments → Active; Settings shows the saved interval and, after changing it, the job's remaining delay changed (8 h → 3 h → 8 h) with one `InboxSyncWorker` job, also after an app restart; "Allow notifications" opens the Android prompt, grants POST_NOTIFICATIONS, and the warning and button disappear. The button is a plain button, not a switch.
- Background run with the app process killed: job fired, new process, `Worker result SUCCESS`, every order's `lastSyncedAt` updated, one order advanced from real mail. Procedure for the physical Pixel: BACKGROUND_SCAN_TEST_PROCEDURE.md. Still physical-device only: Gmail authorization in the background on the Pixel, a real notification, Doze/standby timing, reboot persistence.
- Delivered auto-archive (`archiveDue`, Settings → Archiving) runs only from a Compose `LaunchedEffect` while the app UI is open (keyed on the setting and status timestamps; no timer, no worker). The Settings text says so ("Background scheduling is not active"), but no design note records it as a decision; left unchanged. A background scan that delivers a shipment is therefore archived on the next app open.
- Existing Shipped orders that already had a tracking number or carrier before the handoff feature (eBay, namokiMODS, Simpli Soda) are still active on the emulator because handoff runs only on a status or shipment event.

## IMPLEMENTED 2026-10-26: order → shipment workflow (handoff, Shipments tab, automatic import, Room 12)
Source: MainActivity.kt (`handoffIfReady`, `settleOrder`, `autoImportFromMessages`, `importableGroups`, `applyAssignedEmails`, Shipments tab, `ShipmentDetailCard`), PackageData.kt (`MIGRATION_11_12`, retention queries), GmailParsing.kt (`orderStatusNotice`), OrderProgress.kt (`hasShipmentInformation`, `needsShipmentInformation`, `selectableOrderStatuses`), ScanUpdateResults.kt (`ScanRunSummary`, `derivedOrderStatus`), BackgroundSync.kt, AssociationForm.kt, ChangeHistory.kt.
- Handoff rule and its definition of "shipment information" (tracking number or carrier; an empty shipment does not count and is filled in by the manual entry). Triggered only by order-status changes, shipment creation or association; a manual Restore is not undone.
- Retention: FK `shipments.orderId` is ON DELETE CASCADE, so deleting an expired archived order would delete its shipments, tracking numbers and events. `expiredArchivedKeys`/`deleteExpiredArchives` now skip any order with a shipment that is not archived or not past the cutoff (same predicate as standalone expiry). Manual "Delete order" (confirmed in the dialog) still deletes the order's shipments.
- Migration 11 → 12 is one `ALTER TABLE shipments ADD COLUMN shippedAt INTEGER` (nullable, no data change); chain 10 → 11 → 12 tested; applied to the emulator's real database on first launch (7 orders / 3 shipments kept).
- Existing shipped orders are not archived retroactively; handoff runs on the next relevant event.
- Unchanged by design: a second tracking number without separate-package or handoff wording on an order that already has a number still goes to Needs Review (it may be a carrier handoff of the same parcel).
- Verification: debug build and androidTest APK built; JVM 145 passed; emulator 117 passed via `install -r` + `am instrument` (firstInstallTime unchanged). Tests changed because they encoded the old contract: order status roll-up to Delivered/Partially Shipped (3), archived orders hidden from association ranking, history assertions (approximate date and handoff rows), `syncOrdersFromMessages` now returns `ScanRunSummary`, 10 → 11 migration test also applies 11 → 12.
- Emulator observation: after install, the background worker ran against the signed-in mailbox with the new code (not a controlled test): it created one Amazon order with an Out for Delivery shipment (no carrier/tracking, so "Shipment information needed" shows) and added a UPS shipment to the Tempur-Pedic order, which then archived; other saved orders were unchanged. Shipments tab, the prompt and the approximate-date label were seen on screen. NOT verified: the manual Inbox scan button, the Add-shipment dialog from the prompt, date pickers, Archive tab, light theme.


## IMPLEMENTED 2026-10-26: evidence-based Gmail discovery, standalone shipments, carrier and title (bug fix)
Source files: GmailMatching.kt (`orderEligibility`, `standaloneShipmentEvidence`, `isRelevantEmail`), GmailParsing.kt (`detectCarrier(strict)`, `carrierForTracklessShipment`, `subjectItemName`, `orderTitleFromEmails`), MainActivity.kt (grouping, import, standalone import/update, carrier edit, Scan UI, Needs Review form), AssociationForm.kt (title prefill), PackageData.kt (Room 11 migration).
- One shared decision, `orderEligibility`, is used by scan grouping, `importInTransaction` and the Import UI (`ScanGroup.establishesOrder` replaces `confirmedOrder`, plus `basis`). A confirmation with one order number qualifies; otherwise a verified retailer sender (not shared mail, payment processor or carrier), exactly one labeled order number, no marketing/return/support subject and at least two of: order status wording, item name, delivery information, retailer order link, verified tracking.
- Standalone shipments: a plausible tracking number, strongly evidenced carrier and positive status create (Inbox scan Import) or update (Import and Update paths) a shipment with `orderId = null`, deduplicated by tracking number; each Gmail message is recorded once as a tracking event (`sourceMessageId`); deleting such a shipment excludes its messages from later scans. The Update path never creates one.
- Carrier: new `shipments.carrier` column. Gmail-created tracking-less shipments store the carrier only when the email states it or names a single carrier; a carrier is never overwritten; cards read the tracking-number carrier first, then the shipment carrier; Edit shipment can save a carrier alone.
- Title: the Needs Review dialog keeps one form per email (typed title survives failed attempts), prefills the title from the subject ("OUT FOR DELIVERY: Google Pixel C 64GB..." gives "Google Pixel C 64GB"), and newly imported orders are titled from the email. Gmail updates never rename an order (test added).
- Room 10 to 11 (additive): `shipments.carrier`, `shipment_tracking_events.sourceMessageId`, carrier backfilled from tracking rows and from legacy "Retailer · Carrier" titles. Verified on the real emulator database: 7 orders, 3 shipments and 3 tracking numbers kept; the eBay shipment now has carrier USPS.
- Existing tests changed because they asserted the old contract: `shippingMessageWithoutOrderConfirmationCannotCreateANewOrder` (now: too little evidence cannot create an order, a genuine notice can), the eBay tracking-less test (asserts carrier, not title), the Amazon shipped-notice grouping test.
- Results: debug build and androidTest APK built; JVM 140 passed (12 new in GmailDiscoveryTest); emulator 99 passed via `am instrument` (11 new in GmailDiscoveryRoomTest, including the migration test), `install -r` over the existing app, `firstInstallTime` unchanged, only the test package uninstalled.
- UI checked on the emulator: after launch the migrated Orders tab shows the eBay card `4 of 5 · Out for Delivery` with `Shipment: USPS · Out for Delivery`. NOT verified: a live Gmail scan, the Import buttons and Needs Review dialog on screen, standalone shipment cards (no Gmail authorization or matching emails in this environment).
- Known limitations: an Update scan does not reprocess messages already marked processed, so older orders keep their data until a new email arrives; standalone shipments need a tracking number; the two-signal threshold is conservative, so a bare "Your order has shipped" email goes to Needs Review.

## Evidence-based Gmail discovery: rules and design (written before implementation; implemented as above)
Goal: automatic discovery with minimal manual work. Scan → identify orders (verified retailer + exact order number) → attach shipping mail → tracking numbers are secondary identifiers. Confirmations are preferred, not required. Unattachable genuine shipments become standalone shipments. Needs Review is only for ambiguity or insufficient evidence.

### Source findings (inspected 2026-10-26)
- Classifier equates "no confirmation" with "no order": `groupedScanResults` (MainActivity.kt) builds order groups only from `isOrderConfirmation` emails and sends every other order-number email to review ("no matching confirmation or saved retailer/order identity"); `importInTransaction` throws "a new order cannot be created without a matching order confirmation"; the Import button needs `ScanGroup.confirmedOrder`; `isOrderConfirmation` returns false for any shipping-kind email unless the subject reads as a confirmation; `updateOrdersFromEmails` never creates orders.
- Standalone shipments are only created by hand (`createStandalone`); Gmail scanning has no path to create one. A carrier email with no known order/tracking, or a retailer shipping email with an order number but no confirmation, goes to review.
- Database blockers for standalone Gmail shipments: `processed_gmail_messages.orderId` is a non-null foreign key to `orders`, so a message cannot be recorded for a shipment without an order; history (`order_change_history`) is keyed by order; `shipment_tracking_events` has no Gmail message ID; carrier lives only on `shipment_tracking_numbers` (needs a number).
- Title not preserved: the saved eBay order has `name = 'eBay'` and shipment title `eBay · USPS` (read from the emulator database). `createOrderFromEmail` stores `title.trim().ifBlank { retailer }`, so a blank title reached it. The Title field is empty by default (`prefillAssociation` only fills it from `Item:`/`Product:` lines, which eBay mail does not have) and the form is rebuilt each time the dialog is reopened, so text typed in the failed first attempt was lost before the successful second attempt. Whether it was retyped cannot be proven from the database.
- Carrier not populated: the code did detect USPS, but with no tracking number there is no row to hold it, so it was only put in the shipment title. The card reads the carrier from tracking-number rows ("Carrier not set"), and Edit shipment requires a number and a carrier together, so a carrier alone cannot be saved.
- eBay evidence (from the scan card, not the raw email): sender `ebay@ebay.com` (verified retailer identity), one labeled order number `18-15293-46910`, an item name in the subject, an estimated delivery window, an order-status subject ("Order update"), a second "OUT FOR DELIVERY" email for the same number, USPS named in the text, no tracking number. That is enough to establish an order under rule A; it is not enough for a standalone shipment because it has no tracking number.

### Proposed evidence rules
A. Order established (any retailer email, confirmation or not). All gates must hold:
 1. Verified retailer identity: sender domain brand is the retailer, not a shared mail domain, payment processor or carrier; display name does not contradict it.
 2. Exactly one labeled order number (existing extractor); not a one-character variant of a saved order; not a tracking number; not previously deleted (tombstone).
 3. At least TWO corroborating order-specific signals: (a) positive order/shipment status wording (order update, shipped, out for delivery, delivered; not conditional/forecast); (b) a specific item/product name; (c) order fields such as estimated delivery window, quantity, price/total or order date; (d) a retailer-domain order link containing the exact number; (e) a verified-carrier tracking number.
 4. Not a return, refund, cancellation, dispute, survey, marketing or payment-only message.
 A retailer sender plus one number alone never creates an order (it stays in Needs Review). A confirmation still establishes an order on its own and is preferred when present.
B. Standalone shipment (fallback; only when no order is established or matched): a plausible tracking number, a carrier from a strong source (tracking-link host, carrier sender, carrier named beside the number, number format) and positive shipping status wording. An order number seen in the email is kept as a reference, never turned into an order. Without a tracking number there is no secondary identifier, so it goes to Needs Review.
C. Needs Review only for: several candidate orders or shipments, conflicting identifiers, several tracking numbers without package/handoff wording, unverified retailer (shared-mail or processor sender with no merchant), or evidence below rules A and B. Each review card states which rule failed.
D. Shipping mail attaches by exact order number first, then by tracking number. Later verified evidence (a confirmation, a tracking-number match, or exact retailer + order reference) links a standalone shipment to its order; users can still link manually.

### Targeted fixes
1. GmailMatching.kt: new pure `orderEvidence(ev, aliases)` (result + reasons) and `standaloneShipmentEvidence(ev)`; `isOrderConfirmation` unchanged.
2. MainActivity.kt: `groupedScanResults` and `importInTransaction` accept an established order (replace `confirmedOrder` with an evidence basis shown on the card: "Order confirmation" or "Retailer shipping notice"); add standalone-shipment scan groups with an Import action, created through the same transaction/mutex as orders; `updateOrdersFromEmails` stays non-creating.
3. Carrier and title: store the detected carrier on the shipment; card and diagnostics read the shipment carrier first, then tracking rows; Edit shipment accepts a carrier without a number; the association form prefills Title from the subject (status prefix removed) and keeps typed text across failed attempts; Room test that a typed title is saved.
4. Linking: `importInTransaction` also links a standalone shipment by exact retailer + order reference and copies its message markers to the order.
5. Deleting a Gmail-created standalone shipment adds its message IDs to the dismissed set so it is not recreated.

### Database requirements (Room 10 → 11, additive, no destructive fallback)
- `shipments.carrier TEXT NOT NULL DEFAULT ''` (backfilled from the first tracking-number row), plus optional `retailer` and `orderReference` (`TEXT NOT NULL DEFAULT ''`) for later automatic linking.
- New table `shipment_gmail_messages(id, shipmentId → shipments ON DELETE CASCADE, messageId, processedAt, extractionVersion, UNIQUE(messageId, shipmentId))`, because the existing marker table requires an order.
- `shipment_tracking_events.sourceMessageId TEXT NOT NULL DEFAULT ''`; standalone status history is written as events (status, source `gmail-inbox`, message ID, email date). Order history stays in `order_change_history`.
- No existing rows change except the carrier backfill. There is no migration test today; add one that builds a version-10 database with orders, shipments, markers and history, migrates it, and checks counts, relationships and the new columns.

### Tests required
- JVM: evidence rules (eBay-style order update with and without item/ETA, retailer + one number only, support/marketing/refund wording, shared-mail and processor senders, variant numbers, deleted tombstone) and standalone evidence (tracking + carrier + status; missing carrier; forecast wording).
- Room: eBay order established and imported without a confirmation with carrier USPS stored and title kept; standalone shipment created from a carrier email, deduplicated across repeated scans, linked later by a confirmation, history and message ID kept; Needs Review reasons; deleting a standalone shipment does not recreate it; version 10 → 11 migration; Inbox-only query, manual status protection and progress display unchanged.

### Decisions requested
1. Keep a one-tap "Import" step after a scan, or import automatically?
2. Standalone shipments require a tracking number: acceptable?
3. Two corroborating signals for an order without a confirmation: acceptable threshold?
4. Add `retailer` and `orderReference` columns for automatic later linking, or link by tracking number and manual link only?

## Creating an order from an Out for Delivery / Delivered email without tracking
- Reported: "Create order #18-15293-46910 and associate" for the eBay "OUT FOR DELIVERY" email failed with "no single shipment on this order could be identified", so the order was never created.
- Cause: `applyAssignedEmails` created a tracking-less shipment only for In Transit notices on an order with no shipments; Out for Delivery and Delivered notices were flagged, and `createOrderFromEmail` rolls back on any flag.
- Change: any positive shipping notice (In Transit, Out for Delivery, Delivered) creates that first shipment (no tracking number invented; tracking can be added with Edit shipment). Exception notices without a shipment still go to review. This also applies to scans of saved orders that have no shipment yet.
- Tests: new Room test creates the eBay order from an Out for Delivery email (one shipment, Out for Delivery, `4 of 5 · Out for Delivery`, email marked). `anEmailThatCannotBeAppliedLeavesNoOrderNoMarkerAndIsNotUsed` now uses an exception email, because a Delivered email is now applicable. Build passed; JVM 128 and emulator 89 passed via `am instrument`; app data kept. Not re-tested with real Gmail in the dialog.

## Inbox scan results list only actionable groups
- Reported: an eBay shipping update (order 18-15293-46910, no confirmation, no saved order) appeared as a scan card with no Import or Apply action.
- Change (MainActivity.kt Inbox screen): scan cards are shown only for importable groups, saved-order (`room:`) groups, and groups with an import result. Every other group (review groups, order-number-variant conflicts) is listed only under Needs review, which offers Associate with an order (including creating the order) or Remove from review. Grouping, matching and import rules are unchanged.
- Validation: debug build passed; 128 JVM tests passed; APK installed with `install -r` (data kept). The scan screen was not re-checked with real Gmail on the emulator.

## Order card progress synced with shipment status (audit item 17) — implemented
Root cause: `progressStage()` read persisted shipment statuses but mapped In Transit and Out for Delivery to a fixed `In transit` / `Shipment in transit` label; Customs, Exception and `Partially Shipped` were unmapped; the Gmail rank map omitted Customs and let any Exception email (even stale) override movement.

Source changes (no Room schema change, no new status field):
- New `OrderProgress.kt`: `purchaseProgress()` (five stages; stage 3 once any shipment shipped, stage 4 only when every shipment is In Transit/Customs/Out for Delivery/Exception/Delivered, stage 5 only when all Delivered; exact label for a single or uniform status, otherwise `Exception`, `Partially Delivered`, `Partially Shipped` or `Multiple statuses` with per-status counts; manual-status note), `standaloneShipmentProgress()` (0–3 shipment-only stages), `canonicalShipmentStatus()`, and `gmailShipmentStatusApplies()`.
- Gmail transitions: Delivered is terminal; forward moves apply; entering or leaving Exception, and Customs → In Transit, require an email dated after the current status (email date for statuses applied in the same pass, otherwise `statusUpdatedAt`); Label Created never overrides Customs or Exception.
- MainActivity.kt: `ProgressBar` replaces `OrderProgress`; order card shows `Shipment[ n]: <carrier> · <status>` per shipment and `Order marked <status> manually` when the latest order-status change is manual and differs from shipment progress; standalone cards show `Shipment progress`. Manual status protection (`mayDeriveOrderStatus`), Inbox-only query and derived order-status rules unchanged.
- Existing test fixture `deliveryExceptionUpdatesTheMatchedShipmentAndIsRecordedAsProcessed` now dates the In Transit shipment before the exception email (it previously used the current time, which the new stale-mail rule correctly rejects).

Validation (2026-10-26):
- `gradle --offline assembleDebug testDebugUnitTest assembleDebugAndroidTest` (Gradle 8.11.1, JBR 21.0.11): BUILD SUCCESSFUL.
- JVM: 128 passed, 0 failed (new `OrderProgressTest` 18).
- Emulator (API 37 AVD, `install -r` + `am instrument -w`, test package only uninstalled): 88 passed, 0 failed (6 new Room tests: Out for Delivery display/consistency, repeated/older mail non-regression, stale vs newer exception, Customs, multi-shipment Delivered, manual Delivered note). App `firstInstallTime` unchanged (2026-10-25 17:36:26); saved orders still present.
- The first emulator start hung (adb offline); with approval it was force-stopped and cold-booted without wiping data.
- UI: Orders screen opened on the emulator with existing saved data; observed `1 of 5 · Ordered` (DSWATCH, no shipments) and `4 of 5 · In Transit` with `Shipment: USPS · In Transit` (namokiMODS). Out for Delivery, Exception, multi-shipment, manual-note and standalone-shipment cards were NOT visually checked (no such saved records; data was not modified to create them). Real Gmail not tested.
- Limitation: a status applied by an earlier scan is dated by that scan time, so a genuinely newer exception email whose Date header precedes that scan time is ignored.

## Persistent removal from Needs Review (latest)
- `SettingsRepository` stores dismissed Gmail IDs in a DataStore string set (no Room schema change and no fake order foreign key). The review card action removes the item and excludes it from later Inbox previews, Update processing and import. Dismissal is refused if the email already has an association marker.
- Room-backed test verifies persisted dismissal, no order marker, and exclusion from scan/update/import; settings are restored after tests. Validation: 110 JVM tests and 82 emulator tests via `am instrument`; debug build/launch passed earlier. The app was not uninstalled.

## Review hold-out and confirmation classification (latest)
- `classifyEmail` (GmailParsing.kt) now uses `isConfirmationSubject || hasConfirmationHeader` (new: `^ordered\b` subject or `auto-confirm`/`order-confirm*`/`confirmation` sender local part), so a confirmation whose body mentions delivery stays CONFIRMATION. `isOrderConfirmation` shares the same helper.
- `groupedScanResults` removes emails with several tracking numbers lacking separate-package/handoff wording (and not already saved) from their order group into a `review:` group before tracking-owner linking; empty order groups are dropped. Review groups are not importable.
- Failure messages for create/associate rollbacks state the email was not used and stays in Needs Review. Tests: classification of the real Amazon confirmation body, create-from-review with a shipping-mentioning body, failed attempt leaves no order/marker, associated email is not reusable, hold-out grouping.
- Validation: 110 JVM and 81 emulator tests passed via `am instrument`; install time unchanged. Real Gmail not re-tested.

## Association screen: retailer → order number
- `AssociationForm.kt`: `OrderChoice` (Saved / FromEmail / Manual) and `orderChoices(prefill, retailer, ranked)` list only the chosen retailer's saved orders, then unsaved numbers from the email, then manual entry. `AssociationFormState` gained `manualNumber`, `reselect`, `editRetailer`, `enterNumberManually`; `canCreate` requires no selected saved order. `initialAssociationForm` starts on the strongest verified/likely saved order for the email's retailer (any retailer when the email names none, e.g. a processor email). Dialog in MainActivity.kt no longer has the Order / New order control; the dialog uses retailer dropdown, order-number dropdown, Review association or Title + Create.
- Validation: 109 JVM (24 association form) and 78 emulator tests passed via `am instrument` (app not uninstalled; install time unchanged across an emulator restart, so a restart does not wipe app data). Dialog rendering and real Gmail not tested.

## Amazon-style confirmation detection
- `isOrderConfirmation` (GmailMatching.kt): `orderedSubject` is now `^ordered\b` (was `ordered:`), and a sender local part matching `auto-confirm` / `order-confirm*` / `confirmation` counts, still requiring an extracted order number and excluding shipping kinds. Regression tests use the real screenshot values (`Ordered 1 item: Tops`, order 111-9392767-7525812) in JVM grouping tests and a Room import test.
- Validation: 102 JVM and 78 emulator tests passed via `am instrument` (app not uninstalled). The emulator was restarted before the run and the app installed fresh, so no earlier saved orders exist there. Real Gmail not re-tested.

## Order dropdown default and test-run safety
- `AssociationForm.kt`: `strongestMatchId` (top verified/likely order, else none), `topMatchIsTied`, `sameRetailerName` (alias-aware plus a dropped .com/.net/.org/.io suffix), `AssociationFormState.selectedOrderId`. Dialog: one Order dropdown (ranked orders + New order), evidence text, tie warning, Review association button; create fields only for a new order.
- Data loss found and corrected: Gradle's `connectedDebugAndroidTest` uninstalls the app, wiping the emulator database; earlier "data preserved" statements in this file and the changelog are superseded by this. The emulator's saved orders were erased by those runs and are not recoverable here. Procedure now: `install -r` app + test APKs, `am instrument -w`, uninstall only the test package; verified by unchanged `firstInstallTime` and a surviving marker file. `Wp02AssociationRegressionTest` restores the real alias settings it clears.
- Validation: 100 JVM (17 association form) and 77 emulator tests passed. Dialog rendering and real Gmail not tested.

## WP-02 Needs Review association screen
- New `AssociationForm.kt`: `prefillAssociation` (sender/single item/single order number; processor-aware via `merchantFromProcessorEmail`), `rankSavedOrders` (Verified/Likely/Same retailer), `retailerOptions`, `existingOrderFor`, and `AssociationFormState` (created once per email via `remember(item)`; manual edits are never re-prefilled; picking a retailer sets only the retailer; picking a title fills the retailer only if empty).
- Dialog (MainActivity.kt `NeedsReviewSection`) renders that state: placeholders for empty fields, hints for the retailer source, Pick retailer / Pick title / Pick order menus, duplicate guard with Associate-instead, ranked existing-order list. `Field` gained an optional placeholder. Retailer-specific code was not added; no Room schema change; Inbox-only query unchanged.
- Limitation: when a processor email names no merchant the retailer stays empty; a title is left empty rather than invented (the order is then named after the retailer by the existing create path).
- Validation: 96 JVM (25 parser, 50 matching, 13 association form, 8 scan-result) and 77 emulator Room tests passed; new `Wp02AssociationFormFlowTest` covers associate-existing, create-new from prefill, manual retailer, duplicate block. Debug build, `install -r` and launch passed. Compose rendering and real Gmail were not tested (no Compose UI test dependency).

## WP-02 order-centered grouping (overrides conflicting notes below)

### Source changes
- `groupedScanResults()` now calls `assignEmails()` (manual markers > persisted markers > exact order+retailer > tracking ownership > claims > review suggestions) and receives processed markers, so Inbox preview, Update, background sync and import agree. Saved orders become `room:<id>` groups; confirmations seed new groups; follow-ups join by exact order number + verified retailer; orderless carrier mail joins only through a tracking number claimed by an order-verified email or a saved shipment.
- `isOrderConfirmation()` / `isRelevantEmail()` (GmailMatching.kt) replace the narrow subject-only check in grouping, import and update. Added wording: "Ordered:", "Thank you for your order", "We received your order", "Receipt/confirmation for order", order details beside one order number. Shipping notices are never confirmations.
- Multi-tracking: `hasSeparatePackageEvidence` -> one child shipment per number; `hasCarrierHandoffEvidence` -> primary + handoff on one shipment; no evidence -> review, not applied. Reverses the earlier "second number = handoff" assumption.
- Strict tracking equality after USPS routing-prefix strip; fragment/suffix matching removed. Original text preserved in history (`as written`).
- Reverses same-thread order-number-variant joining: variants are flagged conflicts with the other order as a review candidate.
- Import commits the verified order and safe emails; ambiguous emails are reported ("N emails need review") instead of rolling the order back. New orders start as `Order Placed`; shipment evidence moves status later.
- UI: Apply updates button and saved shipments/tracking under existing orders; candidate evidence (`candidateEvidenceText`) on Needs Review cards; scan counts and ignored-with-order-number list; per-message fetch failures are skipped and counted. No Room schema change (still 10); Inbox-only query unchanged.

### Validation
- JVM: 82 passed (25 parser, 49 matching, 8 scan-result). Room-backed instrumentation on API 37 emulator: 72 passed. Debug build passed. `install -r` over the existing app (data preserved) and launch passed with no crash.
- Tests updated to the new contract (thread variants, multi-tracking, fuzzy tracking) and added for existing-order grouping, saved-shipment ownership, one-order-per-group, separate-package and handoff DB relationships, unfamiliar confirmation wording, candidate evidence.
- UI interactions tested manually: app launch only. Real Gmail results: not verified.

# v1.0.5 development status — earlier follow-up 2026-10-25

## WP-02 source-association and review repair

### Order-number-first workflow (overrides earlier WP-02 matching notes)
- Inbox groups are seeded only by an actual confirmation with one extracted order number. The business key is verified retailer identity + exact normalized external order number; Room IDs remain internal keys.
- Exact-order follow-up messages are considered individually and joined only if they provide supported notes, purchase date, item, status, order URL, or tracking information. A customer-service message that only repeats an order number is ignored and receives no processed marker.
- Alternate sender identities join an existing confirmation group only when retailer evidence is strong. A single email with multiple new tracking numbers is isolated in Needs Review, leaving the confirmation importable and avoiding rollback of the order.
- Tracking association is secondary. A message without an order number can update an order only through an exact tracking number already claimed by an order or persisted on its shipment. Otherwise relevant shipping/carrier mail remains in Needs Review; retailer name, sender, and Gmail thread alone never auto-associate it.
- New order import refuses a group without a confirmation, including when `importScanGroup()` is called directly. Existing orders can still be updated from verified exact-order follow-ups.
- Background and Update All continue to call the same per-message order/tracking matcher after the Inbox-only fetch. No Gmail search expands beyond `labelIds=INBOX`.

### Confirmed root causes
- `updateOrdersFromEmails()` included duplicate candidates from all Room orders, regardless of the requested order, and exposed merge actions in the update workflow.
- Inbox preview resolved normalized external order numbers, while transactional import used literal case-insensitive `findDuplicates()`. Formatting variants could therefore preview as the same order but create a second record during import.
- Assignment considered manual markers authoritative but did not otherwise prioritize saved message-to-order associations before newly inferred identity/context.
- Correspondence grouping had retailer-specific sender/title branches, which made example retailers production matching special cases rather than fixtures.

### Targeted changes
- Update-specific review summaries contain only correspondence issues produced for their requested target(s); global duplicate warnings are absent from Update and Update All. Duplicate review and explicit merge remain under Inbox → Needs Review and are computed from Room orders, so that review works without a new Gmail scan.
- `exactSavedOrderCandidates()` now supplies the same normalized external order identity and strongest retailer-evidence decision to Inbox grouping and transactional import. Import checks saved manual/message associations first, then existing tracking/shipment ownership and exact identity; split ownership, conflicting evidence, and duplicate candidates roll back for review rather than being merged.
- `assignEmails()` now honors persisted message associations before tracking, order-number, thread, and contextual inference. Multiple saved owners remain ambiguous. Existing manual associations remain authoritative.
- Known retailer-specific grouping branches were removed; sender-domain brand and carrier-sender helpers are used instead.
- Inbox grouping now canonicalizes verified aliases for exact order-number keys and joins across all extracted tracking numbers, including secondary numbers in handoff/multi-number messages. This addresses the observed separate DSWATCH and DS Designer cards without joining unrelated retailers by name alone.
- Tracking candidates now require at least six digits in mixed alphanumeric IDs, or ten digits for numeric IDs, including carrier URL parameters. The observed `20Documents` token no longer qualifies or creates an unmatched shipment review group.
- Same-retailer order IDs differing by one leading letter can use a shared Gmail thread to join when exactly one saved order matches one spelling. For a new order, a unique ID in the thread’s confirmation is canonical. The other message follows same-thread association. Without a unique saved-order or confirmation anchor, the IDs remain separate and flagged for review; no prefix stripping or broad fuzzy matching is used.
- Scan diagnostics expose candidate saved order IDs, accepted evidence, rejected candidate reasons, changes, and message-level commit state. Review views show extracted identifiers and reason without rendering full email bodies.

### New regressions
- Room/ViewModel targeted update with PayPal fixture `40FQ6HG` and unrelated DSWATCH / DS Designer duplicate; Update All has no merge action, while global Inbox duplicate review still finds the pair.
- ViewModel/Room parity between saved-order update and Inbox import for differently formatted external order IDs.
- JVM tests for persisted association priority, conflicting persisted owners, normalized exact identity, verified-alias grouping, secondary tracking-number joins, rejection of the `20Documents` false positive, orderless weak-evidence review, and ignoring order-number-only service mail.
- Room/ViewModel test verifies two same-thread PayPal messages using the two spellings are both processed under the one uniquely matching saved order, with one shipment and no duplicate order.
- Room import test verifies a same-thread shipping message with a leading-letter variant uses the confirmation’s unique order ID and creates one order, one shipment, and two processed-message links; a separate test verifies a multi-tracking shipping email remains unprocessed while the confirmation imports.
- Existing Simpli Soda, eBay, verified-alias, conflicting tracking, rollback, repeat-scan, and manual-association tests remain in place; new diagnostics assertions cover all three Simpli correspondence messages.

### Constraints and review behavior
- Merge confirmation remains exclusively in Inbox → Needs Review. Unresolved email associations are only ephemeral because storing unresolved message records would require schema/storage changes; they reappear when scanned again. Global duplicate review is available directly from current Room orders without Gmail authorization.
- Inbox-only query (`labelIds=INBOX`) and its pagination path are unchanged. VersionName `1.0.5-dev`, versionCode 29, and Room schema 10 are unchanged.

### Validation for this follow-up
- Debug build: `assembleDebug` **passed**; instrumentation source compilation **passed**.
- JVM: **73 passed, 0 failed** (GmailParsingTest 25, GmailMatchingTest 40, ScanUpdateResultsTest 8).
- Android instrumentation: **65 passed, 0 failed** on `Medium_Phone_API_37.0`, including order-number-first import, source-thread variant handling, multi-tracking review split, order-specific review scoping, orderless weak-evidence review, customer-service filtering, rollback, alias, tracking ownership, false tracking suppression and Simpli Soda regressions.
- Emulator app: final debug APK installed and launched successfully. The app was not previously installed; installation used `adb install -r`, with no uninstall or wipe. Existing emulator data was not cleared.
- Real Gmail/OAuth: **not tested**. The screenshot supplied by the user was used as a report of the visible grouping issue, not as proof that live Gmail was processed during this validation.
- Inbox-only/version/schema: `labelIds=INBOX` is unchanged; versionName `1.0.5-dev`, versionCode 29, and Room schema 10 remain unchanged.
- Remaining association limitation: two messages with different explicit external order numbers and no shared tracking, thread, saved association, or verified identity evidence remain separate or go to review. A thread-based leading-letter variant also requires one unique saved order; otherwise manual review is needed.

---

## WP-02 delivery-exception and Inbox-scope follow-up

### Root cause found
The Gmail parser recognized delivery exceptions, but `statusForKind` returned no shipment status for `EmailKind.EXCEPTION`. The write path also excluded exception messages from `isShippingKind`. As a result, an exception message matched to an existing order could be marked processed even though no shipment state changed; without an existing shipment, it also bypassed Needs Review. This violated the processed-marker contract and discarded real shipment evidence.

### Source changes
- `GmailParsing.kt`: map exception evidence to the distinct `Exception` shipment status, include exceptions in shipping correspondence routing, and provide a pure Inbox-only Gmail list-path helper.
- `MainActivity.kt`: persist an exception against the uniquely selected shipment, permit an exception transition from non-delivered states without replacing `Delivered`, route exceptions with no eligible shipment into review without writing a processed marker, and display per-email association/extraction diagnostics on update results and review items.
- `ScanUpdateResults.kt`: add ephemeral per-message diagnostics and source-message IDs for exact change attribution; no email body or extra database state is retained.
- `GmailParsingTest.kt`: cover exception classification/status and exact Inbox-only initial/paginated Gmail list requests.
- `Wp02AssociationRegressionTest.kt`: cover Room-backed exception persistence, history/result output, repeat-scan idempotence, and review/unprocessed behavior when no shipment exists.

### Validation in this session
- Debug build: `assembleDebug` **passed** with cached Gradle 8.11.1 and JBR 21.0.11.
- JVM: **58 passed, 0 failed** (GmailParsingTest 24, GmailMatchingTest 26, ScanUpdateResultsTest 8), including the Inbox query regression.
- Instrumentation source compilation: `compileDebugAndroidTestKotlin` **passed** after the exception and scan-diagnostics edits.
- Instrumentation execution: attempted on the existing API 37 AVD, but Gradle reported `emulator-5554` **OFFLINE**; no Android instrumentation tests ran. The emulator was stopped without wiping data. No app uninstall or data clear was performed.
- Real Gmail/OAuth and physical Pixel: **not tested**. No database migration or schema change occurred; Room remains schema 10, versionName remains `1.0.5-dev`, and versionCode remains 29.

The historical WP-02 implementation and its previously recorded test results follow; those results were not reproduced in this session.

---

# v1.0.5 development status — source patch 2026-10-25

## WP-02 association regression repair (latest)
Baseline `PixelPackageTracker_v1.0.5_WP02_Complete_Source`; versionName 1.0.5-dev, versionCode 29, Room schema 10 all **unchanged** (no schema change, no new dependency).

### Root causes
Real mailbox content was not available here. The causes below were found by reading the pipeline and reproducing each failure with synthetic Gmail API JSON; which of them hit a given real email cannot be confirmed from this machine.
1. **Link targets were destroyed.** The HTML-to-text step removed every tag, including `<a href>` (so a tracking number only present in a carrier link was lost), and removed plain-text `<https://...>` links as if they were tags.
2. **Text normalization.** Only `&nbsp;` and `&amp;` were decoded. Numeric entities (`&#160;`), raw non-breaking and zero-width characters are not matched by `\s`, so `FedEx:&#160;38429466112` could separate a label from its number. RFC 2047 encoded `Subject`/`From`, non-UTF-8 charsets and attachment-backed large text parts were not handled.
3. **Shipped evidence only from the subject; carrier by substring.** `shipped` in the body was ignored unless the subject matched, and `contains("UPS")` matched words like "groups" while picking the first carrier named anywhere.
4. **Association needed an explicit order number in every email.** A shipping email without one, or a carrier email whose retailer email did not repeat the number, was never associated. There was no thread or retailer-context stage.
5. **Emails were marked processed without being applied.** A shipping or delivery email that reached an order but produced nothing was still recorded at the current parser version, so no later scan reconsidered it. The parser version was not bumped when parsing improved.
6. **Retailer identity was the exact marketplace name.** DSWATCH and DS Designer could never match each other; Inbox import, the manual duplicate check and saved-order matching all compared exact names, so two cards appeared.

### Source implemented and build/test verified here
- **Parsing (`GmailParsing.kt`, new):** `parseGmailMessage` (MIME walk, per-part charset, attachment-backed parts, encoded headers), `htmlToText` (keeps `[link: url]`, decodes all entities, normalizes NBSP/zero-width), carrier tracking-link extraction (FedEx/UPS/USPS/DHL/OnTrac/Cainiao hosts only, including redirect-encoded links), word-boundary carrier detection with evidence order (link host, beside the number, "shipped with", number format, sender), email classification (confirmation / shipped / out for delivery / delivered / exception) with conditional-sentence filtering, conservative `Item:` extraction and a labeled own-domain order link.
- **Tracking extraction:** the requested sentence "Putting the tracking number below into FedEx: 38429466112" yields FedEx + the number; order numbers, invoice and reference numbers and retailer "Track package" links never become tracking numbers.
- **Staged association (`GmailMatching.kt`):** (1) retailer + exact order number, aliases count as one retailer, a unique tracking number breaks ties; (2) Gmail thread of an associated email; (3) retailer shipping notice without an order number when exactly one open order of that retailer exists; (4) carrier mail through a tracking number exactly one order claims; (5) carrier or forwarded mail naming exactly one open order's retailer. Several candidates, conflicts or claimed-by-two always go to review; nothing is guessed.
- **Retailer aliases:** built-in verified group DSWATCH = DS Designer (reported as one retailer; same `DSDF` order numbers); other groups only by explicit user confirmation, stored in DataStore (no schema change). Aliases are used by matching, saved-order lookup, Inbox import, manual duplicate check and the deleted-order check. Unverified names are never merged automatically.
- **Order/shipment effects:** a shipped notice without a tracking number creates a shipment titled `Retailer · Carrier` and no tracking number; a tracking number with no movement evidence sets `Label Created` (not shipped); a shipping/delivery email that reaches no single shipment is reported and left **unprocessed**; `GMAIL_PARSER_VERSION = 3`, so markers written by the previous build are reanalyzed by a manual Update (background sync still does not reanalyze).
- **Needs Review (UI, compile-verified only):** below Update / Update All results. Ambiguous or unapplied emails can be associated with an order (optionally a specific shipment) after a preview of tracking, carrier, status evidence and conflicts. Confirming writes the result and a processed marker with `MANUAL_ASSOCIATION_VERSION = 1000` in the existing `processed_gmail_messages` table, so later scans treat that email as already resolved. Possible duplicate orders are offered for merge with an explicit confirmation.
- **Association vs merge:** association connects an email/tracking number to an existing record. `mergeOrders` combines two records of one purchase in one transaction: items (duplicates dropped), shipments (a parcel recorded twice is kept once and loses no number or event), tracking numbers, events, history and processed markers all move to the kept order; the kept order's own values and manual status are not overwritten; a history row records the merge. Nothing is deleted without that confirmation. An unverified alias can be remembered during the merge.
- **Associate tracking number / create shipment:** existing `addTrackingNumber`, `createStandalone` and Inbox import remain; they refuse a tracking number that already belongs to another shipment.
- **Orders screen:** unchanged Room Flow observation; the new rows appear through it (verified at ViewModel Flow level).
- **Background sync:** `syncOrdersFromMessages` holds everything the worker does after the Gmail fetch and is tested.

### Tests (all executed here)
- JVM: 57 tests (GmailMatchingTest 26, GmailParsingTest 23 new, ScanUpdateResultsTest 8), 0 failures.
- Emulator (`Medium_Phone_API_37.0`): 56 tests (GmailScanUpdateTest 7, Wp02ImportSafetyTest 25, Wp02AssociationRegressionTest 24 new), 0 failures. The new class builds Gmail API JSON (multipart, HTML with entities and links, Latin-1, attachment-backed part, encoded headers), parses it, and runs the real scan, import, association and merge code on an in-memory Room database.
- Case A (Simpli Soda): three emails -> one order, one FedEx shipment, tracking `38429466112`, three markers; variants without an order number (same thread, different thread, carrier first, retailer email without tracking); repeat scans idempotent; markers from the old parser reanalyzed.
- Case B (eBay): shipping email with USPS and no tracking number -> USPS shipment on the existing order, no tracking number, repeat scan no change, delivery follow-up, manual order status preserved, body-only shipped notice, confirmation never counts.
- Case C (DSWATCH / DS Designer): verified alias matching, no second card from import or manual save, ambiguous email leaves duplicates untouched and lists them for merge, unverified names not merged, merge preserves items/shipments/tracking/events/history/markers, remembered alias.
- Also: multiple orders from one retailer (ambiguous), carrier mail with tracking, tracking-number collisions, manual association persistence and no repeated review, chosen shipment, conflict refusal, failed association rollback, background sync, HTML/MIME parsing, existing history provenance.
- Mutation spot checks: lowering the parser version and disabling context association made four tests fail; reverted.

### Retailer enrichment: supported and unsupported
- **Supported:** Gmail confirmations and shipping emails (order number, placed date, notes, items from explicit `Item:` lines, order total, an order link on the sender's own domain, tracking numbers, carrier, status evidence), and carrier emails by tracking number.
- **Not implemented:** no retailer API (Amazon, eBay, AliExpress or others) is called or claimed to exist; no retailer order page is fetched or scraped (automated retrieval has not been verified as permitted and nothing here bypasses logins, CAPTCHAs or protections); no carrier tracking API. `View Order` URLs are stored and opened in the browser only; Gmail remains the fallback source.

### Not verified / remaining
- No real Gmail account, OAuth flow or `scanGmailPreviews` network run; no real emails from the three cases. Compose screens (Needs Review, alias list, refusal notice) were compile-checked only; no UI test, no physical Pixel, no schema 9->10 migration test.
- Ambiguity is never persisted as "dismissed": a duplicate pair you decline to merge is offered again on the next scan. An email you do not associate is listed again too.
- Association to a standalone shipment (no order) is not remembered, because a processed marker needs an order; add the tracking number to the shipment or link it to an order instead.
- Inbox scan is still INBOX-label only and English-only regex parsing; shipping mail archived out of the Inbox is not seen.
- The DSWATCH = DS Designer alias is seeded from the user's report, not verified against real mail.
- Items extraction fills only an order that has no items and only from explicit `Item:`/`Product:` lines.

---
## Earlier: WP-02 complete implementation (superseded where it conflicts with the section above)
Baseline: v1.0.5-dev, versionCode 29, Room schema 10 — all unchanged. No schema change, no new dependency.

### Source implemented AND build/test verified on this machine
Environment: Gradle 8.11.1 (cached distribution, `--offline`), JDK 21 (JetBrains Runtime 21.0.11), Android SDK platform 35, AVD `Medium_Phone_API_37.0` (API 37 Google Play image, x86_64). A Gradle wrapper script/JAR is still not in this archive.
- `gradle testDebugUnitTest`: BUILD SUCCESSFUL — 34 JVM tests (GmailMatchingTest 26, ScanUpdateResultsTest 8), 0 failures.
- `gradle connectedDebugAndroidTest` on the emulator: BUILD SUCCESSFUL — 32 tests (existing GmailScanUpdateTest 7 + new Wp02ImportSafetyTest 25), 0 failures.
- Mutation spot check: disabling the manual-order-status rule made `manualOrderStatusSurvivesGmailWhileShipmentStatusStillAdvances` fail; the change was reverted.

| Requirement | Implementation | Tests |
|---|---|---|
| 1 Safe order matching | `GmailMatching.kt`: `assignEmails` (Matched / Ambiguous / Unrelated), whole-token identifiers, retailer evidence (sender = strong, mentioned = weak), tracking claims | GmailMatchingTest (exact, prefix, cross-retailer, ambiguous tracking, claims); Wp02 matching tests |
| 2 Correspondence grouping | dedupe by message ID in `groupedScanResults`; retailer-aware saved-order match; brand-based retailer keys; `shippedWithoutTracking` | grouping tests; retailer+carrier under one order; no-tracking shipment evidence |
| 3 Transactional import | `importScanGroup` = single transaction under the scan mutex; `ReviewAbort` rolls back; markers in same transaction | rollback + marker consistency, review leaves nothing, duplicate prevention, concurrent scan/import |
| 4 History | `recordChange` + provenance (`manual`, `derived`, `inbox-import`, Gmail message ID); manual save/edit/add/link/status paths; import for existing orders | manual edit history, shipment/tracking edits, linking, bookkeeping-only saves write nothing |
| 5 Results | scan results built only from committed rows; kinds New value / Correction / Backfill; Updated / Unchanged / Review / Failed | repeated scans, summary counts, failed scan |
| 6 Parser reanalysis | `GMAIL_PARSER_VERSION = 2`; current-version markers are no-ops; older markers reanalyzed by manual Update (BACKFILL), not by background sync; marker upgraded in the same transaction | reanalysis test |
| 7 Orders sync | Room Flows unchanged; order vs shipment status separate; manual order status is sticky (see rule) | Orders flow tests (`TrackerVm` flows) |

### Documented rules
- **Manual order status:** if the latest "Order status" history row has manual provenance, Gmail never changes the order status. Manual shipment-status edits still roll the order status up (never for Cancelled) and are recorded as "derived".
- **Populated values are never overwritten by Gmail** (placed date, notes, URL). Only blank values and the placeholder note "Discovered from Gmail Inbox…" are filled.
- **New orders created by Inbox import** record no history for their initial values; later changes do.
- **Refused manual saves:** a tracking number that already belongs to another shipment (or repeats within the form) is refused and shown on screen.
- Messages mentioning several order numbers, several shipments, or several *new* tracking numbers (shipments vs handoffs cannot be told apart) go to review and are not marked processed.

### Not verified
- No real Gmail account/OAuth/`scanGmailPreviews` run; extraction uses synthetic emails only.
- No Compose UI test: the refusal notice, history "Source:" line and import result text are compile-verified only.
- No physical Pixel; no schema 9→10 migration test (schema unchanged, existing migration code untouched).
- Orders-tab synchronization is verified at the ViewModel Flow level, not by rendering the screen.

### Remaining / limitations
- Parsing is English-only and regex-based; retailer identity relies on sender display name/domain or retailer name in the text.
- Manual shipment edits in the order form are positional (shipment i in the form = i-th saved shipment).
- Legacy history rows (before this change) show "Source not recorded"; legacy placed-date epoch values are still formatted on display.
- Background sync does not reanalyze older markers by design; until a manual Update is run, messages with version-1 markers stay as they were.
- WP-01 tests from the other working directory are still not merged; WP-03 untouched.

---
## Earlier status (kept for history; see above for current state)

Baseline: v1.0.5-dev, versionCode 29, Room schema 10.

This ZIP is derived from `PixelPackageTracker_v1.0.5_DEV_Source_16ItemAudit.zip`, **not** from Codex's later VS Code working directory. Accordingly, the WP-01 tests reportedly created in that working directory are **not present in this archive**. Do not overwrite a newer local project without merging/reviewing differences.

## Targeted source changes in this archive
- Preserve `archivedAt` and `lastSyncedAt` when editing an order; avoid updating order status timestamp when status is unchanged.
- Permit currency-only monetary totals in Gmail Notes extraction while retaining placeholder rejection.
- Re-query order shipments and tracking numbers before processing each matched email, reducing duplicate inserts and stale shipment-status comparisons in sequential scans.
- Attribute selected purchase date to an email containing that selected date, rather than the first email with any date.

## Remaining work
- WP-02 matching collisions, review-before-write semantics, complete field-level audit coverage, parser versioning, and structured scan results are not completed here.
- WP-01 test files from Codex's workspace are unavailable in this archive; re-merge from the working directory.
- Missing Gradle wrapper executable scripts and JAR; this archive retains the source baseline's wrapper properties only.

## Validation
- Source-level patch and archive integrity checks only.
- Android compilation, JVM tests, emulator tests, and Pixel tests **not run** in this environment.
- No database schema, app version, or dependency changes.

## WP-02 targeted Gmail association patch (2026-10-25)
Source changes in MainActivity.kt: recognize FedEx numeric tracking in shipping sentences and shipment subjects; group retailer and carrier correspondence using the shared extracted tracking ID; avoid grouping equal order IDs across different merchant domains; require an exact extracted order number for direct saved-order matching; create a shipment without a fabricated tracking number when an explicit retailer/carrier shipped notice supports it; include carrier emails with uniquely linked retailer tracking during updates. The existing updated-implementation source is the baseline.
**Verification:** Source implemented only. Android compilation, JVM tests, emulator and device tests have not been run. The eBay and Simpli Soda user screenshots are acceptance scenarios, not executed test fixtures. Matching ambiguity, structured scan outcomes, provenance, parser-version reanalysis, and transaction-wide rollback still need work.

## WP-02 targeted fixes: detailed Gmail update results and Orders refresh (source implemented, NOT build-verified)
Baseline unchanged: versionName 1.0.5-dev, versionCode 29, Room schema 10 (no schema change; two read-only `@Query` methods added to `OrderDao`).

### Fix 1 — detailed scan results (source implemented)
- `updateOrdersFromEmails` now returns a structured `ScanUpdateSummary` (new `ScanUpdateResults.kt`) instead of a count triple. Each order has exactly one outcome: UPDATED, UNCHANGED, REVIEW_REQUIRED or FAILED.
- Each order is processed in a single Room transaction. Field changes are built from `OrderChangeHistory` rows written inside that transaction and surfaced only after it commits; an exception rolls the whole order back and yields FAILED with no changes listed.
- Reported per change: retailer/order number, field, before, after, source (sender and subject of the email, or "Source not identified"), applied time, and kind (New value / Correction / Backfill). Kind and source label are ephemeral (derived at scan time); the persisted history keeps its existing columns (field, old/new value, message id, email date, applied time).
- `lastSyncedAt` updates are bookkeeping and never produce a history row or an "updated" result. Equal values (after trim/whitespace normalization, absent markers treated alike) are not changes.
- Review-required emails (tracking number that cannot be tied to a single shipment on the order) are not applied, not recorded as processed, and not counted as updates. Previously these were counted as "review" and again as changed/unchanged.
- New history rows store readable values ("Not available", formatted placed date). Legacy rows holding epoch milliseconds are formatted on display.
- Update screen lists updated/review/failed orders with field details; unchanged orders are collapsed.

### Fix 2 — Orders refresh (source implemented)
- Orders already observes Room `Flow`s through `TrackerVm`; no second state mechanism was added. Causes found by static analysis (not reproduced at runtime):
  1. A Gmail update advanced shipment status but never updated the order's status or `statusUpdatedAt`, so the Orders card status and recency sort did not change.
  2. The update path compared against stale snapshots (caller's `OrderRecord`, and `StateFlow.value` lists that are empty in the background worker) and wrote the stale order back.
  3. The update ran in the Update screen's composition scope; leaving the screen mid-scan could cancel it part-way.
- Changes: order status is derived forward-only from persisted shipment status (never downgraded, never touches Cancelled/Delivered or unknown custom statuses) and only when the scan advanced a shipment; the order and its history are re-read inside the transaction; the apply phase runs non-cancellable under a shared mutex (manual and background scans serialize); the import path also reads fresh DAO data instead of `StateFlow.value`.

### Tests added (not executed)
- `app/src/test/.../ScanUpdateResultsTest.kt` — JVM unit tests for change detection, classification, source description, display formatting, order-status derivation and outcome counting.
- `app/src/androidTest/.../GmailScanUpdateTest.kt` — in-memory Room tests for: genuine change with before/after and matching history rows; repeat scan (with stale order snapshot) producing no updates; review-required not applied; forced failure rolled back and reported FAILED; missing order reported FAILED; updated state visible through `TrackerVm` flows; unrelated orders/items/shipments intact.
- `app/build.gradle.kts`: added `testInstrumentationRunner` and test dependencies only.

### Not done / limitations
- **Not compiled, not run.** This environment has no JDK, Android SDK, Gradle wrapper JAR or scripts. No build, unit-test, instrumented-test, emulator or device result exists.
- CORRECTION is classified only when a populated value is replaced (e.g. the "Discovered from Gmail Inbox" placeholder note); current matching rules otherwise never overwrite populated values.
- Source description is subject-based; it does not classify the email type (e.g. "shipping confirmation").
- The Inbox import path (`importScanGroup`) still modifies existing orders without history rows and can return a "Review required" message after having inserted an order; not changed here.
- Matching collisions, parser-version reanalysis and WP-02 items beyond these two fixes remain open. WP-03 untouched.


### WP-02 targeted follow-up (source implemented, not build verified)
- Import: ambiguous saved retailer/order duplicates and conflicting tracking owners are checked before writes in the import transaction.
- Import: date, notes, URL backfill and newly created tracking numbers are audited alongside persistence.
- Update: tracking numbers associated with another saved shipment are not silently added to this order; review is requested.
- Outstanding: global cross-order email matching collisions, full correspondence grouping validation, parser-version reanalysis, all manual-edit audit paths, and complete import-result detail UI.
- Verification: ZIP packaged; compilation and all automated/device tests NOT RUN.

## WP-02 follow-up (unverified)
The latest user report says automatic association still fails on-device. This source revision preserves Inbox-only Gmail discovery and exposes unmatched Inbox shipping messages for manual association. These are targeted corrections, not a claim that all real-mail matching failures are fixed. No build or device test was run for this revision.
