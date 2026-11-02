## 2026-10-27 Unified copy selection dialog

Orders, Shipments and Archive now use one Material copy icon in each card heading. The dialog starts empty and offers field checkboxes, Select all, Clear, Cancel and Copy (N). One field copies its raw value; multiple fields copy labeled lines. Primary/handoff identifiers and their carrier/URL choices remain independent, and unavailable values are omitted. Inline copy icons and old full-details copy buttons are removed. Final debug build and 171 JVM tests pass; Compose/clipboard tests compile but were NOT RUN because no disposable AVD is available. No application data/schema or other workflows changed. Updated main APK: [dist/PixelTrack-v1.0.9.apk](dist/PixelTrack-v1.0.9.apk). See [developer report](COPY_DIALOG_DEVELOPER_REPORT.md).

## Correct application APK for transfer (2026-10-27)

Use **[dist/PixelTrack-v1.0.9.apk](dist/PixelTrack-v1.0.9.apk)**. It is the verified main application (`com.example.pixelpackagetracker`), with the original icon, current features and the same signing certificate as the reference main APK. Do not transfer `app-debug-androidTest.apk`: it is the separate `.test` package.

Build/copy again with `powershell -File scripts/Release-Apk.ps1`. The script takes only the fixed main-app output, verifies it and publishes the one distribution APK. Default safe builds no longer also build the test APK. Main build succeeded; 158 fresh JVM tests passed. No working installation/data was changed. Device installation remains unverified. Internal version remains 1.0.5-dev (29); the filename is a distribution name. See [release verification report](APK_RELEASE_FIX_REPORT.md).

## 2026-10-27 Launcher icon and test safety

Original custom icon and adaptive/round configuration are intact; 11/11 PNG resources match the debug APK. No icon or runtime source change. Added a root Gradle device-task guard, configuration-cache protection, an allowlisted non-device build script and repository device-safety rules. Builds and 158 fresh JVM tests pass; inert guard probes pass. No devices are connected and no separate disposable AVD exists, so instrumentation and live app-drawer checks were NOT RUN. No working installation/data was changed in this round. See [report](ICON_AND_TEST_SAFETY_REPORT.md) and [safety rules](AGENTS.md).

# v1.0.5 20-item audit - correction scope, 2026-10-27

Validation for this correction: 158 JVM tests passed; 164 emulator tests passed with 2 skips and no failures; debug build succeeded. Physical Pixel and live Gmail are unverified. Earlier Gradle UTP cleanup may have removed prior emulator app data; the final install -r/manual instrumentation run leaves the app installed. See the developer report for the incident and full evidence.

This update tracks the existing 20-item skill matrix without expanding the attached request into an unrelated release rewrite. The current source stays at Room version 12. See EMAIL_ARCHIVE_DEVELOPER_REPORT.md for this request's 40-check traceability, exact files and final test counts. Existing historical audit entries are not proof of current release completion.

| ID | Existing requirement | Source / test evidence in current tree | Verification boundary |
|---|---|---|---|
| 01 | Gmail deduplication | MainActivity.kt, PackageData.kt; Wp02ImportSafetyTest, GmailScanUpdateTest | Fixture regressions; no live repeat-mailbox scan in this task |
| 02 | Actual change counts | MainActivity.kt, ScanUpdateResults.kt; ScanUpdateResultsTest, Wp02ImportSafetyTest | Fixture regressions |
| 03 | Before/after results | ScanUpdateResults.kt, MainActivity.kt; ScanUpdateResultsTest | No full results-screen UI walkthrough |
| 04 | Persistent change history | PackageData.kt, ChangeHistory.kt, MainActivity.kt; Wp02ImportSafetyTest | Room fixtures; new archive reconciliation idempotency tested |
| 05 | Exact matching | GmailMatching.kt; GmailMatchingTest, Wp02AssociationRegressionTest | Existing matching unchanged |
| 06 | Purchase date extraction | GmailParsing.kt, MainActivity.kt; EmailDatesTest, EmailDatesRoomTest | Existing parser unchanged; real mailbox not exercised |
| 07 | Notes extraction | GmailParsing.kt; GmailParsingTest | Synthetic fixtures |
| 08 | Safe backfill/manual preservation | MainActivity.kt; Wp02ImportSafetyTest, EmailDatesRoomTest | Fixture regressions plus archived purchase status protection |
| 09 | Date pickers | MainActivity.kt | Existing code retained; exhaustive picker UI not reverified |
| 10 | Latest meaningful update | ChangeHistory.kt, MainActivity.kt | Existing UI retained; archive operations audited |
| 11 | Separate sync/status times | PackageData.kt, MainActivity.kt | Existing model retained; shipped-date fixture tests |
| 12 | Archive retention deletion | PackageData.kt, BackgroundSync.kt; OrderShipmentWorkflowRoomTest | Existing expiry-safety fixtures; long-duration retention not reverified |
| 13 | Archive timestamps/safe cleanup | PackageData.kt, MainActivity.kt; ArchiveManagementRoomTest | New archived-only transaction, FK integrity and failure rollback tests |
| 14 | WorkManager maintenance | BackgroundSync.kt | Existing storage-maintenance job retained; TrackingIntervalSchedulingTest concerns Gmail interval rather than a new retention scheduling claim |
| 15 | Room migrations | PackageData.kt; GmailDiscoveryRoomTest, OrderShipmentWorkflowRoomTest | Existing 10-to-11 and 11-to-12 fixture tests; no new migration, no new 9-to-10 validation |
| 16 | Temporary scan cleanup/size | MainActivity.kt | Untouched; reclaimed-byte/storage accuracy not reverified |
| 17 | Retention dropdown | Preferences.kt, MainActivity.kt | Untouched; no new retention UI tests |
| 18 | Temporary cleanup | MainActivity.kt | Untouched; persistent-storage cleanup not executed on user records |
| 19 | Full app-data deletion | MainActivity.kt | Existing typed-confirmation control untouched; new Archive Delete All is a separate, narrower operation |
| 20 | Reimport safeguards | PackageData.kt, Preferences.kt, MainActivity.kt | Existing deleted-order markers reused transactionally for Archive Delete All; existing dismissal retained for individual shipment deletion |

Source implemented and build/emulator validation for the requested correction are reported separately from release readiness. Physical Pixel and live Gmail authorization/network behavior remain unverified. No destructive migration or schema change. No release ZIP certification or claim that every historical acceptance item is complete.
