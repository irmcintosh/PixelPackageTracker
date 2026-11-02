# Unified copy dialog - implementation and verification

Date: 2026-10-27. Scope: copy controls, field selection and clipboard formatting only.

## UI changes

Each normalized order and shipment card now has one standard Material Content Copy vector icon at the upper-right of its heading. It uses the existing Material 3 IconButton and theme primary color, preserves the dark theme/purple accent, has the accessible description Copy order details or Copy shipment details, and is outside the expanded-only content. Its click only opens the copy dialog; it does not toggle expansion. Existing preserved legacy order cards use the same order-copy dialog.

Removed every inline CopyButton beside order numbers, tracking numbers and URLs, plus the old full-order/full-shipment copy buttons. Underlying displayed text, SelectionContainers, links, status/progress, expansion and existing action callbacks remain. The same shared OrderCard/ShipmentDetailCard components are used by Orders, Shipments and Archive, so active, Delivered and archived cards get the same controls.

The shared Material 3 AlertDialog shows labels, full actual values and checkboxes. Either the row or its checkbox area toggles selection. It starts with no selection on each opening, supports Select all and Clear, disables Copy (0), and offers Cancel. Copy (N) formats only the chosen rows, writes through the existing Android clipboard helper and closes the dialog. Cancel/dismiss never invokes the clipboard helper. Selection uses a stable, immutable snapshot taken on opening, so a background UI update does not unexpectedly change selected values. Existing confirmation behavior is retained: Toast below Android 13; the system clipboard confirmation on Android 13 and later.

## Available fields and formatting

- Orders: saved title, retailer, exact order number, order status/date (retaining the existing approximate-date qualifier), last status update, last sync, notes, complete order URL, products/quantity and saved prices, plus associated shipment fields. Associated shipments are identified by shipment number when multiple exist, and shipment timestamps are distinguished from the order timestamp.
- Shipments: saved title/carrier/status, shipped/estimated/delivered dates when known, last status update, last checked when known, status source, associated retailer/order/title/URL, and every saved tracking number with its own carrier and complete tracking URL.
- Primary, handoff and additional identifiers have separate selectable rows with unique IDs. Carrier names remain in their labels and are independently selectable too. Repeated roles are distinguished; identifiers are never merged.
- Missing/blank information and unknown dates/check times are omitted. No Not available/Not checked yet rows are synthesized. Identifiers and URLs are not trimmed, normalized, truncated or rewritten.
- One selected field copies its exact raw value. Multiple selected fields copy readable `Label: value` lines in display order. All field builders and the formatter are pure model-to-text functions, with no database calls or persistence writes.

## Exact source files changed

- `app/src/main/java/com/example/pixelpackagetracker/MainActivity.kt`: replace inline/full-block controls with one header action in the shared order, shipment and preserved legacy cards; existing card text/actions retained.
- `app/src/main/java/com/example/pixelpackagetracker/CopyDetails.kt`: replace the old inline emoji CopyButton/full-block helpers with the shared icon/dialog; retain the existing clipboard API/confirmation implementation.
- `app/src/main/java/com/example/pixelpackagetracker/CopyFields.kt` (new): pure available-field builders, independent tracking identifiers and selected-field formatting.
- `app/src/main/res/drawable/ic_content_copy.xml` (new): standard 24dp Material Content Copy vector, replacing the clipboard emoji without adding a dependency.
- `app/src/test/java/com/example/pixelpackagetracker/CopyDetailsTest.kt`: replace four old whole-block tests with 17 selection/availability/formatting regressions.
- `app/src/androidTest/java/com/example/pixelpackagetracker/ClipboardCopyTest.kt`: use selected-field builders for the existing clipboard/record-preservation tests.
- `app/src/androidTest/java/com/example/pixelpackagetracker/CopySelectionUiTest.kt` (new): six Compose tests for dialog controls, raw/labeled output, clipboard cancellation/reopening, collapsed/expanded and archived shipment controls.
- `scripts/Release-Apk.ps1`: require the new CopySelectionDialog/formatCopySelection bytecode markers when publishing the existing verified main-app APK.

Documentation updated: README.md, CHANGELOG.md, PHASE2_PROGRESS.md, V1_0_5_IMPLEMENTATION_STATUS.md, V1_0_5_20_ITEM_AUDIT.md and this report. No app Gradle dependencies, Room model/schema/migration, manifest, launcher icon or application identity changes.

## Validation and requested-check traceability

