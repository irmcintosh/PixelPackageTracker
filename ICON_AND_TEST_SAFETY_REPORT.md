# Launcher icon and development/test safety correction

Date: 2026-10-27. This report concerns only the icon and test-workflow safety correction. Historical instrumentation results in other reports are not validation performed in this round.

## Icon finding

The established blue-and-gold package/map-pin icon is intact. I visually inspected `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`. All ten launcher/round PNGs across mdpi, hdpi, xhdpi, xxhdpi and xxxhdpi, plus `drawable-nodpi/ic_launcher_foreground.png`, exist and match their resources in the freshly built debug APK byte for byte (11/11 SHA-256 comparisons; `icon-resource-verification.json`). No icon was generated, replaced or modified.

`AndroidManifest.xml` correctly references `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`. Both adaptive XMLs reference the existing background color, custom foreground and monochrome vector; aapt2 inspection of both compiled XMLs confirms those references are packaged. The application ID remains `com.example.pixelpackagetracker`, the exported MAIN/LAUNCHER activity remains `com.example.pixelpackagetracker.MainActivity`, and the existing application label remains **Pixel Tracker**. APK badging confirms package identity, launcher activity, label and adaptive icon path. No manifest, strings, application ID, signing configuration or icon resource was edited. There is no Git history in this workspace for a historical resource diff; current assets and prior-round file changes provide no evidence of an icon replacement.

The confirmed earlier Gradle/UTP uninstall removed the installed application and its launcher entry. Reinstalling an APK does not establish that a previously placed home-screen shortcut was restored. There is no current source icon defect to repair. The exact current device-specific appearance/home-screen state cannot be established: `adb devices` was empty, no emulator was running, and only the protected working AVD `Medium_Phone_API_37.0` is configured. I did not start it, reinstall the working app or alter launcher data to investigate. Thus a missing home-screen shortcut after the earlier uninstall is consistent with the evidence, but current app-drawer rendering is **not visually verified**. The resource itself was visually verified on the host; APK packaging was verified.

## Safeguards implemented

- `build.gradle.kts`: root task-graph guard fails before any task action if a graph includes install/uninstall, connected/device/managed-device test tasks, known Android installer/instrumentation task types or non-build AndroidTest runners. It inspects resolved dependencies too, so selecting an alias or abbreviated task does not avoid the check. There is no project opt-out for running these Gradle device tasks, including on a disposable AVD; use explicit manual ADB there instead.
- `gradle.properties`: configuration cache defaults off. The root build also refuses an explicit configuration-cache request, preventing cached task execution from bypassing the graph listener.
- `scripts/Build-Safely.ps1`: an exact parameter allowlist permits only `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:assembleDebugAndroidTest`. It uses the existing compatible JDK and Gradle distribution (or explicit paths), disables configuration caching and stops on build failure. It never starts a device, invokes ADB or installs anything. No wrapper launcher/JAR exists in this checkout, so the script can locate the cached Gradle 8.11.1 launcher without inventing a wrapper.
- Root `AGENTS.md`: persistent repository instructions protect the working AVD and every physical device, prohibit direct instrumentation there (even `am instrument`, whose tests may change jobs/preferences), forbid uninstall/clear/reset/destructive migrations/snapshot deletion without explicit approval, and require a separately identified disposable AVD for instrumentation. Serial numbers alone do not establish device identity. If a disposable AVD is unavailable, tests are NOT RUN.
- README and background-scan instructions no longer recommend manual instrumentation against the working installation. Necessary authorized updates require verifying explicit device identity, package identity and signing compatibility before `adb -s <serial> install -r`; failure must never lead to uninstall/clear/downgrade or silently selecting another device. No update was necessary or performed in this correction.

These are project-level safeguards and agent instructions; they do not intercept an arbitrary ADB command typed outside the project or a deliberate removal of the guard. No claim of system-wide uninstall prevention is made.

## Exact files changed

