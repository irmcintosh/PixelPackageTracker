package com.example.pixelpackagetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Requires an emulator/device: uses an in-memory Room database, never the app's real data.
@RunWith(AndroidJUnit4::class)
class GmailScanUpdateTest {
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private val dao get() = db.orderDao()

 @Before fun setUp() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  vm = TrackerVm(db, SettingsRepository(context))
 }

 @After fun tearDown() { db.close() }

 private fun shippedEmail(id: String = "m1", tracking: String = "38429466112") = GmailPreview(
  sender = "Simpli Soda <orders@simplisoda.com>", subject = "Your Simpli Soda order #28855918 has shipped",
  date = "Mon, 15 Jan 2024 10:00:00 +0000", snippet = "Your order has shipped.", threadId = "t1",
  body = "Order #28855918\nTracking number: $tracking\nCarrier: FedEx", messageId = id)

 private suspend fun seedOrder(): OrderRecord {
  val id = dao.insertOrder(OrderRecord(marketplace = "Simpli Soda", orderNumber = "28855918", name = "Simpli Soda"))
  return dao.getOrder(id)!!
 }

 @Test fun genuineChangeProducesCommittedBeforeAfterResults() = runBlocking<Unit> {
  val order = seedOrder()
  val summary = vm.updateOrdersFromEmails(listOf(shippedEmail()), listOf(order))
  val result = summary.results.single()
  assertEquals(ScanOutcome.UPDATED, result.outcome)
  val tracking = result.changes.single { it.field == "Tracking number" }
  assertEquals("Not available", tracking.before); assertEquals("38429466112", tracking.after)
  assertEquals(ChangeKind.NEW_VALUE, tracking.kind)
  assertTrue(tracking.source!!.contains("Simpli Soda") && tracking.source!!.contains("has shipped"))
  val status = result.changes.single { it.field == "Shipment status" }
  assertEquals("Awaiting Tracking", status.before); assertEquals("In Transit", status.after)
  assertEquals("Shipped", result.changes.single { it.field == "Order status" }.after)
  // Every reported change exists as a persisted history row with the same values.
  val persisted = dao.observeChanges(order.id).first()
  assertEquals(result.changes.map { it.historyId }.toSet(), persisted.map { it.id }.toSet())
  assertEquals("38429466112", persisted.single { it.field == "Tracking number" }.newValue)
 }

 @Test fun repeatedScanReportsNoFurtherUpdatesEvenWithStaleOrderSnapshot() = runBlocking<Unit> {
  val order = seedOrder()
  vm.updateOrdersFromEmails(listOf(shippedEmail()), listOf(order))
  val historyAfterFirst = dao.observeChanges(order.id).first().size
  val second = vm.updateOrdersFromEmails(listOf(shippedEmail()), listOf(order))
  assertEquals(ScanOutcome.UNCHANGED, second.results.single().outcome)
  assertEquals(0, second.updated)
  assertEquals(historyAfterFirst, dao.observeChanges(order.id).first().size)
  assertEquals(1, dao.getShipments(order.id).size)
  assertEquals(1, dao.allTrackingNumbers().size)
 }

 @Test fun reviewRequiredIsNotAppliedOrReportedAsUpdated() = runBlocking<Unit> {
  val order = seedOrder()
  listOf("11111111111", "22222222222").forEach { number ->
   val sid = dao.insertShipment(ShipmentRecord(orderId = order.id, status = "Awaiting Tracking"))
   dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = number, carrier = "FedEx"))
  }
  val summary = vm.updateOrdersFromEmails(listOf(shippedEmail(tracking = "55555555555")), listOf(order))
  val result = summary.results.single()
  assertEquals(ScanOutcome.REVIEW_REQUIRED, result.outcome)
  assertTrue(result.changes.isEmpty())
  assertEquals(0, summary.updated)
  assertEquals(2, dao.allTrackingNumbers().size)
  assertTrue(dao.getShipments(order.id).all { it.status == "Awaiting Tracking" })
  assertTrue(dao.observeChanges(order.id).first().isEmpty())
 }

 @Test fun failedUpdateRollsBackAndIsReportedAsFailed() = runBlocking<Unit> {
  val order = seedOrder()
  db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_history BEFORE INSERT ON order_change_history BEGIN SELECT RAISE(ABORT, 'forced failure'); END")
  val result = vm.updateOrdersFromEmails(listOf(shippedEmail()), listOf(order)).results.single()
  assertEquals(ScanOutcome.FAILED, result.outcome)
  assertTrue(result.changes.isEmpty())
  assertTrue(dao.getShipments(order.id).isEmpty())
  assertTrue(dao.allTrackingNumbers().isEmpty())
  assertEquals("Order Placed", dao.getOrder(order.id)!!.status)
 }

 @Test fun missingOrderIsFailedNotUpdated() = runBlocking<Unit> {
  val ghost = OrderRecord(id = 999, marketplace = "Simpli Soda", orderNumber = "28855918", name = "Simpli Soda")
  val result = vm.updateOrdersFromEmails(listOf(shippedEmail()), listOf(ghost)).results.single()
  assertEquals(ScanOutcome.FAILED, result.outcome)
  assertTrue(dao.observeOrders().first().isEmpty())
 }

 @Test fun updatedStateIsVisibleThroughTheFlowsOrdersObserves() = runBlocking<Unit> {
  val order = seedOrder()
  vm.updateOrdersFromEmails(listOf(shippedEmail()), listOf(order))
  withTimeout(5_000) {
   val shown = vm.orders.first { list -> list.any { it.id == order.id && it.status == "Shipped" } }
   assertNotNull(shown.single { it.id == order.id }.lastSyncedAt)
   vm.shipments.first { list -> list.any { it.orderId == order.id && it.status == "In Transit" } }
   vm.numbers.first { list -> list.any { it.number == "38429466112" } }
  }
 }

 @Test fun unrelatedOrdersItemsAndShipmentsRemainIntact() = runBlocking<Unit> {
  val order = seedOrder()
  val otherId = dao.insertOrder(OrderRecord(marketplace = "eBay", orderNumber = "11-22222-33333", name = "Keyboard", status = "Shipped", notes = "keep me"))
  dao.insertItem(OrderItemRecord(orderId = otherId, name = "Keyboard"))
  val otherShip = dao.insertShipment(ShipmentRecord(orderId = otherId, status = "In Transit", title = "Keyboard"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = otherShip, number = "9400111899223344556677", carrier = "USPS"))
  val before = dao.getOrder(otherId)!!
  vm.updateOrdersFromEmails(listOf(shippedEmail()), listOf(order, before))
  val after = dao.getOrder(otherId)!!
  assertEquals(before.copy(lastSyncedAt = after.lastSyncedAt, archived = true, archivedAt = after.archivedAt), after)
  assertEquals(1, dao.getItems(otherId).size)
  assertEquals("In Transit", dao.getShipments(otherId).single().status)
  assertEquals("9400111899223344556677", dao.getTrackingNumbersForShipment(otherShip).single().number)
  assertEquals(listOf("Order archive"), dao.observeChanges(otherId).first().map { it.field })
 }
}
