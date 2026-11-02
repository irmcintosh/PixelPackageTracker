package com.example.pixelpackagetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Order -> shipment handoff, retention safety, standalone association, approximate dates, order notices and automatic import, against Room.
@RunWith(AndroidJUnit4::class)
class OrderShipmentWorkflowRoomTest {
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private val dao get() = db.orderDao()
 private val simpli = "Simpli Soda <orders@simplisoda.com>"
 private val number = "28855918"
 private val tracking = "38429466112"

 @Before fun setUp() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  vm = TrackerVm(db, SettingsRepository(context))
 }

 @After fun tearDown() { db.close() }

 private fun mail(id: String, subject: String, body: String, date: String = "Mon, 15 Jan 2024 10:00:00 +0000", sender: String = simpli) =
  GmailPreview(sender, subject, date, "", "t$id", body, id)
 private suspend fun seed(status: String = "Order Placed", archived: Boolean = false): OrderRecord {
  val id = dao.insertOrder(OrderRecord(marketplace = "Simpli Soda", orderNumber = number, name = "Root Beer", status = status))
  if(archived) dao.archiveOrder(id, true)
  return dao.getOrder(id)!!
 }
 private suspend fun fresh(order: OrderRecord) = dao.getOrder(order.id)!!
 private val shippedMail get() = mail("ship", "Your order #$number has shipped", "Order number: $number\nYour order has shipped via FedEx.\nTracking number: $tracking")

 // ---------- handoff ----------

 @Test fun aShippedOrderStaysActiveUntilShipmentDetailsAreSavedThenArchivesWhileTheShipmentStaysActive() = runBlocking<Unit> {
  val order = seed()
  vm.setOrderStatus(order.id, "Shipped")
  assertFalse(fresh(order).archived)
  assertTrue(needsShipmentInformation(fresh(order), dao.getShipments(order.id), dao.allTrackingNumbers()))
  assertEquals(SaveOutcome.Saved, vm.addShipmentToOrder(order.id, ShipmentDraft(number = tracking, carrier = "FedEx", status = "In Transit", shippedDate = "2024-01-10", confirmed = true)))
  val archived = fresh(order)
  assertTrue(archived.archived)
  assertEquals("Shipped", archived.status)
  val shipment = dao.getShipments(order.id).single()
  assertFalse(shipment.archived)
  assertEquals(order.id, shipment.orderId)
  assertEquals(tracking, dao.allTrackingNumbers().single().number)
  assertTrue(shipment.shippedAt != null)
  assertTrue("Order archive" in dao.changesForOrder(order.id).map { it.field })
 }

 @Test fun savingAShipmentNeverArchivesAnOrderThatHasNotShipped() = runBlocking<Unit> {
  val order = seed()
  vm.addShipmentToOrder(order.id, ShipmentDraft(carrier = "USPS"))
  assertFalse(fresh(order).archived)
  assertEquals("Order Placed", fresh(order).status)
 }

 @Test fun aShipmentWithMovementMovesTheOrderToShippedAndCompletesHandoff() = runBlocking<Unit> {
  val order = seed()
  vm.addShipmentToOrder(order.id, ShipmentDraft(number = tracking, carrier = "FedEx", status = "In Transit", confirmed = true))
  assertEquals("Shipped", fresh(order).status)
  assertTrue(fresh(order).archived)
 }

 @Test fun anEmptyShipmentDoesNotCompleteHandoffAndIsFilledInByTheEnteredDetails() = runBlocking<Unit> {
  val order = seed(status = "Shipped")
  dao.insertShipment(ShipmentRecord(orderId = order.id))
  vm.setOrderStatus(order.id, "Processing")
  vm.setOrderStatus(order.id, "Shipped")
  assertFalse(fresh(order).archived)
  vm.addShipmentToOrder(order.id, ShipmentDraft(number = tracking, carrier = "FedEx", confirmed = true))
  assertEquals(1, dao.getShipments(order.id).size)
  assertTrue(fresh(order).archived)
 }

 @Test fun shipmentProgressAfterHandoffNeverChangesOrFreesTheArchivedOrder() = runBlocking<Unit> {
  val order = seed()
  vm.addShipmentToOrder(order.id, ShipmentDraft(number = tracking, carrier = "FedEx", status = "In Transit", confirmed = true))
  val sid = dao.getShipments(order.id).single().id
  vm.setShipmentStatus(sid, "Out for Delivery")
  vm.setShipmentStatus(sid, "Delivered")
  assertEquals("Shipped", fresh(order).status)
  assertTrue(fresh(order).archived)
  assertTrue(dao.getShipment(sid)!!.archived)
  assertEquals("Delivered", dao.getShipment(sid)!!.status)
 }

 // ---------- retention ----------

 @Test fun expiringAnArchivedOrderNeverCascadesIntoShipmentsThatAreNotIndependentlyExpired() = runBlocking<Unit> {
  val order = seed(status = "Shipped")
  dao.archiveOrder(order.id, true, 1L)
  val sid = dao.insertShipment(ShipmentRecord(orderId = order.id, status = "In Transit", statusUpdatedAt = 1L))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = tracking, carrier = "FedEx"))
  dao.insertEvent(ShipmentTrackingEventRecord(shipmentId = sid, description = "Seen"))
  assertTrue(dao.expiredArchivedKeys(100L).isEmpty())
  assertEquals(0, dao.deleteExpiredArchives(100L))
  assertEquals(1, dao.allOrders().size); assertEquals(1, dao.allShipments().size); assertEquals(1, dao.allTrackingNumbers().size)
  // An archived shipment that is still recent also protects its order.
  dao.updateShipment(dao.getShipment(sid)!!.copy(archived = true, statusUpdatedAt = 1000L))
  assertEquals(0, dao.deleteExpiredArchives(100L))
  assertEquals(1, dao.allShipments().size)
  // Once every shipment is archived and past the cutoff the order expires together with them.
  dao.updateShipment(dao.getShipment(sid)!!.copy(statusUpdatedAt = 1L))
  assertEquals(1, dao.expiredArchivedKeys(100L).size)
  assertEquals(1, dao.deleteExpiredArchives(100L))
  assertTrue(dao.allOrders().isEmpty() && dao.allShipments().isEmpty())
 }

 @Test fun anArchivedOrderWithoutShipmentsStillExpires() = runBlocking<Unit> {
  val order = seed(status = "Cancelled")
  dao.archiveOrder(order.id, true, 1L)
  assertEquals(1, dao.deleteExpiredArchives(100L))
 }

 // ---------- standalone shipments and additional shipments ----------

 private suspend fun standalone(): Long {
  val id = dao.insertShipment(ShipmentRecord(orderId = null, status = "In Transit", carrier = "FedEx", title = "Loose parcel"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = id, number = tracking, carrier = "FedEx"))
  return id
 }

 @Test fun aStandaloneShipmentIsAssociatedWithAnActiveOrderWhenAnEmailTiesTheTrackingNumberToTheOrderNumber() = runBlocking<Unit> {
  val order = seed()
  val sid = standalone()
  vm.updateOrdersFromEmails(listOf(shippedMail), listOf(order))
  assertEquals(order.id, dao.getShipment(sid)!!.orderId)
  assertEquals(1, dao.allShipments().size); assertEquals(1, dao.allTrackingNumbers().size)
  assertEquals("Shipped", fresh(order).status)
  assertTrue(fresh(order).archived)
  assertFalse(dao.getShipment(sid)!!.archived)
 }

 @Test fun aStandaloneShipmentIsAssociatedWithAnArchivedOrderWithoutReactivatingIt() = runBlocking<Unit> {
  val order = seed(status = "Shipped", archived = true)
  val sid = standalone()
  vm.updateOrdersFromEmails(listOf(shippedMail), listOf(order))
  assertEquals(order.id, dao.getShipment(sid)!!.orderId)
  assertTrue(fresh(order).archived)
  assertEquals(1, dao.allOrders().size)
  // Nothing is created or changed by a repeat scan.
  vm.updateOrdersFromEmails(listOf(shippedMail), listOf(order))
  assertEquals(1, dao.allShipments().size)
 }

 @Test fun aStandaloneShipmentCanBeLinkedManuallyToAnArchivedOrder() = runBlocking<Unit> {
  val order = seed(status = "Shipped", archived = true)
  val sid = standalone()
  assertEquals(SaveOutcome.Saved, vm.linkShipmentToOrder(sid, order.id))
  assertEquals(order.id, dao.getShipment(sid)!!.orderId)
  assertTrue(fresh(order).archived)
 }

 @Test fun anAdditionalPackageOnAnArchivedOrderAddsAShipmentWithoutDuplicatingOrReactivatingTheOrder() = runBlocking<Unit> {
  val order = seed(status = "Shipped", archived = true)
  val first = dao.insertShipment(ShipmentRecord(orderId = order.id, status = "In Transit", carrier = "FedEx"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = first, number = tracking, carrier = "FedEx"))
  val second = mail("pkg2", "Your order #$number has shipped (package 2 of 2)", "Order number: $number\nPackage 2 of 2 has shipped via UPS.\nTracking number: 1Z999AA10123456784")
  vm.updateOrdersFromEmails(listOf(second), listOf(order))
  assertEquals(1, dao.allOrders().size)
  assertEquals(2, dao.getShipments(order.id).size)
  assertTrue(fresh(order).archived)
  assertEquals(setOf(tracking, "1Z999AA10123456784"), dao.allTrackingNumbers().map { it.number }.toSet())
 }

 // ---------- dates and order notices ----------

 @Test fun anInferredPurchaseDateIsLabeledApproximateAndReplacedByAnExplicitOne() = runBlocking<Unit> {
  val order = seed()
  val notice = mail("n1", "Order update", "Order number: $number\nYour order is being processed.", "Wed, 10 Jan 2024 08:00:00 +0000")
  val later = mail("n2", "Order update", "Order number: $number\nYour order is still being processed.", "Fri, 12 Jan 2024 08:00:00 +0000")
  vm.updateOrdersFromEmails(listOf(later, notice), listOf(order))
  assertEquals(parseMailDate("Wed, 10 Jan 2024 08:00:00 +0000"), fresh(order).placedAt)
  assertTrue(dao.latestChangeForField(order.id, "Placed date")!!.newValue.endsWith(APPROXIMATE_DATE_SUFFIX))
  val confirmation = mail("c1", "Order #$number confirmed", "Order number: $number\nOrder date: January 3, 2024", "Wed, 03 Jan 2024 09:00:00 +0000")
  vm.updateOrdersFromEmails(listOf(confirmation), listOf(fresh(order)))
  assertFalse(dao.latestChangeForField(order.id, "Placed date")!!.newValue.endsWith(APPROXIMATE_DATE_SUFFIX))
  assertTrue(fresh(order).placedAt != null && fresh(order).placedAt != parseMailDate("Wed, 10 Jan 2024 08:00:00 +0000"))
 }

 @Test fun aShippingNoticeTimestampNeverReplacesAnExistingPurchaseDate() = runBlocking<Unit> {
  val order = seed().let { dao.updateOrder(it.copy(placedAt = 1_700_000_000_000L)); fresh(it) }
  vm.updateOrdersFromEmails(listOf(shippedMail), listOf(order))
  assertEquals(1_700_000_000_000L, fresh(order).placedAt)
 }

 @Test fun processingAndCancelledNoticesMoveTheOrderStatusForwardOnly() = runBlocking<Unit> {
  val order = seed()
  vm.updateOrdersFromEmails(listOf(mail("p1", "Order update", "Order number: $number\nYour order is being processed.")), listOf(order))
  assertEquals("Processing", fresh(order).status)
  vm.updateOrdersFromEmails(listOf(mail("c1", "Your order #$number has been cancelled", "Order number: $number")), listOf(fresh(order)))
  assertEquals("Cancelled", fresh(order).status)
  assertFalse(fresh(order).archived)
  // A later "processing" notice cannot reopen a cancelled order.
  vm.updateOrdersFromEmails(listOf(mail("p2", "Order update", "Order number: $number\nYour order is being processed.", "Tue, 16 Jan 2024 10:00:00 +0000")), listOf(fresh(order)))
  assertEquals("Cancelled", fresh(order).status)
 }

 @Test fun aCancellationNoticeIsIgnoredOnceAShipmentHasMoved() = runBlocking<Unit> {
  val order = seed(status = "Processing")
  vm.addShipmentToOrder(order.id, ShipmentDraft(number = tracking, carrier = "FedEx", status = "In Transit", confirmed = true))
  vm.updateOrdersFromEmails(listOf(mail("c1", "Your order #$number has been cancelled", "Order number: $number")), listOf(fresh(order)))
  assertEquals("Shipped", fresh(order).status)
 }

 // ---------- automatic import ----------

 @Test fun automaticImportCreatesVerifiedRecordsOnceAndReportsExactCounts() = runBlocking<Unit> {
  val confirmation = mail("c1", "Order #$number confirmed", "Order number: $number\nOrder date: January 3, 2024\nItem: Root Beer Variety Pack", "Wed, 03 Jan 2024 09:00:00 +0000")
  val marketing = mail("promo", "Save 20% this weekend", "Big sale on soda.", sender = "Deals <deals@simplisoda.com>")
  val first = vm.autoImportFromMessages(listOf(confirmation, shippedMail, marketing))
  assertEquals(1, first.ordersCreated); assertEquals(0, first.ordersUpdated)
  assertEquals(1, first.shipmentsCreated); assertEquals(0, first.shipmentsUpdated)
  assertEquals(0, first.needsReview)
  assertEquals(1, first.ignored)
  val order = dao.allOrders().single()
  assertEquals("Shipped", order.status)
  assertTrue(order.archived)
  assertEquals(tracking, dao.allTrackingNumbers().single().number)
  val second = vm.autoImportFromMessages(listOf(confirmation, shippedMail, marketing))
  assertEquals(listOf(0, 0, 0, 0), listOf(second.ordersCreated, second.ordersUpdated, second.shipmentsCreated, second.shipmentsUpdated))
  assertEquals(1, dao.allOrders().size); assertEquals(1, dao.allShipments().size)
 }

 @Test fun automaticImportCreatesAStandaloneShipmentFromACarrierNoticeAndNeverDuplicatesIt() = runBlocking<Unit> {
  val carrierMail = mail("fx", "FedEx Delivery Manager: Your package is on its way", "Tracking number: $tracking\nYour package is on its way.", sender = "FedEx <TrackingUpdates@fedex.com>")
  val run = vm.autoImportFromMessages(listOf(carrierMail))
  assertEquals(1, run.shipmentsCreated); assertEquals(0, run.ordersCreated)
  assertNull(dao.allShipments().single().orderId)
  assertEquals(0, vm.autoImportFromMessages(listOf(carrierMail)).shipmentsCreated)
  assertEquals(1, dao.allShipments().size)
 }

 // ---------- schema ----------

 @Test fun migrationFrom11To12AddsANullableShippedDateAndKeepsEveryRow() = runBlocking<Unit> {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val name = "migration-12-test.db"
  context.deleteDatabase(name)
  val built = Room.databaseBuilder(context, PackageDatabase::class.java, name).allowMainThreadQueries().build()
  val orderId = built.orderDao().insertOrder(OrderRecord(marketplace = "eBay", orderNumber = "18-15293-46910", name = "Pixel", status = "Shipped", archived = true))
  val shipmentId = built.orderDao().insertShipment(ShipmentRecord(orderId = orderId, status = "In Transit", carrier = "USPS", title = "eBay"))
  built.orderDao().insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = shipmentId, number = "9400111899223344556677", carrier = "USPS"))
  built.orderDao().insertEvent(ShipmentTrackingEventRecord(shipmentId = shipmentId, description = "Seen", status = "In Transit"))
  built.orderDao().markProcessed(ProcessedGmailMessage(messageId = "m1", orderId = orderId))
  built.close()
  val raw = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
  raw.execSQL("ALTER TABLE shipments DROP COLUMN shippedAt")
  raw.execSQL("ALTER TABLE shipments DROP COLUMN estimatedDeliveryEndAt")
  raw.execSQL("ALTER TABLE shipments DROP COLUMN estimatedDeliverySourceMessageId")
  raw.execSQL("ALTER TABLE shipments DROP COLUMN estimatedDeliverySourceAt")
  raw.version = 11
  raw.close()
  val migrated = Room.databaseBuilder(context, PackageDatabase::class.java, name).addMigrations(MIGRATION_11_12, MIGRATION_12_13).allowMainThreadQueries().build()
  try {
   val o = migrated.orderDao()
   assertEquals(1, o.allOrders().size)
   assertTrue(o.allOrders().single().archived)
   val shipment = o.getShipment(shipmentId)!!
   assertEquals(orderId, shipment.orderId)
   assertEquals("In Transit", shipment.status)
   assertNull(shipment.shippedAt)
   assertEquals(1, o.allTrackingNumbers().size)
   assertEquals(1, o.eventShipmentIds().size)
   assertEquals(1, o.allProcessed().size)
   o.updateShipment(shipment.copy(shippedAt = 1_700_000_000_000L))
   assertEquals(1_700_000_000_000L, o.getShipment(shipmentId)!!.shippedAt)
  } finally { migrated.close(); context.deleteDatabase(name) }
 }
}
