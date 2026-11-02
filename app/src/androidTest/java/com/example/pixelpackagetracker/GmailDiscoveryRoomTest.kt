package com.example.pixelpackagetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Gmail discovery without a confirmation, standalone shipments, carrier/title persistence and the 10 -> 11 migration, against Room.
@RunWith(AndroidJUnit4::class)
class GmailDiscoveryRoomTest {
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private val dao get() = db.orderDao()
 private val ebay = "eBay <ebay@ebay.com>"
 private val number = "18-15293-46910"

 @Before fun setUp() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  vm = TrackerVm(db, SettingsRepository(context))
 }

 @After fun tearDown() { db.close() }

 private fun mail(id: String, sender: String, subject: String, body: String = "", date: String = "Mon, 15 Jan 2024 10:00:00 +0000") =
  GmailPreview(sender, subject, date, "", "t$id", body, id)
 private val orderUpdate = mail("upd", ebay, "Order update: Google Pixel C 64GB...", "Order #$number\nEstimated delivery: Thu, Oct 22 - Wed, Oct 28", "Sun, 14 Jan 2024 10:00:00 +0000")
 private val outForDelivery = mail("ofd", ebay, "OUT FOR DELIVERY: Google Pixel C 64GB...", "Order #$number\nEstimated delivery: Thu, Oct 22 - Wed, Oct 28\nYour item is out for delivery with USPS.")
 private fun groups(vararg emails: GmailPreview) = groupedScanResults(emails.toList(), emptyList(), emptyList(), emptyList())
 private suspend fun savedGroups(vararg emails: GmailPreview) = groupedScanResults(emails.toList(), dao.allTrackingNumbers(), dao.allShipments(), dao.allOrders())
 private suspend fun progress(orderId: Long): PurchaseProgress {
  val order = dao.getOrder(orderId)!!
  return purchaseProgress(order.status, !mayDeriveOrderStatus(dao.latestChangeForField(orderId, "Order status")))
 }

 @Test fun aRetailerOutForDeliveryNoticeCreatesTheOrderItsShipmentStatusAndCarrierWithoutAConfirmationOrTrackingNumber() = runBlocking<Unit> {
  val group = groups(orderUpdate, outForDelivery).single()
  assertTrue(group.establishesOrder)
  assertTrue(vm.importScanGroup(group).startsWith("Imported order #$number"))
  val order = dao.allOrders().single()
  assertEquals("eBay", order.marketplace)
  assertEquals("Google Pixel C 64GB", order.name)
  val shipment = dao.getShipments(order.id).single()
  assertEquals("Out for Delivery", shipment.status)
  assertEquals("USPS", shipment.carrier)
  assertEquals("eBay", shipment.title)
  assertTrue(dao.allTrackingNumbers().isEmpty())
  assertEquals("Shipped", order.status)
  assertEquals("4 of 4 · Shipped", progress(order.id).summary)
  assertEquals(setOf("upd", "ofd"), dao.allProcessed().map { it.messageId }.toSet())
 }

 @Test fun repeatedScansAndImportsNeverDuplicateTheOrderShipmentOrHistory() = runBlocking<Unit> {
  val group = groups(orderUpdate, outForDelivery).single()
  assertTrue(vm.importScanGroup(group).startsWith("Imported"))
  assertTrue(vm.importScanGroup(group).startsWith("Existing order"))
  val order = dao.allOrders().single()
  repeat(2) { assertEquals(ScanOutcome.UNCHANGED, vm.updateOrdersFromEmails(listOf(orderUpdate, outForDelivery), listOf(dao.getOrder(order.id)!!), true).results.single().outcome) }
  assertEquals(1, dao.allOrders().size)
  assertEquals(1, dao.getShipments(order.id).size)
  assertEquals(2, dao.allProcessed().size)
  // Only the approximate purchase date label is recorded for a new order; repeated scans add nothing.
  assertEquals(listOf("Placed date"), dao.changesForOrder(order.id).map { it.field })
 }

 @Test fun anUnrelatedEmailThatMentionsAnOrderNumberCannotCreateAnOrder() = runBlocking<Unit> {
  val unrelated = listOf(
   mail("cs", ebay, "Re: your question about order $number", "We are looking into your request.\nOrder #$number"),
   mail("one", ebay, "Your order $number", "Order #$number\nThanks for shopping with us."),
   mail("promo", ebay, "Save 20% on your next purchase after order $number", "Order #$number\nEstimated delivery soon. Sale ends Friday."),
   mail("fwd", "Someone <someone@gmail.com>", "OUT FOR DELIVERY: Google Pixel C 64GB", "Order #$number\nEstimated delivery: Thu, Oct 22"))
  unrelated.forEach { email ->
   assertTrue(email.subject, groups(email).none { it.establishesOrder })
   val outcome = vm.importScanGroup(ScanGroup("review:${email.messageId}", "eBay", number, null, null, listOf(email), null))
   assertTrue("${email.subject}: $outcome", outcome.startsWith("Review required"))
  }
  assertTrue(dao.allOrders().isEmpty() && dao.allShipments().isEmpty() && dao.allProcessed().isEmpty())
 }

 @Test fun anExistingOrderReceivesTheShippingUpdateKeepsItsManualTitleAndStatusAndLearnsTheCarrier() = runBlocking<Unit> {
  val id = dao.insertOrder(OrderRecord(marketplace = "eBay", orderNumber = number, name = "My Pixel", status = "Order Placed"))
  assertEquals(SaveOutcome.Saved, vm.setOrderStatus(id, "Processing"))
  val shipmentId = dao.insertShipment(ShipmentRecord(orderId = id, status = "In Transit"))
  val result = vm.updateOrdersFromEmails(listOf(orderUpdate, outForDelivery), listOf(dao.getOrder(id)!!), true).results.single()
  assertEquals(ScanOutcome.UPDATED, result.outcome)
  assertEquals("My Pixel", dao.getOrder(id)!!.name)
  assertEquals("Processing", dao.getOrder(id)!!.status)
  assertEquals("Out for Delivery", dao.getShipment(shipmentId)!!.status)
  assertEquals("USPS", dao.getShipment(shipmentId)!!.carrier)
  assertEquals("2 of 4 · Processing", progress(id).summary)
  assertEquals(null, progress(id).manualNote)
 }

 @Test fun aUserTypedTitleSurvivesCreationAndLaterGmailUpdates() = runBlocking<Unit> {
  assertEquals(SaveOutcome.Saved, vm.createOrderFromEmail(outForDelivery, "eBay", number, "Ebay 46910"))
  val order = dao.allOrders().single()
  assertEquals("Ebay 46910", order.name)
  vm.updateOrdersFromEmails(listOf(orderUpdate, outForDelivery), listOf(order), true)
  assertEquals("Ebay 46910", dao.getOrder(order.id)!!.name)
  assertEquals("USPS", dao.getShipments(order.id).single().carrier)
 }

 @Test fun anOlderEmailNeverLowersANewerShipmentStatus() = runBlocking<Unit> {
  vm.importScanGroup(groups(orderUpdate, outForDelivery).single())
  val order = dao.allOrders().single()
  val older = mail("old", ebay, "Order update: Google Pixel C 64GB shipped", "Order #$number\nShipped with USPS", "Fri, 12 Jan 2024 10:00:00 +0000")
  vm.updateOrdersFromEmails(listOf(older), listOf(order), true)
  assertEquals("Out for Delivery", dao.getShipments(order.id).single().status)
 }

 @Test fun aCarrierShipmentWithNoOrderBecomesAStandaloneShipmentUpdatedInPlaceAndNeverDuplicated() = runBlocking<Unit> {
  val fedex = "FedEx <TrackingUpdates@fedex.com>"
  val onItsWay = mail("fx1", fedex, "Your package is on its way", "Tracking number: 38429466112")
  val outForDelivery = mail("fx2", fedex, "Your package is out for delivery", "Tracking number: 38429466112", "Tue, 16 Jan 2024 08:00:00 +0000")
  val first = groups(onItsWay).single()
  assertEquals("shipment:new:38429466112", first.key)
  assertTrue(vm.importScanGroup(first).startsWith("Imported standalone shipment FedEx 38429466112"))
  val shipment = dao.allShipments().single()
  assertEquals(null, shipment.orderId)
  assertEquals("FedEx", shipment.carrier)
  assertEquals("In Transit", shipment.status)
  assertTrue(dao.allOrders().isEmpty() && dao.allProcessed().isEmpty())

  val saved = savedGroups(outForDelivery).single()
  assertEquals("shipment:saved:${shipment.id}", saved.key)
  assertTrue(vm.importScanGroup(saved).startsWith("Existing shipment FedEx 38429466112 updated"))
  assertEquals("Out for Delivery", dao.getShipment(shipment.id)!!.status)

  repeat(2) {
   assertTrue(vm.importScanGroup(savedGroups(onItsWay, outForDelivery).single()).contains("no duplicate created"))
  }
  assertEquals(1, dao.allShipments().size)
  assertEquals(1, dao.allTrackingNumbers().size)
  assertEquals("Out for Delivery", dao.getShipment(shipment.id)!!.status)
  assertEquals(setOf("fx1", "fx2"), dao.observeEvents(shipment.id).first().map { it.sourceMessageId }.toSet())
  assertEquals(2, dao.observeEvents(shipment.id).first().size)
 }

 @Test fun theUpdatePathRefreshesASavedStandaloneShipmentButNeverCreatesOne() = runBlocking<Unit> {
  val fedex = "FedEx <TrackingUpdates@fedex.com>"
  val onItsWay = mail("fx1", fedex, "Your package is on its way", "Tracking number: 38429466112")
  val delivered = mail("fx3", fedex, "Your package was delivered", "Tracking number: 38429466112", "Wed, 17 Jan 2024 08:00:00 +0000")
  vm.updateOrdersFromEmails(listOf(onItsWay), emptyList(), true)
  assertTrue(dao.allShipments().isEmpty())
  vm.importScanGroup(groups(onItsWay).single())
  vm.updateOrdersFromEmails(listOf(onItsWay, delivered), emptyList(), true)
  assertEquals("Delivered", dao.allShipments().single().status)
  assertEquals(1, dao.allShipments().size)
 }

 @Test fun aCarrierCanBeSavedOnATracklessShipmentWithoutInventingATrackingNumber() = runBlocking<Unit> {
  val id = dao.insertOrder(OrderRecord(marketplace = "eBay", orderNumber = number, name = "Pixel", status = "Shipped"))
  val shipmentId = dao.insertShipment(ShipmentRecord(orderId = id, status = "In Transit"))
  assertEquals(SaveOutcome.Saved, vm.editShipment(shipmentId, null, "", "USPS", null))
  assertEquals("USPS", dao.getShipment(shipmentId)!!.carrier)
  assertTrue(dao.allTrackingNumbers().isEmpty())
  assertTrue(vm.editShipment(shipmentId, null, "", "", null) is SaveOutcome.Blocked)
  assertEquals(SaveOutcome.Saved, vm.editShipment(shipmentId, null, "9400111899223344556677", "USPS", null))
  assertEquals(1, dao.allTrackingNumbers().size)
  assertTrue(vm.editShipment(shipmentId, dao.allTrackingNumbers().single().id, "", "USPS", null) is SaveOutcome.Blocked)
 }

 @Test fun migrationFrom10To11KeepsEveryRowAndBackfillsTheCarrier() = runBlocking<Unit> {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val name = "migration-test.db"
  context.deleteDatabase(name)
  val built = Room.databaseBuilder(context, PackageDatabase::class.java, name).allowMainThreadQueries().build()
  val orderId = built.orderDao().insertOrder(OrderRecord(marketplace = "eBay", orderNumber = number, name = "Pixel", status = "Shipped"))
  val shipmentId = built.orderDao().insertShipment(ShipmentRecord(orderId = orderId, status = "In Transit", title = "eBay"))
  built.orderDao().insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = shipmentId, number = "9400111899223344556677", carrier = "USPS"))
  val legacyId = built.orderDao().insertShipment(ShipmentRecord(orderId = orderId, status = "Out for Delivery", title = "eBay \u00b7 USPS"))
  val plainId = built.orderDao().insertShipment(ShipmentRecord(orderId = orderId, status = "In Transit", title = "eBay"))
  built.orderDao().insertEvent(ShipmentTrackingEventRecord(shipmentId = shipmentId, description = "Seen", status = "In Transit"))
  built.orderDao().markProcessed(ProcessedGmailMessage(messageId = "m1", orderId = orderId))
  built.orderDao().recordChange(orderId, "Order status", "Order Placed", "Shipped", "m1", "", 1L)
  built.close()
  val raw = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
  raw.execSQL("ALTER TABLE shipments DROP COLUMN carrier")
  raw.execSQL("ALTER TABLE shipments DROP COLUMN shippedAt")
  raw.execSQL("ALTER TABLE shipment_tracking_events DROP COLUMN sourceMessageId")
  raw.execSQL("ALTER TABLE shipments DROP COLUMN estimatedDeliveryEndAt")
  raw.execSQL("ALTER TABLE shipments DROP COLUMN estimatedDeliverySourceMessageId")
  raw.execSQL("ALTER TABLE shipments DROP COLUMN estimatedDeliverySourceAt")
  raw.version = 10
  raw.close()
  val migrated = Room.databaseBuilder(context, PackageDatabase::class.java, name).addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13).allowMainThreadQueries().build()
  try {
   val d = migrated.orderDao()
   assertEquals(1, d.allOrders().size)
   val shipment = d.getShipments(orderId).first { it.id == shipmentId }
   assertEquals(3, d.getShipments(orderId).size)
   assertEquals("USPS", d.getShipment(legacyId)!!.carrier)
   assertEquals("Out for Delivery", d.getShipment(legacyId)!!.status)
   assertEquals("", d.getShipment(plainId)!!.carrier)
   assertEquals("USPS", shipment.carrier)
   assertEquals("eBay", shipment.title)
   assertEquals(1, d.allTrackingNumbers().size)
   assertEquals("", d.observeEvents(shipmentId).first().single().sourceMessageId)
   assertEquals(1, d.allProcessed().size)
   assertEquals(1, d.changesForOrder(orderId).size)
   assertFalse(d.observeEvents(shipmentId).first().isEmpty())
  } finally { migrated.close(); context.deleteDatabase(name) }
 }
}