| Requested checks | Evidence | Status |
|---|---|---|
| 1-3 one accessible icon, collapsed access and correct dialog | Three shared header call sites (normalized order, shipment, preserved legacy); old copy call sites removed; Compose tests compiled | Source/build verified; not visually exercised on a device |
| 4-6 field/checkbox toggles, Select all, Clear and disabled empty Copy | Shared toggleable checkbox-row implementation and CopySelectionUiTest | Compiled; device interaction tests NOT RUN |
| 7-10 raw single value, labeled subset, independent primary/handoff and full long URLs | CopyDetailsTest: exact identifier characters/whitespace/Unicode; deterministic subset ordering; independent carrier labels; URLs over 10,000 characters | JVM passed |
| 11 archived orders/shipments | Same shared components regardless of archived flag; JVM archived-field parity test; archived shipment Compose test compiled | JVM passed; live Archive navigation unverified |
| 12 Cancel leaves clipboard untouched | Cancel/dismiss only close state; Compose clipboard sentinel/reopen test compiled | Source verified; device clipboard cancellation test NOT RUN |
| 13 Update/View Tracking/Archive/Delete/expansion preserved | Complete MainActivity diff reviewed: only copy controls/header rows changed; existing callbacks unchanged; shipment-action UI tests compiled | Source/build verified; live interactions unverified |
| 14 existing records unchanged | Pure-builder immutability JVM test; existing Room clipboard regression updated and compiled; hash audit of all preexisting app/src/main files | JVM/source verified; Room instrumentation NOT RUN |

Build commands (all non-device):
- `scripts/Build-Safely.ps1`: main debug build and JVM tests succeeded (`copy-dialog-build.log`, initial 170 tests).
- `scripts/Release-Apk.ps1`: final main debug build and JVM tests succeeded; **171 tests, zero failures/errors**, including all 17 copy-field tests (`copy-dialog-release.log`, current XML under app/build/test-results/testDebugUnitTest/).
- `scripts/Build-Safely.ps1 -Tasks ':app:assembleDebugAndroidTest'`: **test APK compilation succeeded**, including the new Compose tests and updated clipboard/Room tests (`copy-dialog-instrumentation-compile.log`). This only compiles; it does not install or execute tests.

Instrumentation: **NOT RUN**. No devices are connected and only the protected working AVD is configured; no separate disposable AVD is available. No working emulator/physical Pixel was used for testing. Android 13+ clipboard overlay, pre-13 Toast, actual icon placement/wrapping/font scaling, checkbox touch behavior, clipboard permissions, complete Orders/Shipments/Archive navigation and existing action interactions remain unverified on a live device.

Source-scope audit: all preexisting runtime files outside MainActivity.kt and CopyDetails.kt are byte-identical to the prechange snapshot. The only new runtime files are CopyFields.kt and ic_content_copy.xml. The MainActivity diff was reviewed and contains only copy UI wiring/removal. Evidence: `build/copy-dialog/MainActivity-copy-only.diff`, `build/copy-dialog/baseline-runtime-hashes.json`, and `copy-dialog-verification.json`. No Gmail/auth/parsing/matching, lifecycle, association, archive/delete persistence, scheduling, notifications, date extraction or regex logic was changed. Database version remains 12 with no migration.

## Verified updated main application APK

`C:\Users\Ian12724\Downloads\PixelPackageTracker_Android_Setup_Skill\PixelPackageTracker\PixelPackageTracker_v1.0.5_WP02_InboxOnly_Review_Fix\dist\PixelTrack-v1.0.9.apk`

- Main package: `com.example.pixelpackagetracker` (not the `.test` package).
- Original launcher icon and reference signing certificate verified by the existing release script.
- Contains CopySelectionDialog/formatCopySelection plus the previous archive/Settings feature markers.
- Size: 13,685,709 bytes; build timestamp: 2026-10-27T21:37:48.9840533Z.
- SHA-256: `b7a85d12adfe0473fdc59c06446a2297f53cb75641e0da9790fc5fc265873db8`.
- Internal version unchanged: 1.0.5-dev (29); filename remains PixelTrack-v1.0.9.apk under the existing distribution workflow.
- Distributed bytes match app/build/outputs/apk/debug/app-debug.apk exactly. Test APK is kept separate and is not distributed.

## Data safety

No application was installed, uninstalled, launched, force-stopped or cleared in this round. No app records/preferences, emulator data/snapshots or Google authorization were deleted/reset. No device-connected Gradle task or manual instrumentation command ran. Builds and host-side tests used the guarded non-device workflow. Source implemented/build verified/JVM tested; device interaction testing remains explicitly unverified.
