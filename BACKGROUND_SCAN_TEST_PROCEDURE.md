# Testing a background Gmail scan on a signed-in Pixel (no uninstall, no data clear)

The background scan is the periodic WorkManager job `InboxSyncWorker`. Android decides when it runs, and a forced run is not possible: `adb shell cmd jobscheduler run -f ...` is rejected by WorkManager ("executed before schedule"). The procedure below makes the job due soon by shortening the interval, then proves the run from logs and data.

## Never do these (they wipe the data or the schedule)
- `adb uninstall com.example.pixelpackagetracker`, `adb shell pm clear ...`, Settings → Apps → Storage → Clear data.
- Any connected/device/managed-device Gradle test or install/uninstall task. These are blocked by the root Gradle task-graph guard. Do not run manual `am instrument` on the working emulator or physical Pixel either; instrumentation is restricted to a separate designated disposable AVD. If none is available, report it NOT RUN.
- `adb shell am force-stop ...` or "Force stop" in Android settings: it cancels the pending job until the app is opened again.
- Settings → Danger Zone → Delete All App Data.

## 1. Preconditions (on the phone)
1. USB debugging on; `adb devices` lists the Pixel. `$adb = "<Android SDK>\platform-tools\adb.exe"`.
2. App → Settings: Gmail shows "Connected · Gmail read-only" (use Reconnect Gmail if not), and no red "notification permission is off" text (tap Allow notifications if it is shown).
3. Settings → Apps → Pixel Tracker → Battery: set to Unrestricted for the test (Battery saver off). Keep Wi-Fi or mobile data on.
4. Only for a necessary, authorized update: first verify the explicit target serial, package identity and candidate/installed signing certificates, then use `adb -s <verified-serial> install -r app\build\outputs\apk\debug\app-debug.apk`. Stop on any mismatch or failure; never uninstall or clear data as a fallback. Do not update solely to investigate an icon. An update can trigger background work against the real mailbox.

## 2. Baseline
1. Note the interval in Settings and the "Last synced" time on the Orders cards.
2. Back up the database (read-only copy; needs the debug build):
   `cmd /c "adb exec-out run-as com.example.pixelpackagetracker cat databases/packages.db > before.db"` (also copy `packages.db-wal`).
3. Optional: `python -c "import sqlite3;c=sqlite3.connect('before.db');print(list(c.execute('select id,marketplace,status,lastSyncedAt from orders')))"`.

## 3. Make the job due soon
1. Settings → Tracking → Check interval (hours) → 1. WorkManager keeps the previous period start, so the next run is (period start + 1 h), not 1 h from now. If the job is already more than an hour old it is due immediately.
2. Confirm the schedule: `adb shell dumpsys jobscheduler | Select-String -Pattern '#InboxSyncWorker#' -Context 0,12` and read `Minimum latency` (remaining wait) and `Network type` (needs a validated connection).
3. Close the app without force-stopping: press Home, then end the process the way Android would: `adb shell run-as com.example.pixelpackagetracker kill <pid from adb shell pidof com.example.pixelpackagetracker>`. `pidof` printing nothing confirms the process is gone.
4. Start a log capture: `adb logcat -c` then `adb logcat -v time WM-WorkerWrapper:I WM-SystemJobService:D *:S > wmlog.txt`. Leave the phone idle; the screen may be off.

## 4. Evidence that the background run happened
- Log: a `WM-WorkerWrapper ... Worker result SUCCESS for Work ... InboxSyncWorker` line (with the app process started by the job, not by you). `Worker result RETRY` means a failure that will be retried with backoff (up to three runs); `FAILURE` means it gave up until the next period.
- Data: copy the database again (step 2.2) and compare `orders.lastSyncedAt`; every saved order, including archived ones, gets a new value on each successful scan even when nothing changed. New orders and shipments, updated shipment statuses and history rows are also visible in the app.
- Gmail authorization in the background: SUCCESS plus new `lastSyncedAt` values proves it. If the log shows no run and the Reconnect Gmail notification (id 9911) appears, authorization needs the user: open Settings → Reconnect Gmail.
- Notification: only appears when a saved shipment's status changes during the run, the matching Settings → Notifications switch is on (or "Every tracking update"), and the Android permission is granted. A run with no status change posts nothing, so a notification cannot be forced without a real status change in your mailbox.

## 5. Restore
1. Settings → Tracking → Check interval → your original value (verify with the same `dumpsys` command).
2. Remove the temporary battery override if you changed it.

## What this does not prove
Reboot persistence, Doze and App Standby delays over a full day, and carrier or OEM battery policies. Check those by leaving the 8-hour interval in place for a day and comparing `lastSyncedAt` values the next morning.

## Emulator run of this procedure (2026-10-26)
Process killed, interval set to 1 h with about 4 minutes left on the existing period, no app UI open: the job started a new process and logged `Worker result SUCCESS ... InboxSyncWorker`. All 8 orders' `lastSyncedAt` moved from 19:09 to 20:10, and one order (Overstock) went Order Placed to Shipped from real mail. The interval was then restored to 8 h. The same Gmail account on a physical Pixel is still untested.
