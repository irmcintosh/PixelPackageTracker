package com.example.pixelpackagetracker

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Four-stage order lifecycle, handoff archiving and order/shipment status notifications, against Room and the real scan code.
@RunWith(AndroidJUnit4::class)
class OrderLifecycleNotificationTest {
 private lateinit var context: Context
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private lateinit var prefs: SettingsRepository
 private val dao get() = db.orderDao()
 private val simpli = "Simpli Soda <orders@simplisoda.com>"
 private val number = "28855918"
 private val tracking = "38429466112"
 private val posted = mutableListOf<StatusNotice>()

 @Before fun setUp() {
  context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  prefs = SettingsRepository(context)
  vm = TrackerVm(db, prefs)
 }

 @After fun tearDown() { db.close() }

 private fun mail(id: String, subject: String, body: String = "") = GmailPreview(simpli, subject, "Mon, 15 Jan 2024 10:00:00 +0000", "", "t$id", body, id)
 private val processingMail = mail("proc", "Order #$number update", "Your order is being processed.")
 private val trackingOnlyMail = mail("trk", "Order #$number tracking", "Putting the tracking number below into FedEx: $tracking")
 private val dispatchMail = mail("ship", "Your order #$number has shipped", "Your order has shipped via FedEx! Putting the tracking number below into FedEx: $tracking")
 private val cancelMail = mail("cxl", "Your order #$number has been cancelled")
 private suspend fun seed(status: String = "Order Placed") = dao.getOrder(dao.insertOrder(OrderRecord(marketplace = "Simpli Soda", orderNumber = number, name = "Soda", status = status)))!!
 private suspend fun status(id: Long) = dao.getOrder(id)!!.status

 private suspend fun <T> notified(settings: TrackerSettings = TrackerSettings(), block: suspend () -> T): T {
  posted.clear()
  return withStatusNotifications(db, { settings }, { posted += it }, block)
 }
 private fun messages() = posted.map { it.message }

 @Test fun aTrackingNumberAloneMeansPreparingForShipmentNotShippedAndNeverArchives() = runBlocking<Unit> {
  val order = seed("Processing")
  vm.updateOrdersFromEmails(listOf(trackingOnlyMail), listOf(order))
  assertEquals("Preparing for Shipment", status(order.id))
  assertEquals("Label Created", dao.getShipments(order.id).single().status)
  assertEquals(1, dao.allTrackingNumbers().size)
  assertFalse(dao.getOrder(order.id)!!.archived)
  assertEquals("3 of 4 · Preparing for Shipment", purchaseProgress(status(order.id)).summary)
 }

 @Test fun aManualShipmentWithATrackingNumberIsPreparingAndANumberlessOneChangesNothing() = runBlocking<Unit> {
  val order = seed("Processing")
  assertEquals(SaveOutcome.Saved, vm.addShipmentToOrder(order.id, ShipmentDraft(carrier = "UPS")))
  assertEquals("Processing", status(order.id))
  val other = dao.getOrder(dao.insertOrder(OrderRecord(marketplace = "Shop", orderNumber = "Z-1", name = "Z", status = "Processing")))!!
  assertEquals(SaveOutcome.Saved, vm.addShipmentToOrder(other.id, ShipmentDraft(number = "1Z999AA10123456784", carrier = "UPS")))
  assertEquals("Preparing for Shipment", status(other.id))
  assertFalse(dao.getOrder(other.id)!!.archived)
 }

 @Test fun confirmedDispatchShipsAndArchivesTheOrderWhileItsShipmentStaysTracked() = runBlocking<Unit> {
  val order = seed("Processing")
  vm.updateOrdersFromEmails(listOf(trackingOnlyMail), listOf(order))
  assertFalse(dao.getOrder(order.id)!!.archived)
  vm.updateOrdersFromEmails(listOf(trackingOnlyMail, dispatchMail), listOf(dao.getOrder(order.id)!!))
  assertEquals("Shipped", status(order.id))
  assertTrue(dao.getOrder(order.id)!!.archived)
  val ship = dao.getShipments(order.id).single()
  assertEquals("In Transit", ship.status)
  assertFalse(ship.archived)
  assertEquals(1, dao.allTrackingNumbers().size)
 }

