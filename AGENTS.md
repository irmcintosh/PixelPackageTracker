# Pixel Package Tracker: mandatory device/data safety

These rules apply to all work in this repository. User authorization is required
before uninstalling or clearing any working installation, resetting app databases,
using destructive migrations, deleting emulator data, or deleting/resetting snapshots.

- Treat `Medium_Phone_API_37.0` and every physical device as protected working
  environments. A serial such as `emulator-5554` is not an identity: verify the AVD
  name each time. An unknown device is protected, never disposable by default.
- Never run Gradle connected/device/managed-device instrumentation, install or
  uninstall tasks here. The root task-graph guard blocks them before execution,
  including dependencies of aliases. Do not remove/bypass it or use configuration
  cache (`--configuration-cache`) with this task-graph guard.
- Use `scripts/Build-Safely.ps1`: it accepts only `:app:assembleDebug`,
  `:app:testDebugUnitTest`, and `:app:assembleDebugAndroidTest`. Inspect task names
  and implementation before adopting any additional task. A test/build label is
  not evidence that a command preserves data.
- Distribution uses `scripts/Release-Apk.ps1` and only
  `dist/PixelTrack-v1.0.9.apk`. The source is the fixed main-app path
  `app/build/outputs/apk/debug/app-debug.apk`; never discover APKs with wildcards,
  choose the newest APK, or rename/copy `app-debug-androidTest.apk` as the app.
  The script verifies package, launcher/icon, reference signing certificate,
  recent feature markers and byte-identical copying before publishing. Stop on
  verification failure. A filename does not change APK identity or internal version.
- Do not run instrumentation on the working emulator or physical Pixel, even via
  `am instrument`: existing tests change preferences, jobs and notifications.
  If no separate disposable emulator is available, report instrumentation NOT RUN.
- Instrumentation requires a separate AVD explicitly designated for disposable
  testing, verified with `adb -s <serial> emu avd name`, no copied working data or
  Gmail session, and an explicit serial on every command. Build test APKs without
  device tasks; then use manual ADB installation/instrumentation only there.
- Never use `adb uninstall`, `pm clear`, app storage clearing, database resets,
  `-wipe-data`, or snapshot deletion against working installations. Never change
  the application ID as a workaround. Do not uninstall after an update failure.
- Working-device updates are separate from build/test validation. Only update when
  necessary and authorized: check package/application ID, launcher activity and
  installed/candidate signing certificates first, then use `adb -s <serial> install -r`.
  Stop on mismatch/failure; do not downgrade, uninstall, clear data or silently switch devices.
- Icon investigation uses source/APK inspection and launcher observation. Do not
  reinstall solely to troubleshoot a missing shortcut. Do not launch the tracker
  just to inspect its icon (startup can trigger sync/retention work).

Preserve Gmail/authentication, workflows, schema and user records when doing build
safety or icon work. See ICON_AND_TEST_SAFETY_REPORT.md for current evidence.
