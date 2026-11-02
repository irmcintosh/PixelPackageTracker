package com.example.pixelpackagetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Shipments-tab Update / Update All against Room and the real Gmail matching code. Shipment ids are high so the
// last-checked entries written to the real settings store can never collide with the installed app's shipments.
@RunWith(AndroidJUnit4::class)
class ShipmentUpdateRoomTest {
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private lateinit var prefs: SettingsRepository
 private val dao get() = db.orderDao()
 private val fedex = "FedEx <TrackingUpdates@fedex.com>"
 private val trackA = "38429466112"
 private val trackB = "794644790138"
 private val posted = mutableListOf<StatusNotice>()

 @Before fun setUp() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  prefs = SettingsRepository(context)
  vm = TrackerVm(db, prefs)
 }

 @After fun tearDown() { db.close() }

 private fun mail(id: String, subject: String, number: String, sender: String = fedex, body: String = "Tracking number: NUMBER") =
  GmailPreview(sender, subject, "Mon, 15 Jan 2024 10:00:00 +0000", "", "t$id", body.replace("NUMBER", number), id)
 private fun onItsWay(number: String, id: String = "way-$number") = mail(id, "FedEx Delivery Manager: Your package is on its way", number)
 private fun outForDelivery(number: String, id: String = "ofd-$number") = mail(id, "Your package is out for delivery", number)

 private suspend fun standalone(id: Long, number: String?, carrier: String = "FedEx", status: String = "Label Created", title: String = "Gift"): ShipmentRecord {
  dao.insertShipment(ShipmentRecord(id = id, orderId = null, status = status, title = title, carrier = carrier))
  if(number != null) dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = id, number = number, carrier = carrier))
  return dao.getShipment(id)!!
 }
 private suspend fun events(id: Long) = dao.eventMessageIds(id)
 private suspend fun allEvents(id: Long) = dao.observeAllEvents().first().filter { it.shipmentId == id }
 private suspend fun <T> notified(settings: TrackerSettings = TrackerSettings(), block: suspend () -> T): T {
  posted.clear()
  return withStatusNotifications(db, { settings }, { posted += it }, block)
 }

 @Test fun anIndividualUpdateAdvancesAStandaloneShipmentAndRecordsOneEventPerMessage() = runBlocking<Unit> {
  val ship = standalone(9_000_001, trackA)
  dao.insertEvent(ShipmentTrackingEventRecord(shipmentId = ship.id, description = "Dropped at counter", source = "manual"))
  val first = vm.updateShipmentsFromEmails(listOf(onItsWay(trackA)), listOf(ship))
  assertEquals(1, first.updated)
  assertEquals("In Transit", dao.getShipment(ship.id)!!.status)
  assertEquals(listOf("way-$trackA"), events(ship.id))
  val again = vm.updateShipmentsFromEmails(listOf(onItsWay(trackA)), listOf(dao.getShipment(ship.id)!!))
  assertEquals(0, again.updated)
  assertEquals(1, again.unchanged)
  assertEquals(listOf("way-$trackA"), events(ship.id))
  vm.updateShipmentsFromEmails(listOf(onItsWay(trackA), outForDelivery(trackA)), listOf(dao.getShipment(ship.id)!!))
  assertEquals("Out for Delivery", dao.getShipment(ship.id)!!.status)
  assertEquals(2, events(ship.id).size)
  assertEquals("the manual event is kept", 3, allEvents(ship.id).size)
  assertTrue(allEvents(ship.id).any { it.description == "Dropped at counter" })
 }

 @Test fun mailForAnotherTrackingNumberOrCarrierIsIgnoredAndManualDataIsKept() = runBlocking<Unit> {
  val ship = standalone(9_000_002, trackA, title = "My title")
  val other = vm.updateShipmentsFromEmails(listOf(onItsWay(trackB)), listOf(ship))
  assertEquals(0, other.updated)
  val upsSaved = standalone(9_000_003, trackB, carrier = "UPS", title = "Held as UPS")
  val wrongCarrier = vm.updateShipmentsFromEmails(listOf(onItsWay(trackB)), listOf(upsSaved))
  assertEquals(0, wrongCarrier.updated)
  assertEquals("Label Created", dao.getShipment(upsSaved.id)!!.status)
  assertEquals("My title", dao.getShipment(ship.id)!!.title)
  assertEquals(1, dao.getTrackingNumbersForShipment(ship.id).size)
  assertTrue(events(ship.id).isEmpty() && events(upsSaved.id).isEmpty())
 }

 @Test fun aShipmentWithoutASavedTrackingNumberIsSkippedAndNothingIsCreated() = runBlocking<Unit> {
  val ship = standalone(9_000_004, null)
  val before = dao.allShipments().size
  val result = vm.updateShipmentsFromEmails(listOf(onItsWay(trackA)), listOf(ship))
  assertEquals(0, result.checked)
  assertEquals(1, result.withoutTracking)
  assertEquals(before, dao.allShipments().size)
  assertTrue(dao.allTrackingNumbers().isEmpty())
  assertNull(prefs.shipmentChecks.first()[ship.id])
 }

 @Test fun updateAllReusesOneMessageListForStandaloneAndArchivedOrderShipmentsWithoutTouchingTheOrder() = runBlocking<Unit> {
  val a = standalone(9_000_005, trackA)
  val orderId = dao.insertOrder(OrderRecord(marketplace = "Simpli Soda", orderNumber = "28855918", name = "Soda", status = "Shipped"))
  dao.archiveOrder(orderId, true)
  dao.insertShipment(ShipmentRecord(id = 9_000_006, orderId = orderId, status = "In Transit", title = "Simpli Soda", carrier = "FedEx"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = 9_000_006, number = trackB, carrier = "FedEx"))
  val linked = dao.getShipment(9_000_006)!!
  val orderBefore = dao.getOrder(orderId)!!
  val messages = listOf(onItsWay(trackA), outForDelivery(trackB), mail("noise", "Weekly deals", "000"))
  val result = vm.updateShipmentsFromEmails(messages, listOf(a, linked))
  assertEquals(2, result.checked)
  assertEquals(result.lines.toString(), 2, result.updated)
  assertEquals("In Transit", dao.getShipment(a.id)!!.status)
  assertEquals("Out for Delivery", dao.getShipment(linked.id)!!.status)
  val orderAfter = dao.getOrder(orderId)!!
  assertEquals(orderBefore.status, orderAfter.status)
  assertTrue(orderAfter.archived)
  assertEquals(orderId, dao.getShipment(linked.id)!!.orderId)
  val history = dao.changesForOrder(orderId).size
  val repeat = vm.updateShipmentsFromEmails(messages, listOf(dao.getShipment(a.id)!!, dao.getShipment(linked.id)!!))
  assertEquals(0, repeat.updated)
  assertEquals(history, dao.changesForOrder(orderId).size)
  assertEquals(1, dao.getShipments(orderId).size)
  assertEquals(2, dao.allShipments().size)
  val checks = prefs.shipmentChecks.first()
  assertNotNull(checks[a.id]); assertNotNull(checks[linked.id])
 }

 @Test fun aRealStatusChangeNotifiesOnceAndARepeatOrSwitchedOffUpdateDoesNot() = runBlocking<Unit> {
  val ship = standalone(9_000_007, trackA)
  notified { vm.updateShipmentsFromEmails(listOf(onItsWay(trackA)), listOf(ship)) }
  assertEquals(listOf("Gift: In Transit"), posted.map { it.message })
  assertEquals("Shipment updated", posted.single().title)
  notified { vm.updateShipmentsFromEmails(listOf(onItsWay(trackA)), listOf(dao.getShipment(ship.id)!!)) }
  assertTrue(posted.isEmpty())
  notified(TrackerSettings(notifyOut = false, notifyEvery = false)) { vm.updateShipmentsFromEmails(listOf(outForDelivery(trackA)), listOf(dao.getShipment(ship.id)!!)) }
  assertTrue(posted.isEmpty())
  assertEquals("Out for Delivery", dao.getShipment(ship.id)!!.status)
 }

 @Test fun manualUpdatesNeverTouchTheBackgroundScheduleOrReEmitSettings() = runBlocking<Unit> {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val work = WorkManager.getInstance(context)
  val before = work.getWorkInfosForUniqueWork(BackgroundSync.WORK).get().firstOrNull { !it.state.isFinished }
  assumeTrue("the app has scheduled its background job", before != null)
  val ship = standalone(9_000_008, trackA)
  val emitted = async(Dispatchers.Default) { withTimeoutOrNull(1500) { prefs.settings.drop(1).first() } }
  vm.updateShipmentsFromEmails(listOf(outForDelivery(trackA)), listOf(dao.getShipment(ship.id)!!))
  assertNull("a shipment check must not re-emit settings (their collector reschedules the sync)", emitted.await())
  val after = work.getWorkInfosForUniqueWork(BackgroundSync.WORK).get().first { !it.state.isFinished }
  assertEquals(before!!.id, after.id)
  assertEquals(before.periodicityInfo!!.repeatIntervalMillis, after.periodicityInfo!!.repeatIntervalMillis)
  assertEquals(before.nextScheduleTimeMillis, after.nextScheduleTimeMillis)
 }
}
