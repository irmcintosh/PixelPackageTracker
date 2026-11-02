package com.example.pixelpackagetracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** WorkManager periodic jobs are best-effort, never real-time. */
object BackgroundSync {
 internal const val WORK = "gmail-inbox-background-sync"
 /** A failed run is retried with backoff this many times, then waits for the next period. */
 internal const val MAX_RUN_ATTEMPTS = 3
 /** Order notification ids sit above shipment ids (Room row ids) so the two never replace each other. */
 internal const val ORDER_NOTICE_BASE = 1_000_000
 fun schedule(context: Context, hours: String) {
  val interval = (hours.toLongOrNull() ?: 8L).coerceAtLeast(1L)
  val request = PeriodicWorkRequestBuilder<InboxSyncWorker>(interval, TimeUnit.HOURS)
   .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
   .build()
  WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
 }
 fun notificationsAllowed(context: Context): Boolean =
  Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
 /** Per-category switches decide; "Every tracking update" covers any status change. */
 internal fun shouldNotifyStatusChange(settings: TrackerSettings, status: String): Boolean = settings.notifyEvery || when (status) {
  "Delivered" -> settings.notifyDelivered
  "Out for Delivery" -> settings.notifyOut
  "Shipped", "In Transit" -> settings.notifyShipped
  else -> settings.notifyException
 }
 /** Shipped follows the Shipped switch and Cancelled the exception switch; Processing and Preparing for Shipment follow the order-progress switch. */
 internal fun shouldNotifyOrderStatusChange(settings: TrackerSettings, status: String): Boolean = settings.notifyEvery || when (status) {
  "Shipped" -> settings.notifyShipped
  "Cancelled" -> settings.notifyException
  else -> settings.notifyOrder
 }
 fun notify(context: Context, title: String, message: String, id: Int) {
  if (!notificationsAllowed(context)) return
  val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  manager.createNotificationChannel(NotificationChannel("shipment_updates", "Package updates", NotificationManager.IMPORTANCE_DEFAULT))
  val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification = NotificationCompat.Builder(context, "shipment_updates")
   .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(message)
   .setAutoCancel(true).setContentIntent(intent).build()
  NotificationManagerCompat.from(context).notify(id, notification)
 }
}

/** Everything the background worker does once messages are fetched; it imports verified records exactly like a manual scan. */
internal suspend fun syncOrdersFromMessages(db: PackageDatabase, preferences: SettingsRepository, messages: List<GmailPreview>): ScanRunSummary =
 TrackerVm(db, preferences).autoImportFromMessages(messages, reanalyze = false)

internal class StatusSnapshot(val orders: Map<Long, OrderRecord>, val shipments: Map<Long, ShipmentRecord>)

internal suspend fun statusSnapshot(db: PackageDatabase): StatusSnapshot =
 StatusSnapshot(db.orderDao().allOrders().associateBy { it.id }, db.orderDao().allShipments().associateBy { it.id })

internal class StatusNotice(val id: Int, val title: String, val message: String)

/** One notice per record whose stored status differs between the snapshots; new, deleted and unchanged records never notify. */
internal fun statusNotices(settings: TrackerSettings, before: StatusSnapshot, after: StatusSnapshot): List<StatusNotice> {
 val orders = after.orders.values.mapNotNull { order ->
  val previous = before.orders[order.id] ?: return@mapNotNull null
  if (previous.status == order.status || !BackgroundSync.shouldNotifyOrderStatusChange(settings, order.status)) null
  else StatusNotice(BackgroundSync.ORDER_NOTICE_BASE + order.id.toInt(), "Order updated", "${order.marketplace} #${order.orderNumber}: ${order.status}")
 }
 val shipments = after.shipments.values.mapNotNull { ship ->
  val previous = before.shipments[ship.id] ?: return@mapNotNull null
  if (previous.status == ship.status || !BackgroundSync.shouldNotifyStatusChange(settings, ship.status)) null
  else StatusNotice(ship.id.hashCode(), "Shipment updated", "${ship.title}: ${ship.status}")
 }
 return orders + shipments
}

/** Scans from the foreground and from WorkManager never overlap, so a change is announced by exactly one of them. */
private val statusNoticeLock = kotlinx.coroutines.sync.Mutex()

