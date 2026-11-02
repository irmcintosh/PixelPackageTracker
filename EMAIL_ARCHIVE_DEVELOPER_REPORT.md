# Email Accounts and archive corrections - developer report

Date: 2026-10-27. Scope: the attached correction request, implemented in the current workspace (application 1.0.5-dev, versionCode 29). No unrelated redesign. `TARGET_ORDER_SHIPMENT_WORKFLOW.md` and a source ZIP were not present in this workspace; current Kotlin, Room relationships, existing audits and tests were inspected directly.

## Root causes and implemented behavior

- Order handoff was gated on a new status/shipment change. Older eligible Shipped orders, unchanged scans, and edits that supplied missing shipment information could miss that gate. `handoffIfReady` is now evaluated after every applied order update and relevant manual save, association or tracking edit. `reconcileArchives` reevaluates persisted Shipped orders and Delivered shipments on app entry and through the shared foreground/background scan and individual/bulk update paths, including empty scans. It never derives a new purchase status. Shipped without usable associated shipment information stays active and retains the existing warning.
- Delivered shipments previously depended on an optional screen-driven, delayed archive pass. Shipment insert/update persistence now archives Delivered immediately, and reevaluation repairs older records. Dedicated archive SQL changes only the archive flag, preserving all status, carrier, dates, identifiers, tracking history and association fields. Archived purchase statuses are protected from subsequent Gmail shipment progression and manual shipment-derived progression. Label/tracking evidence still uses the existing four-stage lifecycle logic.
- Archive rendered only standalone archived shipments. It now renders every independently archived shipment, including shipments linked to active or archived orders. Active Shipments still excludes archived rows.
- Shipment Archive was hidden in expanded details and Delete was restricted to standalone records in both the UI and DAO path. Archive and Delete are now visible on every shipment card. Archive changes only that shipment's flag; Delete opens an explicit permanent-deletion warning and uses the shipment-ID delete query. Room cascades remove that shipment's tracking numbers/events, preserving its order and siblings. Existing Gmail-message dismissal behavior is retained for individual deletion.
- Settings now shows Gmail with a compact Connected/Disconnected indicator and a right-aligned three-dot menu. Disconnect session is in that menu, behind a confirm/cancel dialog explaining temporary-token clearing versus Google authorization. The confirm callback retains the former token/disconnected/status assignments. The primary button switches between Reconnect Email and Connect to Email. Connect/Reconnect and Scan / Import share a weighted row with compact padding; interval and last scan are secondary text. Gmail access is described as read-only Inbox access. Provider placeholders remain.

## Delete All data protection

The existing `shipments.orderId -> orders.id` foreign key uses ON DELETE CASCADE; identifiers/events cascade from shipments. A naive archived-order DELETE would therefore remove active shipments. No schema change was necessary.

Delete All is disabled for an empty archive. Confirmation displays archived order and shipment counts (plus earlier records if present), permanent scope and the cannot-undo warning. It captures exact IDs at dialog opening. The transaction rechecks that each confirmed row is still archived; restored records and records archived after the dialog opened are preserved. It first deletes only confirmed archived shipments, then detaches every remaining shipment from each confirmed archived order before deleting the order. Detachment updates only orderId, preserving the full shipment and its tracking history. Existing deleted-order markers prevent deleted archived orders from being silently reimported. Earlier archived legacy cards are also included explicitly in the confirmation and transaction. Active legacy records are preserved. Individual archived-order deletion also detaches active children before the existing order/archived-child deletion, and its confirmation explains that scope.

A trigger-induced failure test verifies rollback of shipment deletion, detachment, order deletion and deletion markers. No Gmail synchronization reset or background schedule change occurs. All new destructive tests use an in-memory database, never user records. Their scan timestamps use isolated cache-backed DataStore preferences.

## Exact source files changed