Build/safety implementation:
- `build.gradle.kts`
- `gradle.properties`
- `AGENTS.md` (new)
- `scripts/Build-Safely.ps1` (new)

Documentation:
- `README.md`
- `BACKGROUND_SCAN_TEST_PROCEDURE.md`
- `CHANGELOG.md`
- `PHASE2_PROGRESS.md`
- `V1_0_5_IMPLEMENTATION_STATUS.md`
- `V1_0_5_16_ITEM_AUDIT.md`
- `V1_0_5_20_ITEM_AUDIT.md`
- `ICON_AND_TEST_SAFETY_REPORT.md` (this report)

Generated logs/resource verification and the inert probe under `build/icon-safety/` are validation artifacts. No app Kotlin, Room entities/migrations, app module dependencies, manifest or resources changed. Gmail, archive/delete controls, background interval, notifications, dates, copy controls and regex memory behavior were not changed.

## Validation performed

1. Before choosing tasks, ran cached Gradle `:app:tasks --all --console=plain` (task listing only). It lists the three non-device build/JVM tasks and confirms device tasks have install/uninstall behavior. Evidence: `icon-safety-task-inventory.log`.
2. Ran `powershell -File scripts/Build-Safely.ps1` (equivalent script invocation in the existing PowerShell shell). It executes `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --console=plain --no-configuration-cache`. Both APK builds succeeded. Evidence: `icon-safety-build.log`.
3. Ran fresh JVM execution with `gradle.bat :app:testDebugUnitTest --rerun --console=plain --no-configuration-cache`: **158 tests, zero failures/errors**. Evidence: `icon-safety-jvm.log` and `app/build/test-results/testDebugUnitTest/` (fresh XML).
4. Verified guard using an init script that registers four **inert synthetic tasks** (`installSafetyProbe`, `uninstallSafetyProbe`, `connectedSafetyProbe`, `disposableSafetyAndroidTest`) and an alias depending on them. These tasks contain no ADB or device operations; their only action would throw a probe-failure exception if reached. `gradle.bat -I build/icon-safety/probe.init.gradle safetyProbeAlias --console=plain --no-configuration-cache` was rejected by DEVICE SAFETY GUARD before any action. Its nonzero Gradle exit is the expected passing guard result. Evidence: `icon-safety-guard-probe.log`. An initial probe-file BOM encoding error was corrected before the successful check.
5. Ran harmless `gradle.bat help --configuration-cache --console=plain`: rejected by the configuration-cache guard as intended. Evidence: `icon-safety-cache-guard.log`.
6. aapt2 badging and compiled adaptive XML inspection plus 11 source/APK PNG byte comparisons passed. Existing source PNG was viewed; current app-drawer UI was not.
7. Instrumentation: **NOT RUN this round**. No separate disposable AVD exists; only the protected working AVD is configured, and no ADB devices are connected. No physical Pixel was targeted. No real connected/device task was invoked, even as a dry run; guard validation used inert probes only.

Toolchain: cached Gradle 8.11.1, JDK `C:/Users/Ian12724/.jdks/jbr-21.0.11`, SDK build-tools 35.0.0 for aapt2. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Data-preservation statement and remaining limits

During **this correction**, no working installation was installed/reinstalled, uninstalled, cleared, force-stopped or launched. No emulator was started, wiped or reset; no snapshots were deleted or reset. No app database was opened/migrated/deleted and no preferences, Google authorization or app records were changed by our actions. Read-only ADB listing found no connected devices; two initial read-only inspection requests returned device-not-found. Working application data was therefore untouched by this round, although its contents could not be inspected without a connected device. This does not restore or retroactively certify the data lost in the preceding round.

Source/build/host resource inspection: verified. Device app-drawer appearance and current home shortcut: unverified. Instrumentation on disposable AVD: not run because unavailable. Physical Pixel/live Gmail: not tested. No replacement icon, reinstall or data-restoration attempt was made. No release-readiness claim or unrelated release package.
