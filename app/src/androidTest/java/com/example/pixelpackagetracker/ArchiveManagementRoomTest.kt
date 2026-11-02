package com.example.pixelpackagetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Destructive scenarios use only an isolated in-memory database. */
@RunWith(AndroidJUnit4::class)
class ArchiveManagementRoomTest {
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private val dao get() = db.orderDao()
 @Before fun setup() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  // Isolate DataStore as well as Room. The instrumentation APK has no Application context.
  val testPreferencesContext = object : android.content.ContextWrapper(context) {
   override fun getApplicationContext(): android.content.Context = this
   override fun getFilesDir(): java.io.File = java.io.File(context.cacheDir, "archive-management-test-preferences").apply { mkdirs() }
  }
  vm = TrackerVm(db, SettingsRepository(testPreferencesContext))
 }
 @After fun close() { db.close() }
 private suspend fun order(status: String = "Shipped", archived: Boolean = false): Long = dao.insertOrder(
  OrderRecord(marketplace = "Test", orderNumber = "test-${System.nanoTime()}", name = "Preserve manual name", status = status, notes = "Manual notes", archived = archived))
 private suspend fun ship(orderId: Long? = null, status: String = "In Transit", archived: Boolean = false): Long {
  val id = dao.insertShipment(ShipmentRecord(orderId = orderId, status = status, carrier = "FedEx", title = "Manual parcel", shippedAt = 10, estimatedDeliveryAt = 20, archived = archived))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = id, number = "38429466112", carrier = "FedEx"))
  dao.insertEvent(ShipmentTrackingEventRecord(shipmentId = id, description = "Preserved history", eventAt = 10))
  return id
 }
 @Test fun reevaluationArchivesOldEligibleOrdersButNotMissingShipmentOrLabelOnlyOrders() = runBlocking<Unit> {
  val ready = order(); val sid = ship(ready)
  val missing = order()
  val empty = order(); dao.insertShipment(ShipmentRecord(orderId = empty))
  val label = order("Preparing for Shipment"); ship(label, "Label Created")
  vm.reconcileArchives()
  assertTrue(dao.getOrder(ready)!!.archived)
  assertFalse(dao.getOrder(missing)!!.archived)
  assertFalse(dao.getOrder(empty)!!.archived)
  assertFalse(dao.getOrder(label)!!.archived)
  assertFalse(dao.getShipment(sid)!!.archived)
  assertEquals("Manual notes", dao.getOrder(ready)!!.notes)
  val history = dao.changesForOrder(ready)
  vm.reconcileArchives()
  assertEquals(history, dao.changesForOrder(ready))
 }
 @Test fun deliveredArchivesImmediatelyAndDoesNotChangeArchivedPurchaseStatus() = runBlocking<Unit> {
  val oid = order("Processing", archived = true); val sid = ship(oid)
  val before = dao.getOrder(oid)
  vm.setShipmentStatus(sid, "Delivered")
  assertTrue(dao.getShipment(sid)!!.archived)
  assertEquals(before, dao.getOrder(oid))
  assertEquals(oid, dao.getShipment(sid)!!.orderId)
  assertEquals(1, dao.allTrackingNumbers().size)
  val events = dao.eventShipmentIds().size
  vm.setShipmentStatus(sid, "Delivered")
  assertEquals(events, dao.eventShipmentIds().size)
 }
 @Test fun existingDeliveredShipmentsAreReevaluatedWithoutTouchingTheirOrder() = runBlocking<Unit> {
  val oid = order("Processing"); val sid = ship(oid)
  dao.updateShipmentRow(dao.getShipment(sid)!!.copy(status = "Delivered"))
  val before = dao.getOrder(oid)
  vm.reconcileArchives()
  assertTrue(dao.getShipment(sid)!!.archived)
  assertEquals(before, dao.getOrder(oid))
 }
 @Test fun earlyShipmentStatusesNeverArchive() = runBlocking<Unit> {
  listOf("Label Created", "In Transit", "Out for Delivery").forEach { status ->
   val sid = ship(status = status)
   vm.setShipmentStatus(sid, status)
   assertFalse(dao.getShipment(sid)!!.archived)
  }
 }
 @Test fun archiveAndDeleteOneShipmentPreserveOrderAndSiblings() = runBlocking<Unit> {
  val oid = order("Processing"); val sid = ship(oid); val other = ship(oid)
  val before = dao.getOrder(oid)
  dao.archiveShipment(sid, true)
  assertTrue(dao.getShipment(sid)!!.archived)
  assertEquals(before, dao.getOrder(oid))
  vm.deleteShipmentRecord(sid)
  assertNull(dao.getShipment(sid))
  assertEquals(before, dao.getOrder(oid))
  assertNotNull(dao.getShipment(other))
  assertEquals(listOf(other), dao.eventShipmentIds())
  assertEquals(other, dao.allTrackingNumbers().single().shipmentId)
 }
 @Test fun deleteAllDetachesActiveShipmentAndPreservesAllItsDetailsAndHistory() = runBlocking<Unit> {
  val archivedOrder = order(archived = true); val activeOrder = order("Processing")
  val activeChild = ship(archivedOrder); val archivedChild = ship(archivedOrder, archived = true)
  val archivedUnderActive = ship(activeOrder, archived = true); val unrelated = ship(activeOrder)
  val loose = ship(archived = true)
  val before = dao.getShipment(activeChild)!!
  val activeBefore = dao.getOrder(activeOrder)
  vm.deleteArchived(ArchiveSelection(listOf(archivedOrder), listOf(archivedChild, archivedUnderActive, loose)))
  assertNull(dao.getOrder(archivedOrder))
  assertEquals(before.copy(orderId = null), dao.getShipment(activeChild))
  assertEquals(activeBefore, dao.getOrder(activeOrder))
  assertNotNull(dao.getShipment(unrelated))
  assertEquals(setOf(activeChild, unrelated), dao.allShipments().map { it.id }.toSet())
  assertEquals(setOf(activeChild, unrelated), dao.eventShipmentIds().toSet())
  assertEquals(setOf(activeChild, unrelated), dao.allTrackingNumbers().map { it.shipmentId }.toSet())
  db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
 }
 @Test fun confirmedIdsExcludeNewlyArchivedRecordsAndPreserveRestoredOnes() = runBlocking<Unit> {
  val oid = order(archived = true); val sid = ship(oid, archived = true)
  val selection = ArchiveSelection(listOf(oid), listOf(sid))
  dao.archiveShipment(sid, false); dao.archiveOrder(oid, false)
  val laterOrder = order(archived = true); val laterShip = ship(laterOrder, archived = true)
  vm.deleteArchived(selection)
  assertNotNull(dao.getOrder(oid)); assertNotNull(dao.getShipment(sid))
  assertNotNull(dao.getOrder(laterOrder)); assertNotNull(dao.getShipment(laterShip))
 }
 @Test fun failureRollsBackShipmentDeletionDetachmentAndOrderDeletion() = runBlocking<Unit> {
  val oid = order(archived = true); val active = ship(oid); val archived = ship(archived = true)
  db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_archive_delete BEFORE DELETE ON orders BEGIN SELECT RAISE(ABORT, 'isolated test failure'); END")
  var failed = false
  try { vm.deleteArchived(ArchiveSelection(listOf(oid), listOf(archived))) } catch(e: Exception) { failed = true }
  assertTrue(failed)
  assertNotNull(dao.getOrder(oid)); assertNotNull(dao.getShipment(archived))
  assertEquals(oid, dao.getShipment(active)!!.orderId)
  assertEquals(2, dao.eventShipmentIds().size)
  assertEquals(0, dao.wasDeleted("Test", dao.getOrder(oid)!!.orderNumber))
 }
 @Test fun emptyScanReevaluatesExistingRecordsInTheSharedForegroundBackgroundPath() = runBlocking<Unit> {
  val oid = order(); ship(oid)
  val sid = ship(); dao.updateShipmentRow(dao.getShipment(sid)!!.copy(status = "Delivered"))
  vm.autoImportFromMessages(emptyList(), reanalyze = false)
  assertTrue(dao.getOrder(oid)!!.archived); assertTrue(dao.getShipment(sid)!!.archived)
  val history = dao.changesForOrder(oid)
  vm.autoImportFromMessages(emptyList(), reanalyze = false)
  assertEquals(history, dao.changesForOrder(oid))
 }
 @Test fun addingCarrierToAnExistingEmptyShipmentCompletesHandoff() = runBlocking<Unit> {
  val oid = order(); val sid = dao.insertShipment(ShipmentRecord(orderId = oid))
  vm.editShipment(sid, null, "", "FedEx", null)
  assertTrue(dao.getOrder(oid)!!.archived)
 }
 @Test fun singleShipmentGmailUpdateArchivesDeliveryAndFreezesArchivedOrderStatus() = runBlocking<Unit> {
  val oid = order("Processing", archived = true); val sid = ship(oid)
  val email = GmailPreview("FedEx <TrackingUpdates@fedex.com>", "Delivered: 38429466112", "Mon, 15 Jan 2024 10:00:00 +0000", "", "archive-test-thread", "Your package was delivered. Tracking number: 38429466112", "archive-test-delivered")
  vm.updateShipmentsFromEmails(listOf(email), listOf(dao.getShipment(sid)!!))
  assertEquals("Delivered", dao.getShipment(sid)!!.status)
  assertTrue(dao.getShipment(sid)!!.archived)
  assertEquals("Processing", dao.getOrder(oid)!!.status)
  val events = dao.eventShipmentIds().size
  vm.updateShipmentsFromEmails(listOf(email), listOf(dao.getShipment(sid)!!))
  assertEquals(events, dao.eventShipmentIds().size)
 }
 @Test fun individualOrderUpdateAndUncheckableShipmentUpdateAlsoReevaluate() = runBlocking<Unit> {
  val oid = order(); ship(oid)
  vm.updateOrdersFromEmails(emptyList(), listOf(dao.getOrder(oid)!!), orderSpecific = true)
  assertTrue(dao.getOrder(oid)!!.archived)
  val sid = dao.insertShipmentRow(ShipmentRecord(status = "Delivered"))
  vm.updateShipmentsFromEmails(emptyList(), listOf(dao.getShipment(sid)!!))
  assertTrue(dao.getShipment(sid)!!.archived)
 }

 @Test fun individualArchivedOrderDeletionAlsoPreservesActiveChildren() = runBlocking<Unit> {
  val oid = order(archived = true); val active = ship(oid); val archived = ship(oid, archived = true)
  val before = dao.getShipment(active)!!
  vm.delete(dao.getOrder(oid)!!).join()
  assertNull(dao.getOrder(oid)); assertNull(dao.getShipment(archived))
  assertEquals(before.copy(orderId = null), dao.getShipment(active))
  assertEquals(listOf(active), dao.eventShipmentIds())
 }

}