- `app/src/main/java/com/example/pixelpackagetracker/MainActivity.kt`: Settings, Gmail account component, archive reconciliation, handoff call sites, archived status protection, shipment controls and deletion, Archive display and Delete All controls/transaction.
- `app/src/main/java/com/example/pixelpackagetracker/PackageData.kt`: Delivered-aware insert/update wrappers, independent archive and repair queries, shipment detachment, legacy ID lookup. Room version remains **12**; no entities/columns/migrations changed.
- `app/build.gradle.kts`: Compose UI test dependencies and explicit Espresso 3.7.0 test dependency. Runtime library versions are unchanged. Espresso fixes reflective InputManager access used by older test libraries; see [official release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0).
- `app/src/androidTest/java/com/example/pixelpackagetracker/ArchiveManagementRoomTest.kt`: new isolated persistence, reevaluation, scan/update, independence, FK safety and rollback regressions.
- `app/src/androidTest/java/com/example/pixelpackagetracker/ArchiveControlsUiTest.kt`: new Archive/Delete UI callbacks, confirmation cancellation/counts and disabled-empty behavior.
- `app/src/androidTest/java/com/example/pixelpackagetracker/GmailAccountsUiTest.kt`: connected/disconnected labels, shared-row bounds, menu, cancellation, confirmed session clearing and Scan / Import callback. Fake in-memory token; no real authorization request.
- `app/src/androidTest/java/com/example/pixelpackagetracker/GmailScanUpdateTest.kt`: update old expectation to require reevaluation of another eligible Shipped order while preserving its remaining fields/children.
- `app/src/androidTest/java/com/example/pixelpackagetracker/OrderShipmentWorkflowRoomTest.kt`: update old Delivered expectation to immediate shipment archiving.
- `app/src/androidTest/java/com/example/pixelpackagetracker/TrackingIntervalSchedulingTest.kt`: capture periodic worker state once in the assertion, avoiding a RUNNING-to-ENQUEUED race between two reads; scheduling implementation unchanged.

Documentation updated: README.md, CHANGELOG.md, PHASE2_PROGRESS.md, V1_0_5_IMPLEMENTATION_STATUS.md, V1_0_5_16_ITEM_AUDIT.md, V1_0_5_20_ITEM_AUDIT.md, and this report. Generated validation logs and APKs are separate build artifacts.

## Request validation traceability

| Request checks | Source surfaces | Evidence / limits |
|---|---|---|
| 1-8 connected/disconnected Gmail, row, menu, confirmation | GmailAccountsSection | GmailAccountsUiTest: fake connected/disconnected states, visible labels, same-row bounds, menu and cancel/confirm. Physical Pixel and large accessibility font settings unverified. |
| 9 Gmail connect/reconnect and Scan / Import | existing rememberGmailAuthorization, existing Settings callback | Scan callback exercised; authorization implementation retained. No real Google connection/reconnection or live mailbox import was attempted. |
| 10-15 Shipped handoff, missing shipment, label-only, old orders, preserved association/status | handoffIfReady, settleOrder, reconcileArchives, applyAssignedEmails | ArchiveManagementRoomTest, OrderShipmentWorkflowRoomTest, OrderLifecycleNotificationTest, OrderWorkflowTest and OrderProgressTest. |
| 16-21 Delivered, old Delivered, independent archive, repeated scans | OrderDao shipment writes/repair, Archive/Shipments filters | New Room tests and shipment/email regressions; independent archived flag and idempotent history verified. Actual list inclusion is wired through the existing observed Room flows and filters; full seeded navigation UI not exercised. |
| 22-26 shipment Archive/Delete, cancel, dependent cleanup, parent preserved | ShipmentDetailCard, archiveShipment, deleteShipmentRecord | ArchiveControlsUiTest plus isolated Room deletion of linked shipment, preservation of siblings/order/history and FK cascades. |
| 27-29 disabled Delete All, counts and cancellation | ArchiveDeleteAllButton, ArchiveDeletionDialog | ArchiveControlsUiTest; callbacks remain inert until explicit confirmation. |
| 30-34 archived-only deletion, active-child detachment, FK integrity, rollback | deleteArchived, detachShipments | ArchiveManagementRoomTest includes active child, archived child under active order, unrelated records, restored rows, newly archived rows, PRAGMA foreign_key_check and trigger-induced failure rollback. |
| 35-37 foreground/background scans, individual/bulk updates and handoff | existing scan paths plus reconcileArchives | GmailScanUpdateTest, ShipmentUpdateRoomTest, Wp02ImportSafetyTest, Wp02AssociationRegressionTest, OrderShipmentWorkflowRoomTest and new empty-scan/single-update tests. Fixture execution of shared background code; no scheduled live Gmail job was observed. |
| 38 notifications | unchanged BackgroundSync and snapshot/notice paths | OrderLifecycleNotificationTest and BackgroundSyncTest. Physical notification display unverified. |
| 39 Tracking Interval | unchanged preferences/scheduling | TrackingIntervalSchedulingTest and BackgroundSyncTest. |
| 40 Gmail memory | parser/fetch/regex sources unchanged | RegexNativeMemoryTest exercises bounded native memory on synthetic large mail. No new full-mailbox memory measurement; previous live-mailbox limitations are not claimed resolved. |