/** Runs a scan's database work and posts a notice for each order or shipment status it actually changed. */
internal suspend fun <T> withStatusNotifications(db: PackageDatabase, settings: suspend () -> TrackerSettings, post: (StatusNotice) -> Unit, block: suspend () -> T): T =
 statusNoticeLock.withLock {
  val before = statusSnapshot(db)
  try { block() } finally {
   // Changes already committed are announced even if the caller was cancelled mid-scan.
   withContext(NonCancellable) { runCatching { statusNotices(settings(), before, statusSnapshot(db)).forEach(post) } }
  }
 }

internal suspend fun <T> withStatusNotifications(context: Context, block: suspend () -> T): T {
 val app = context.applicationContext
 return withStatusNotifications(PackageDatabase.get(app), { SettingsRepository(app).settings.first() }, { BackgroundSync.notify(app, it.title, it.message, it.id) }, block)
}

class InboxSyncWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
 override suspend fun doWork(): Result {
  val context = applicationContext
  val token = when (val auth = requestGmailToken(context)) {
   is GmailTokenResult.Token -> auth.value
   // A transient Google Play services failure is retried; a missing or expired grant needs the user, so retrying cannot help.
   GmailTokenResult.Failed -> return if (runAttemptCount + 1 < BackgroundSync.MAX_RUN_ATTEMPTS) Result.retry() else Result.failure()
   GmailTokenResult.NeedsAuthorization -> {
    val prefs = context.getSharedPreferences("gmail_sync_alerts", Context.MODE_PRIVATE)
    val last = prefs.getLong("reconnect_alert", 0L)
    if (BackgroundSync.notificationsAllowed(context) && System.currentTimeMillis() - last > TimeUnit.DAYS.toMillis(1)) {
     BackgroundSync.notify(context, "Reconnect Gmail", "Open Pixel Package Tracker to authorize Inbox checks.", 9911)
     prefs.edit().putLong("reconnect_alert", System.currentTimeMillis()).apply()
    }
    return Result.success()
   }
  }
  return try {
   val db = PackageDatabase.get(context)
   val messages = scanGmailPreviews(token) { }
   // Background checks never reanalyze messages from older parser versions; that stays a manual action.
   withStatusNotifications(context) { syncOrdersFromMessages(db, SettingsRepository(context), messages) }
   Result.success()
  } catch (e: kotlinx.coroutines.CancellationException) { throw e }
  catch (e: Exception) { if (runAttemptCount + 1 < BackgroundSync.MAX_RUN_ATTEMPTS) Result.retry() else Result.failure() }
 }
}

/** Local maintenance: deletes only records whose archive deadline has passed. */
object StorageMaintenance {
 private const val WORK = "archived-order-retention"
 fun schedule(context: Context) {
  val request = PeriodicWorkRequestBuilder<ArchiveCleanupWorker>(1, TimeUnit.DAYS).build()
  WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
 }
 suspend fun deleteExpired(context: Context): Int {
  val setting = SettingsRepository(context).settings.first()
  val days = setting.archivedRetentionDays.toLongOrNull() ?: return 0
  val cutoff = System.currentTimeMillis() - days.coerceAtLeast(1) * TimeUnit.DAYS.toMillis(1)
  val db = PackageDatabase.get(context)
  return db.withTransaction {
   val dao = db.orderDao()
   // Retain minimal markers to prevent automatic reimport of intentionally removed orders.
   // Archived orders imported before the migration have no archivedAt and are not purged.
   val candidates = dao.expiredArchivedKeys(cutoff)
   candidates.forEach { dao.markDeleted(DeletedOrderMarker(marketplace = it.marketplace, orderNumber = it.orderNumber)) }
   dao.deleteExpiredArchives(cutoff)
  }
 }
 fun cleanTemporary(context: Context): Long {
  // Only application-owned disposable cache; never Room database, preferences or message IDs.
  val root = context.cacheDir
  fun clear(dir: java.io.File): Long {
   var freed = 0L
   dir.listFiles()?.forEach { file ->
    if(file.isDirectory) freed += clear(file)
    else { val size = file.length(); if(file.delete()) freed += size }
   }
   return freed
  }
  return clear(root)
 }
 fun storageBytes(context: Context): Long {
  val root = context.getDatabasePath("packages.db")
  return listOf(root, java.io.File(root.path + "-wal"), java.io.File(root.path + "-shm")).sumOf { if(it.exists()) it.length() else 0L }
 }
}

class ArchiveCleanupWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
 override suspend fun doWork(): Result = try {
  StorageMaintenance.deleteExpired(applicationContext)
  Result.success()
 } catch (_: Exception) { Result.retry() }
}