 @Test fun aShippedOrderWithoutShipmentInformationStaysActiveUntilAShipmentIsSaved() = runBlocking<Unit> {
  val order = seed("Processing")
  assertEquals(SaveOutcome.Saved, vm.setOrderStatus(order.id, "Shipped"))
  val shipped = dao.getOrder(order.id)!!
  assertFalse(shipped.archived)
  assertTrue(needsShipmentInformation(shipped, dao.getShipments(order.id), dao.allTrackingNumbers()))
  assertEquals(SaveOutcome.Saved, vm.addShipmentToOrder(order.id, ShipmentDraft(number = "1Z999AA10123456784", carrier = "UPS")))
  assertTrue(dao.getOrder(order.id)!!.archived)
  assertEquals(1, dao.getShipments(order.id).size)
 }

 @Test fun aForegroundScanNotifiesEachOrderStatusStepAndARepeatNotifiesNothing() = runBlocking<Unit> {
  val order = seed()
  notified { vm.autoImportFromMessages(listOf(processingMail), reanalyze = true) }
  assertEquals(listOf("Simpli Soda #$number: Processing"), messages())
  notified { vm.autoImportFromMessages(listOf(processingMail), reanalyze = true) }
  assertTrue(messages().toString(), posted.isEmpty())
  notified { vm.autoImportFromMessages(listOf(processingMail, trackingOnlyMail), reanalyze = true) }
  assertEquals(listOf("Simpli Soda #$number: Preparing for Shipment"), messages())
  notified { vm.autoImportFromMessages(listOf(processingMail, trackingOnlyMail, dispatchMail), reanalyze = true) }
  assertEquals(messages().toString(), 2, posted.size)
  assertTrue(messages().contains("Simpli Soda #$number: Shipped"))
  assertTrue(messages().any { it.endsWith(": In Transit") })
  notified { vm.autoImportFromMessages(listOf(processingMail, trackingOnlyMail, dispatchMail), reanalyze = true) }
  assertTrue(messages().toString(), posted.isEmpty())
  assertEquals("Shipped", status(order.id))
 }

 @Test fun aBackgroundScanUsesTheSamePathAndNotifiesAnOrderStatusChange() = runBlocking<Unit> {
  val order = seed()
  notified { syncOrdersFromMessages(db, prefs, listOf(processingMail)) }
  assertEquals(listOf("Simpli Soda #$number: Processing"), messages())
  notified { syncOrdersFromMessages(db, prefs, listOf(processingMail)) }
  assertTrue(posted.isEmpty())
  assertEquals("Processing", status(order.id))
 }

 @Test fun aCancelledOrderNotifiesAndUnrelatedMailNeverDoes() = runBlocking<Unit> {
  val order = seed("Processing")
  val unrelated = GmailPreview("Someone <someone@example.com>", "Hello", "Mon, 15 Jan 2024 10:00:00 +0000", "", "tx", "Order #$number is mentioned but this is not a retailer notice.", "x1")
  notified { vm.autoImportFromMessages(listOf(unrelated), reanalyze = true) }
  assertTrue(posted.isEmpty())
  assertEquals("Processing", status(order.id))
  notified { vm.autoImportFromMessages(listOf(cancelMail), reanalyze = true) }
  assertEquals(listOf("Simpli Soda #$number: Cancelled"), messages())
  assertEquals("Cancelled", status(order.id))
 }

 @Test fun notificationSwitchesDecideWhichStatusChangesAreAnnounced() = runBlocking<Unit> {
  seed()
  notified(TrackerSettings(notifyOrder = false)) { vm.autoImportFromMessages(listOf(processingMail), reanalyze = true) }
  assertTrue(posted.isEmpty())
  assertEquals("Processing", dao.allOrders().single().status)
  notified(TrackerSettings(notifyException = false)) { vm.autoImportFromMessages(listOf(cancelMail), reanalyze = true) }
  assertTrue(posted.isEmpty())
  assertEquals("Cancelled", dao.allOrders().single().status)
 }

 @Test fun aNoticeIsActuallyPostedToTheSystemWhenPermissionIsGranted() = runBlocking<Unit> {
  assumeTrue(BackgroundSync.notificationsAllowed(context))
  val order = seed()
  val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  val id = BackgroundSync.ORDER_NOTICE_BASE + order.id.toInt()
  manager.cancel(id)
  try {
   withStatusNotifications(db, { TrackerSettings() }, { BackgroundSync.notify(context, it.title, it.message, it.id) }) {
    vm.autoImportFromMessages(listOf(processingMail), reanalyze = true)
   }
   val active = manager.activeNotifications.singleOrNull { it.id == id }
   assertTrue("notification posted", active != null)
   assertEquals("Order updated", active!!.notification.extras.getString("android.title"))
   assertEquals("shipment_updates", active.notification.channelId)
  } finally { manager.cancel(id) }
 }
}
