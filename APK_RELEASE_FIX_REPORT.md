# Corrected application APK delivery

## Root cause and comparison

The distributed file was the instrumentation APK renamed to `PixelTrack-v1.0.9.apk`. SDK inspection confirms that test artifact is `com.example.pixelpackagetracker.test`, has an empty application label/icon, and has no launcher activity. Its contents cannot update the main app, so it explains both the generic updater icon and missing application improvements.

The app build configuration is correct. Compared with the available `../PixelPackageTracker_v1.0.5_WP02_InboxOnly_Review_Fix.zip`, the main application ID, versionCode 29, versionName 1.0.5-dev, manifest and application label are unchanged. Both old and current projects keep main and test APKs in separate directories; the reference ZIP itself includes both artifacts. Recent application-module changes added test dependencies, not an output-path or application-ID override. No prior copy/rename/distribution script or reference to `PixelTrack-v1.0.9.apk` was found in the source. Therefore the confirmed failure was selection/distribution of the wrong existing artifact; the precise human/tool selection event is not recorded and cannot be independently reconstructed. There is no evidence that Gradle started producing the test package as its main application output.

## Fix and exact files changed in this round

- `scripts/Release-Apk.ps1` (new): builds only the main app and JVM tests; reads only the fixed path `app/build/outputs/apk/debug/app-debug.apk`; rejects incorrect package/launcher/label/icon/certificate; verifies original icon bytes, recent feature markers and exact copied bytes; publishes one named APK in `dist`. It never scans for the newest APK or accepts a test APK as input. A failed check stops distribution.
- `scripts/Build-Safely.ps1`: default tasks now omit `assembleDebugAndroidTest`; ordinary builds produce the main application without also building the confusing test artifact. Explicit test-APK compilation remains possible separately.
- `AGENTS.md`: permanent distribution rule requires the verified main artifact and forbids wildcard/newest-file selection or test-APK renaming.
- Documentation: `README.md`, `CHANGELOG.md`, `PHASE2_PROGRESS.md`, `V1_0_5_IMPLEMENTATION_STATUS.md`, `V1_0_5_20_ITEM_AUDIT.md`, and this `APK_RELEASE_FIX_REPORT.md`.

The application Gradle configuration, application ID, version metadata, signing configuration, manifest, icon resources, Kotlin functionality and database schema were not changed in this round. Existing device-task safety guards remain enabled. No functionality was reverted to the reference ZIP.

## Delivered APK

- Filename: **PixelTrack-v1.0.9.apk**
- Absolute path: `C:\Users\Ian12724\Downloads\PixelPackageTracker_Android_Setup_Skill\PixelPackageTracker\PixelPackageTracker_v1.0.5_WP02_InboxOnly_Review_Fix\dist\PixelTrack-v1.0.9.apk`
- Source: `C:\Users\Ian12724\Downloads\PixelPackageTracker_Android_Setup_Skill\PixelPackageTracker\PixelPackageTracker_v1.0.5_WP02_InboxOnly_Review_Fix\app\build\outputs\apk\debug\app-debug.apk`
- Verified package: **com.example.pixelpackagetracker**, without `.test`.
- Launcher: `com.example.pixelpackagetracker.MainActivity`; label: **Pixel Tracker**.
- Size: **12,807,484 bytes**.
- APK build timestamp: **2026-10-27T21:00:51.8449116Z**.
- Distribution verified at: **2026-10-27T21:00:54.4562502Z**.
- SHA-256: `037ab7dd5ff48190cd80f254c97866d1b492a987ae18b264c92f0d03fef61ad6`.
- Internal version is still **1.0.5-dev / versionCode 29**. The requested filename is a distribution name; renaming does not alter version metadata.

The distribution APK is byte-for-byte identical to the main application APK and is not the Android test APK. `dist` contains only one APK plus its verification JSON. The test APK remains in its separate AndroidTest build directory and is never copied into `dist`.

## Icon and signing

All **14** current launcher resources (11 PNGs, both adaptive XMLs and the monochrome vector) match the supplied reference ZIP. The original package/map-pin image is intact; no replacement was needed. All 11 source PNGs match the bytes packaged in the delivered APK. aapt2 confirms the main application label/icon and launcher; both compiled adaptive/round definitions include the original foreground and monochrome references. Host visual inspection of the original icon was performed in the preceding safety inspection; live phone/app-drawer appearance was not verified.

`apksigner verify --print-certs` passes on the delivered APK. Its signing certificate matches the main APK extracted from the supplied reference ZIP:
`b526e739f5be7935e957e9f7d9e3126922913e7628417db208ed2d15f57b39c7` (SHA-256).
No signing keys were changed. Physical Pixel signing/version compatibility could not be compared because no devices are connected; compatibility is established against the available reference main APK, not an independently inspected phone installation.

## Current features and validation

The build uses the current source, including order handoff/automatic archiving, Delivered shipment archiving, shipment Archive/Delete, Archive Delete All protections, Gmail Settings redesign, Inbox-only scans, Update/Update All, notifications/background interval, copy controls, email dates and reusable regex matchers. None of those runtime files changed during this fix. Direct DEX inspection confirms nine recent archive/Settings/copy feature markers, including `reconcileArchives`, `deleteArchived`, `GmailAccountsSection`, the disconnect dialog and shipment management controls. This establishes inclusion of the latest code, not a new physical-device functional test.

- `scripts/Release-Apk.ps1`: **successful**, main application build succeeded; output selection, identity, original icon checks, signature and final copy hash passed. Log: `release-apk-build.log`.
- Fresh JVM task `:app:testDebugUnitTest --rerun --console=plain --no-configuration-cache`: **158 passed, zero failures/errors**. Log: `release-jvm-tests.log`; XML: `app/build/test-results/testDebugUnitTest/`.
- Independent aapt2/apksigner inspection of the final distributed APK passed.
- Reference icon comparison: `build/release-verification/icon-reference-comparison.json`.
- Machine-readable release evidence: `dist/PixelTrack-v1.0.9.verification.json`.
- Instrumentation and installation: **NOT RUN**. No separate disposable emulator or connected physical device was available. No real connected-device Gradle task was invoked.

## Safe update and remaining limits

Transfer **only `dist/PixelTrack-v1.0.9.apk`** to the Pixel using the same normal file-transfer process, then open it in the usual APK updater and choose **Update** for Pixel Tracker. The matched reference signing certificate and unchanged main package preserve the existing identity. If Android reports a signing/package/version conflict, stop; do not uninstall, clear storage, force a downgrade or change keys. Do not use `app-debug-androidTest.apk` for distribution. Future build/copy runs can use `powershell -File scripts/Release-Apk.ps1`; normal Android Studio main-app builds are unchanged.

**No working installation was uninstalled, cleared, updated or launched during this fix.** No emulator data/snapshots, app records, Gmail session or preferences were changed. This round does not restore the emulator data lost previously. Physical installation, live icon appearance and live Gmail/functionality remain unverified. The script performs no device installation and does not promise compatibility with a phone certificate/version it could not inspect.
