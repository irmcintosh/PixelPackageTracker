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

# Phase 2 Progress — v0.4.0-dev10

- Device tested in dev9 (user report): orders, multiple products/shipments, product dialog, carrier/marketplace search, links, persistence, and core editing.
- Source implemented in dev10: launcher label **Pixel Tracker**, user-selected package/location icon, adaptive launcher and themed monochrome icon.
- Build verified for dev10: **No**. Device tested for dev10: **No**.
- Outstanding Phase 2/release checks: install dev10 over dev9 without data loss; archive/restore and deletion regression tests; legacy v0.3.x database migration; permanent signing-key strategy; carrier-logo artwork was previously deferred.
- Phase 3: authorized carrier tracking provider/API integration, status/event updates, international carrier handoffs; not yet implemented.

### v0.4.0-dev11
- Launcher icon asset correction source implemented. Build verification and device icon check pending.

## User verification and phase transition (2026-10-24)
- User confirms v0.4.0-dev11 installation and existing functionality checks passed on Pixel, including approved launcher branding and data preservation. Phase 2 closed on user-reported checks.
- Earlier dev10/dev11 pending-check notes above are historical; see DEVELOPMENT_ROADMAP.md for revised Phase 3/4 scope.


### v1.0.5 preview
Source-only Storage & Maintenance retention preference added; no automatic cleanup or build verification.

## v1.0.5-dev
Source work added for audit history, archive timestamp and deletion controls. Build verification, emulator tests, and remaining partial checklist items are pending.
