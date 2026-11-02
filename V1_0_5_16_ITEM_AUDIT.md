## 2026-10-27 Launcher icon and test safety

Original custom icon and adaptive/round configuration are intact; 11/11 PNG resources match the debug APK. No icon or runtime source change. Added a root Gradle device-task guard, configuration-cache protection, an allowlisted non-device build script and repository device-safety rules. Builds and 158 fresh JVM tests pass; inert guard probes pass. No devices are connected and no separate disposable AVD exists, so instrumentation and live app-drawer checks were NOT RUN. No working installation/data was changed in this round. See [report](ICON_AND_TEST_SAFETY_REPORT.md) and [safety rules](AGENTS.md).

## 2026-10-27 Email Accounts and archive corrections

Validation for this correction: 158 JVM tests passed; 164 emulator tests passed with 2 skips and no failures; debug build succeeded. Physical Pixel and live Gmail are unverified. Earlier Gradle UTP cleanup may have removed prior emulator app data; the final install -r/manual instrumentation run leaves the app installed. See the developer report for the incident and full evidence.

Implemented the compact Gmail account header, overflow disconnect confirmation, Connect/Reconnect and Scan / Import row, immediate Delivered shipment archiving, repair of older eligible archive records, shipment Archive/Delete, and transaction-safe Archive Delete All. Active shipments survive deletion of archived parents through safe detachment. Database stays version 12. See [developer report](EMAIL_ARCHIVE_DEVELOPER_REPORT.md) for exact files, validation, traceability and unverified live-device behavior.

# v1.0.5 — 16-item source audit (2026-10-25)

This is a DEVELOPMENT source build. Android/Gradle build and emulator verification have NOT been performed. The following statuses are based on source inspection only; no item is marked complete.

| # | Requirement | Source status / limitation |
|---|---|---|
| 1 | Gmail message deduplication | PARTIAL: Gmail message IDs are recorded per order; older messages can be reanalyzed for backfill. No versioned reanalysis policy or test coverage. |
| 2 | Accurate update counts | SOURCE CHANGED: sync timestamps excluded from changed count; field changes audited. Multi-email edge cases unverified. |
| 3 | Detailed before/after results | PARTIAL: history visible on order card; scan summary remains aggregate, not a full per-order wizard. |
| 4 | Persistent order change history | SOURCE CHANGED: Room history table and per-field audit for Gmail updates. Manual edits not yet consistently audited. |
| 5 | Improved email-to-order matching | EXISTING conservative matching retained; comprehensive validation and collision tests pending. |
| 6 | Correct purchase-date extraction | EXISTING confirmation fallback retained; real retailer fixtures needed. |
| 7 | Better Notes extraction | SOURCE CHANGED: reject obvious invalid placeholder fragments; real retailer fixtures needed. |
| 8 | Fill missing existing-order information | EXISTING backfill retained; manual-vs-automatic provenance not modeled. |
| 9 | Material 3 date pickers throughout | PARTIAL: order and shipment estimated dates use picker; every editable date not exhaustively verified. |
| 10 | Latest meaningful update on cards | SOURCE CHANGED: latest audited field and history shown. |
| 11 | Separate sync and status-change timestamps | SOURCE CHANGED: order card shows both, plus audit timestamp. |
| 12 | Automatic archived-order deletion | SOURCE CHANGED: periodic worker + expiration by archivedAt; emulator tests pending. |
| 13 | Archive timestamps and safe database cleanup | SOURCE CHANGED: archivedAt with migration; pre-migration archived records protected (no archivedAt). |
| 14 | WorkManager background storage maintenance | SOURCE CHANGED: daily best-effort cleanup worker; schedule not emulator-tested. |
| 15 | Room migration for Gmail deduplication and history | SOURCE CHANGED: 9→10 migration; schema validation and upgrade tests pending. |
| 16 | Temporary Gmail scan cleanup and storage reporting | PARTIAL: in-memory scan results disposed with screen, manual app cache cleanup and DB size display; no persisted temporary scan store or measured reclaim test. |

## Additional agreed Storage & Maintenance controls
- Retention dropdown with 30-day default: source implemented.
- Clean Up Temporary Storage: enabled; deletes only app cache files and reports bytes removed.
- Delete All App Data: enabled with record counts and typed DELETE ALL confirmation. Saves minimal tombstones to prevent reimport; preserves appearance and Gmail authorization. Database transaction and UI refresh need emulator testing.

## Additional acceptance item 17 — Order card progress synchronized with shipment status
Status: SOURCE CHANGED, build and tests verified (128 JVM, 88 emulator Room tests passed 2026-10-26). UI partially checked on the emulator (Ordered and In Transit cards only); Out for Delivery, Exception, multi-shipment, manual-note and standalone cards not visually verified. See V1_0_5_IMPLEMENTATION_STATUS.md.

Acceptance criteria:
- Five-stage model: 1 Ordered, 2 Processing, 3 Shipped, 4 In Transit / Out for Delivery (exact status shown), 5 Delivered.
- Single shipment In Transit → `4 of 5 · In Transit`; Out for Delivery → `4 of 5 · Out for Delivery`, description `Out for Delivery`; Delivered → `5 of 5 · Delivered`.
- Out for Delivery never advances to stage 5 or marks the order Delivered.
- Collapsed order card shows each shipment's carrier and persisted status (e.g. `Shipment: USPS · Out for Delivery`), identical to the shipment details text.
- Multi-shipment: stage 3 once any shipment has shipped; stage 4 only when every shipment has moved beyond early shipping; stage 5 only when every shipment is Delivered; aggregate label (never one shipment's most advanced status) plus individual statuses and counts.
- Manual order status is preserved; a note (`Order marked Delivered manually`) is shown when it differs from shipment progress.
- Exception and Customs are labelled explicitly, never shown as `Shipped · Awaiting confirmed carrier movement`.
- Standalone shipments show shipment-only progress (no Ordered/Processing stages).
- Manual order status remains untouched; repeated or older Gmail updates never regress a shipment status (including Customs).
- Derived from existing `ShipmentRecord.status` strings; no new status field and no Room schema change.

## Additional acceptance item 18 — Evidence-based Gmail discovery (SOURCE CHANGED; build, JVM 140 and emulator 99 tests passed; live Gmail and Import UI not verified)
- A scan can establish an order from a verified retailer order-status/shipping email when it has one labeled order number and at least two corroborating order-specific signals (status wording, item name, order fields, retailer order link, verified tracking). A confirmation is preferred but not required. Retailer sender plus one number never creates an order.
- Established orders appear in scan results with their evidence basis and can be imported; the import path no longer requires a confirmation. Shipping mail is attached by exact order number, then tracking number.
- A genuine shipment (plausible tracking number, strong carrier evidence, positive status) that cannot be tied to an order is created as a standalone shipment with carrier, tracking, status, date, Gmail message ID and history preserved; it may stay standalone indefinitely and is linked later only on verified evidence or by the user.
- Needs Review is used only for ambiguity, conflicting identifiers, unverified retailer or insufficient evidence, and each card names the failed rule.
- Orders created from Gmail keep the user's typed title (prefilled from the email subject when possible) and store the detected carrier on the shipment even without a tracking number.
- Inbox-only scanning, order-number-first matching, saved data, shipment history and manual status protection are unchanged. Database: additive Room 10 → 11 migration with a migration test.

## Release decision
NOT RELEASE-READY. No Gradle build, Room migration validation, emulator test, or live Gmail integration test has been performed. Remaining partial items must be completed and validated before v1.0.5 can be called complete.