## Validation commands and results

JDK: `C:/Users/Ian12724/.jdks/jbr-21.0.11`. Gradle: cached `gradle-8.11.1/bin/gradle.bat` (wrapper properties exist but no wrapper launcher was present). SDK from local.properties. Emulator: existing Medium_Phone_API_37.0, Android 17/API 37, x86_64, 1080x2400, density 420, serial emulator-5554. No uninstall, data clear or wipe was requested. However, earlier `connectedDebugAndroidTest` runs used Gradle UTP with `uninstall_after_test: true` (visible in utp.0.log), contrary to the user constraint. The final such run was stopped when this was discovered. Earlier runs may have removed prior emulator app data; preservation cannot be certified, and no pre-run backup exists. This was disclosed to the user. No physical device was touched. Final validation switched to explicit `adb install -r` plus `am instrument` with no uninstall or data clear. The current APKs remain installed.

Initial offline baseline attempts failed to resolve the Android Gradle plugin from the sandbox cache (`baseline-validation.log`, `baseline-jdk21.log`); no passing prechange baseline is claimed. Subsequent authorized runs used the normal dependency cache/download access. Development compile/test attempts identified and corrected test visibility/dependency setup and an outdated test-library InputManager call; final evidence supersedes those attempts.

Final build command: `gradle.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain` with JAVA_HOME above. Final emulator commands: `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`; `adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`; `adb -s emulator-5554 shell am instrument -w -r com.example.pixelpackagetracker.test/androidx.test.runner.AndroidJUnitRunner`. These commands do not invoke Gradle UTP cleanup.

- Source: implemented. Existing database fields sufficient; Room version 12 unchanged.
- JVM: 158 tests, 0 failures (final build command up-to-date after the last source build; current XML retained).
- Debug and instrumentation APK builds: successful (`validation-build-final.log`).
- Final emulator: 164 passed, 2 skipped, 0 failures; runner reports OK (166 tests). All 13 new Room tests and 8 new Compose UI tests pass.
- Skips: actual notification posting requires Android notification permission; the shipment-update/schedule-invariance test requires a preexisting scheduled job. The dedicated TrackingIntervalSchedulingTest passes, as do fixture status-notification tests.
- Synthetic large-email native-memory regression: passed. Previous successful UTP run measured 18 MB to 23 MB peak; no live mailbox measurement.
- Validation incident: earlier Gradle UTP runs were configured to uninstall the emulator app afterward. Emulator preexisting-data preservation cannot be certified; final manual instrumentation leaves the app installed. Physical devices were not touched.


Final build log: `validation-build-final.log`. Final instrumentation status log: `validation-instrumentation.log`. Earlier UTP XML/HTML may describe earlier or interrupted runs; final instrumentation log is authoritative for the latest source. Earlier Gradle log: `validation-final.log`. JVM XML: `app/build/test-results/testDebugUnitTest/`. Instrumentation XML/logcat: `app/build/outputs/androidTest-results/connected/debug/`. JVM HTML report: `app/build/reports/tests/testDebugUnitTest/`. Earlier UTP HTML: `app/build/reports/androidTests/connected/debug/`. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Verification boundary

Source implemented; final build/emulator status recorded above. Physical Pixel verification: **not performed**. Real Gmail authorization, reconnect, foreground mailbox fetch/import, scheduled network scan, full-mailbox memory and device-specific notification/clipboard behavior were not reverified in this task. No parser, matching, fetch, regex reuse, notification preference, date extraction or WorkManager schedule redesign was introduced. No new carrier APIs. Existing fields were sufficient; no additive or destructive migration was required or proposed. This report does not certify every historic v1.0.5 release gate.
