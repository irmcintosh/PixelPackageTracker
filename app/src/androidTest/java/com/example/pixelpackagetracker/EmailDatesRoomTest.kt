package com.example.pixelpackagetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Order date = verified confirmation message date; shipped date = verified dispatch message date. Real scan code and Room.
@RunWith(AndroidJUnit4::class)
class EmailDatesRoomTest {
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private val dao get() = db.orderDao()
 private val simpli = "Simpli Soda <orders@simplisoda.com>"
 private val fedex = "FedEx <TrackingUpdates@fedex.com>"
 private val number = "28855918"
 private val tracking = "38429466112"
 private val jan3 = "Wed, 03 Jan 2024 09:00:00 +0000"
 private val jan4 = "Thu, 04 Jan 2024 09:00:00 +0000"
 private val jan5 = "Fri, 05 Jan 2024 09:00:00 +0000"
 private val jan6 = "Sat, 06 Jan 2024 10:00:00 +0000"
 private val jan10 = "Wed, 10 Jan 2024 08:00:00 +0000"
 private fun at(date: String) = parseMailDate(date)

 @Before fun setUp() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  vm = TrackerVm(db, SettingsRepository(context))
 }

 @After fun tearDown() { db.close() }

 private fun mail(id: String, subject: String, date: String, body: String = "", sender: String = simpli) = GmailPreview(sender, subject, date, "", "t$id", body, id)
 private val confirmation = mail("conf", "Order #$number confirmed", jan3, "Order number: $number\nThanks for your order.")
 private val processing = mail("proc", "Order #$number update", jan5, "Your order is being processed.")
 private val earlyUpdate = mail("early", "Order #$number update", jan4, "Your order is being processed.")
 private val labelOnly = mail("label", "Order #$number tracking", jan4, "Putting the tracking number below into FedEx: $tracking")
 private val dispatch = mail("ship", "Your order #$number has shipped", jan6, "Your order has shipped via FedEx! Putting the tracking number below into FedEx: $tracking")
 private val outForDelivery = mail("ofd", "Your order #$number is out for delivery", jan10, "Your package is out for delivery. Tracking number: $tracking")
 private suspend fun seed(status: String = "Processing", placedAt: Long? = null) =
  dao.getOrder(dao.insertOrder(OrderRecord(marketplace = "Simpli Soda", orderNumber = number, name = "Soda", status = status, placedAt = placedAt)))!!
 private suspend fun scan(order: OrderRecord, vararg emails: GmailPreview) = vm.updateOrdersFromEmails(emails.toList(), listOf(dao.getOrder(order.id)!!))
 private suspend fun placedRows(order: OrderRecord) = dao.changesForOrder(order.id).filter { it.field == "Placed date" }

 // ---------- Order date ----------

 @Test fun theConfirmationMessageDatesTheOrderAndALaterStatusEmailNeverChangesIt() = runBlocking<Unit> {
  val order = seed()
  scan(order, confirmation, processing)
  assertEquals(at(jan3), dao.getOrder(order.id)!!.placedAt)
  val row = placedRows(order).single()
  assertFalse(row.newValue.endsWith(APPROXIMATE_DATE_SUFFIX))
  assertEquals("conf", row.sourceMessageId)
  repeat(2) { scan(order, confirmation, processing) }
  scan(order, mail("later", "Order #$number update", jan10, "Your order is being processed."))
  assertEquals(at(jan3), dao.getOrder(order.id)!!.placedAt)
  assertEquals(1, placedRows(order).size)
 }

 @Test fun withoutAConfirmationTheEarliestOrderEmailGivesAnApproximateDateThatTheConfirmationLaterReplaces() = runBlocking<Unit> {
  val order = seed()
  scan(order, processing, earlyUpdate)
  assertEquals(at(jan4), dao.getOrder(order.id)!!.placedAt)
  assertTrue(placedRows(order).single().newValue.endsWith(APPROXIMATE_DATE_SUFFIX))
  scan(order, processing, earlyUpdate)
  assertEquals(at(jan4), dao.getOrder(order.id)!!.placedAt)
  scan(order, confirmation)
  assertEquals(at(jan3), dao.getOrder(order.id)!!.placedAt)
  assertFalse(dao.latestChangeForField(order.id, "Placed date")!!.newValue.endsWith(APPROXIMATE_DATE_SUFFIX))
  assertEquals(2, placedRows(order).size)
 }

 @Test fun aManuallyEnteredOrderDateIsNeverReplacedByEmailDates() = runBlocking<Unit> {
  val manual = at("Mon, 01 Jan 2024 08:00:00 +0000")!!
  val order = seed(placedAt = manual)
  scan(order, confirmation, processing)
  assertEquals(manual, dao.getOrder(order.id)!!.placedAt)
  assertTrue(placedRows(order).isEmpty())
 }

 @Test fun theScanOrImportDateIsNeverUsedAsTheOrderDate() = runBlocking<Unit> {
  val order = seed()
  val future = mail("future", "Order #$number update", "Wed, 01 Jan 2200 09:00:00 +0000", "Your order is being processed.")
  scan(order, future)
  assertNull(dao.getOrder(order.id)!!.placedAt)
 }

 // ---------- Shipped date ----------

 @Test fun onlyTheDispatchEmailDatesTheShipmentAndLaterMovementKeepsIt() = runBlocking<Unit> {
  val order = seed()
  scan(order, labelOnly)
  val label = dao.getShipments(order.id).single()
  assertEquals("Label Created", label.status)
  assertNull("a tracking number or label does not establish shipment", label.shippedAt)
  scan(order, labelOnly, dispatch)
  assertEquals(at(jan6), dao.getShipments(order.id).single().shippedAt)
  scan(order, labelOnly, dispatch, outForDelivery)
  val ship = dao.getShipments(order.id).single()
  assertEquals("Out for Delivery", ship.status)
  assertEquals(at(jan6), ship.shippedAt)
  repeat(2) { scan(order, labelOnly, dispatch, outForDelivery) }
  assertEquals(at(jan6), dao.getShipments(order.id).single().shippedAt)
  assertEquals(1, dao.getShipments(order.id).size)
 }

 @Test fun aShipmentWithoutAQualifyingEmailKeepsAnUnknownShippedDate() = runBlocking<Unit> {
  val order = seed()
  scan(order, labelOnly, outForDelivery)
  assertNull(dao.getShipments(order.id).single().shippedAt)
 }

 @Test fun aManuallyEnteredShippedDateIsKept() = runBlocking<Unit> {
  val order = seed()
  val manual = at("Mon, 01 Jan 2024 08:00:00 +0000")!!
  val sid = dao.insertShipment(ShipmentRecord(orderId = order.id, status = "Label Created", carrier = "FedEx", shippedAt = manual))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = tracking, carrier = "FedEx"))
  scan(order, dispatch, outForDelivery)
  assertEquals(manual, dao.getShipment(sid)!!.shippedAt)
 }

 @Test fun aStandaloneShipmentIsDatedByDispatchOnlyAndKeepsItThroughLaterUpdates() = runBlocking<Unit> {
  val sid = dao.insertShipment(ShipmentRecord(orderId = null, status = "Awaiting Tracking", title = "Gift", carrier = "FedEx"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = tracking, carrier = "FedEx"))
  val label = mail("fx-label", "Tracking info", jan4, "Tracking number: $tracking", fedex)
  val way = mail("fx-way", "FedEx Delivery Manager: Your package is on its way", jan6, "Tracking number: $tracking", fedex)
  val ofd = mail("fx-ofd", "Your package is out for delivery", jan10, "Tracking number: $tracking", fedex)
  vm.updateShipmentsFromEmails(listOf(label), listOf(dao.getShipment(sid)!!))
  assertNull(dao.getShipment(sid)!!.shippedAt)
  assertEquals("Label Created", dao.getShipment(sid)!!.status)
  vm.updateShipmentsFromEmails(listOf(label, way), listOf(dao.getShipment(sid)!!))
  assertEquals(at(jan6), dao.getShipment(sid)!!.shippedAt)
  vm.updateShipmentsFromEmails(listOf(label, way, ofd), listOf(dao.getShipment(sid)!!))
  assertEquals("Out for Delivery", dao.getShipment(sid)!!.status)
  assertEquals(at(jan6), dao.getShipment(sid)!!.shippedAt)
  assertNotNull(dao.getShipment(sid))
 }
}
