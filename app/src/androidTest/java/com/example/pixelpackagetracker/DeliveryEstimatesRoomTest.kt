package com.example.pixelpackagetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DeliveryEstimatesRoomTest {
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private val dao get() = db.orderDao()
 private val tracking = "38429466112"
 private val orderNumber = "28855918"
 private val oldDate = "Wed, 28 Oct 2026 10:00:00 +0000"
 private val newDate = "Thu, 29 Oct 2026 10:00:00 +0000"
 private val oct30 get() = deliveryEpoch(LocalDate.of(2026, 10, 30))
 private val nov2 get() = deliveryEpoch(LocalDate.of(2026, 11, 2))
 private fun mail(id: String, estimate: String = "October 30, 2026", date: String = oldDate, tracked: Boolean = true) =
  GmailPreview(if(tracked) "FedEx <updates@fedex.com>" else "Simpli Soda <orders@simplisoda.com>",
   "Delivery estimate updated", date, "", body = (if(tracked) "Tracking number: $tracking" else "Order #$orderNumber") +
    "\nEstimated delivery: $estimate", messageId = id)

 @Before fun setUp() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  vm = TrackerVm(db, SettingsRepository(context))
 }
 @After fun tearDown() { db.close() }
 private suspend fun seed(linked: Boolean = true): ShipmentRecord {
  val orderId = if(linked) dao.insertOrder(OrderRecord(marketplace = "Simpli Soda", orderNumber = orderNumber, name = "Soda", placedAt = 1, notes = "Keep", archived = true)) else null
  val id = dao.insertShipment(ShipmentRecord(orderId = orderId, carrier = "FedEx", status = "In Transit", shippedAt = 2))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = id, number = tracking, carrier = "FedEx"))
  return dao.getShipment(id)!!
 }
 private suspend fun update(ship: ShipmentRecord, vararg emails: GmailPreview) =
  vm.updateShipmentsFromEmails(emails.toList(), listOf(dao.getShipment(ship.id)!!))

 @Test fun etaOnlyRevisionCountsAndNeverChangesStatusHistoryArchiveOrNotifies() = runBlocking<Unit> {
  val ship = seed()
  val orderBefore = dao.getOrder(ship.orderId!!)!!
  val posted = mutableListOf<StatusNotice>()
  val first = withStatusNotifications(db, { TrackerSettings() }, { posted += it }) { update(ship, mail("initial")) }
  assertEquals(1, first.updated)
  val revised = update(ship, mail("revised", "November 2, 2026", newDate), mail("initial"))
  assertEquals(1, revised.updated)
  val fresh = dao.getShipment(ship.id)!!
  assertEquals(nov2, fresh.estimatedDeliveryAt)
  assertEquals(ship.status, fresh.status)
  assertEquals(ship.statusUpdatedAt, fresh.statusUpdatedAt)
  assertEquals(ship.shippedAt, fresh.shippedAt)
  assertEquals(ship.archived, fresh.archived)
  assertEquals(orderBefore.status, dao.getOrder(ship.orderId)!!.status)
  assertTrue(dao.getOrder(ship.orderId)!!.archived)
  assertTrue(dao.observeAllEvents().first().isEmpty())
  assertTrue(posted.isEmpty())
  val history = dao.changesForOrder(ship.orderId).size
  assertEquals(0, update(ship, mail("initial"), mail("revised", "November 2, 2026", newDate)).updated)
  assertEquals(history, dao.changesForOrder(ship.orderId).size)
  assertEquals(1, dao.allShipments().size)
  assertEquals(1, dao.allTrackingNumbers().size)
 }
 @Test fun newerIdenticalEstimateAdvancesFreshnessButNotCountsOrHistory() = runBlocking<Unit> {
  val ship = seed()
  update(ship, mail("a"))
  val count = dao.changesForOrder(ship.orderId!!).size
  assertEquals(0, update(ship, mail("b", date = newDate)).updated)
  assertEquals("b", dao.getShipment(ship.id)!!.estimatedDeliverySourceMessageId)
  assertEquals(count, dao.changesForOrder(ship.orderId).size)
  assertEquals(0, update(ship, mail("c", "November 2, 2026", newDate)).updated)
  assertEquals(oct30, dao.getShipment(ship.id)!!.estimatedDeliveryAt)
 }
 @Test fun equalTimestampConflictsOnAnEmptyShipmentAreHeldForReview() = runBlocking<Unit> {
  val ship = seed()
  val result = update(ship, mail("a"), mail("b", "November 2, 2026"))
  assertNull(dao.getShipment(ship.id)!!.estimatedDeliveryAt)
  assertTrue(result.needsReview > 0)
 }
 @Test fun conflictingNewestEvidenceDoesNotFallBackToAnotherOlderEstimate() = runBlocking<Unit> {
  val ship = seed()
  update(ship, mail("initial"))
  val result = update(ship, mail("conflict-a", "November 2, 2026", newDate),
   mail("conflict-b", "November 3, 2026", newDate),
   mail("middle", "November 1, 2026", "Thu, 29 Oct 2026 09:00:00 +0000"))
  assertEquals(oct30, dao.getShipment(ship.id)!!.estimatedDeliveryAt)
  assertTrue(result.needsReview > 0)
 }
 @Test fun tracklessOrderEstimateNeedsExactlyOneShipment() = runBlocking<Unit> {
  val ship = seed()
  val order = dao.getOrder(ship.orderId!!)!!
  vm.updateOrdersFromEmails(listOf(mail("unique", tracked = false)), listOf(order))
  assertEquals(oct30, dao.getShipment(ship.id)!!.estimatedDeliveryAt)
  dao.updateShipment(dao.getShipment(ship.id)!!.copy(estimatedDeliveryAt = null, estimatedDeliverySourceMessageId = "", estimatedDeliverySourceAt = null))
  val other = dao.insertShipment(ShipmentRecord(orderId = order.id, carrier = "USPS"))
  val result = vm.updateOrdersFromEmails(listOf(mail("ambiguous", tracked = false)), listOf(order))
  assertNull(dao.getShipment(ship.id)!!.estimatedDeliveryAt)
  assertNull(dao.getShipment(other)!!.estimatedDeliveryAt)
  assertTrue(result.reviews.isNotEmpty())
 }
 @Test fun trackingSelectsOnlyItsPackageUnderAMultiShipmentOrder() = runBlocking<Unit> {
  val ship = seed()
  val other = dao.insertShipment(ShipmentRecord(orderId = ship.orderId, status = "In Transit"))
  update(ship, mail("one"))
  assertEquals(oct30, dao.getShipment(ship.id)!!.estimatedDeliveryAt)
  assertNull(dao.getShipment(other)!!.estimatedDeliveryAt)
 }
 @Test fun standaloneEstimatesReviseWithOneEventPerMeaningfulChange() = runBlocking<Unit> {
  val ship = seed(false)
  assertEquals(1, update(ship, mail("one")).updated)
  assertEquals(1, update(ship, mail("two", "November 2, 2026", newDate)).updated)
  assertEquals(0, update(ship, mail("one"), mail("two", "November 2, 2026", newDate)).updated)
  val events = dao.observeAllEvents().first()
  assertEquals(2, events.size)
  assertTrue(events.all { it.source == "gmail-eta" && it.status.isBlank() })
  assertEquals("In Transit", dao.getShipment(ship.id)!!.status)
 }
 @Test fun automaticInboxDiscoverySupportsStandaloneEtaAndRepeatsWithoutDuplicates() = runBlocking<Unit> {
  val email = mail("discovery").copy(subject = "Your package is on its way")
  val first = vm.autoImportFromMessages(listOf(email))
  assertEquals(1, first.shipmentsCreated)
  assertEquals(oct30, dao.allShipments().single().estimatedDeliveryAt)
  val counts = dao.observeAllEvents().first().size
  val repeat = vm.autoImportFromMessages(listOf(email))
  assertEquals(0, repeat.shipmentsCreated)
  assertEquals(0, repeat.shipmentsUpdated)
  assertEquals(counts, dao.observeAllEvents().first().size)
 }
 @Test fun noEtaAndPromotionsLeaveAnExistingEstimateUntouched() = runBlocking<Unit> {
  val ship = seed()
  update(ship, mail("one"))
  val before = dao.getShipment(ship.id)!!
  update(ship, mail("none").copy(body = "Tracking number: $tracking\nNo delivery estimate available."),
   mail("promo", "November 2, 2026", newDate).copy(subject = "Save 20% on your next order"))
  assertEquals(before, dao.getShipment(ship.id))
 }
 @Test fun manualValuesAndClearingProtectLinkedAndStandaloneShipments() = runBlocking<Unit> {
  for(linked in listOf(true, false)) {
   val ship = seed(linked)
   val number = dao.getTrackingNumbersForShipment(ship.id).single()
   vm.editShipment(ship.id, number.id, tracking, "FedEx", oct30, changeEstimated = true)
   update(ship, mail("manual-$linked", "November 2, 2026", newDate))
   assertEquals(SOURCE_MANUAL, dao.getShipment(ship.id)!!.estimatedDeliverySourceMessageId)
   vm.editShipment(ship.id, number.id, tracking, "FedEx", null, changeEstimated = true)
   update(ship, mail("cleared-$linked", "November 2, 2026", newDate))
   assertNull(dao.getShipment(ship.id)!!.estimatedDeliveryAt)
   assertEquals(SOURCE_MANUAL, dao.getShipment(ship.id)!!.estimatedDeliverySourceMessageId)
   // Keep the next fixture's tracking identity unique.
   dao.deleteShipment(ship.id)
  }
 }
 @Test fun unrelatedEditsPreserveRangeAndProvenanceAndLegacyEstimatesStayProtected() = runBlocking<Unit> {
  val ship = seed()
  update(ship, mail("range", "November 5-12"))
  val before = dao.getShipment(ship.id)!!
  val number = dao.getTrackingNumbersForShipment(ship.id).single()
  vm.editShipment(ship.id, number.id, tracking, "FedEx", null, changeEstimated = false)
  assertEquals(before, dao.getShipment(ship.id))
  dao.updateShipment(before.copy(estimatedDeliveryAt = 123, estimatedDeliveryEndAt = null, estimatedDeliverySourceMessageId = "", estimatedDeliverySourceAt = null))
  update(ship, mail("legacy", "November 2, 2026", newDate))
  assertEquals(123L, dao.getShipment(ship.id)!!.estimatedDeliveryAt)
 }
 @Test fun historicalEtaPassPreservesManualAssociationMarkersAndDoesNotReplayOtherFields() = runBlocking<Unit> {
  val ship = seed()
  val email = mail("manually-associated", tracked = false).copy(body = "Order #$orderNumber\nEstimated delivery: October 30, 2026\nYour order is being processed.\nOrder date: January 1, 2020")
  dao.markProcessed(ProcessedGmailMessage(messageId = email.messageId, orderId = ship.orderId!!, extractionVersion = MANUAL_ASSOCIATION_VERSION))
  val before = dao.getOrder(ship.orderId)!!
  vm.autoImportFromMessages(listOf(email), reanalyze = false)
  assertEquals(oct30, dao.getShipment(ship.id)!!.estimatedDeliveryAt)
  assertEquals(before.placedAt, dao.getOrder(ship.orderId)!!.placedAt)
  assertEquals(before.status, dao.getOrder(ship.orderId)!!.status)
  assertEquals(before.notes, dao.getOrder(ship.orderId)!!.notes)
  assertEquals(MANUAL_ASSOCIATION_VERSION, dao.allProcessed().single().extractionVersion)
  assertEquals(1, dao.changesForOrder(ship.orderId).size)
  vm.autoImportFromMessages(listOf(email), reanalyze = false)
  assertEquals(1, dao.changesForOrder(ship.orderId).size)
 }
 @Test fun currentParserMarkersAlsoAllowTargetedEtaBackfillInBackground() = runBlocking<Unit> {
  val ship = seed()
  val email = mail("parser-three").copy(body = "Tracking number: $tracking\nEstimated delivery: October 30, 2026\nYour package was accepted by FedEx.")
  dao.markProcessed(ProcessedGmailMessage(messageId = email.messageId, orderId = ship.orderId!!, extractionVersion = GMAIL_PARSER_VERSION))
  vm.autoImportFromMessages(listOf(email), reanalyze = false)
  val fresh = dao.getShipment(ship.id)!!
  assertEquals(oct30, fresh.estimatedDeliveryAt)
  assertEquals(ship.shippedAt, fresh.shippedAt)
  assertEquals(ship.statusUpdatedAt, fresh.statusUpdatedAt)
  assertEquals(1, dao.changesForOrder(ship.orderId).size)
  assertEquals(GMAIL_PARSER_VERSION, dao.allProcessed().single().extractionVersion)
 }
 @Test fun downloadedLabelsAndInternalDateAreHandledBeforeProcessing() {
  val message = JSONObject().put("labelIds", JSONArray().put("INBOX")).put("internalDate", "1234").put("id", "m")
  assertTrue(downloadedMessageIsInInbox(message))
  assertEquals(1234L, parseGmailMessage(message).internalDate)
  message.put("labelIds", JSONArray().put("SENT"))
  assertFalse(downloadedMessageIsInInbox(message))
 }
 @Test fun additive12To13MigrationKeepsPopulatedRowsAndProtectsLegacyEta() = runBlocking<Unit> {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val name = "delivery-estimates-migration-test.db"
  context.deleteDatabase(name)
  val built = Room.databaseBuilder(context, PackageDatabase::class.java, name).allowMainThreadQueries().build()
  val d = built.orderDao()
  val orderId = d.insertOrder(OrderRecord(marketplace = "Shop", orderNumber = "12345678", name = "Order", archived = true, archivedAt = 42))
  val itemId = d.insertItem(OrderItemRecord(orderId = orderId, name = "Item"))
  val shipmentId = d.insertShipment(ShipmentRecord(orderId = orderId, status = "In Transit", estimatedDeliveryAt = 123, shippedAt = 50))
  d.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = shipmentId, number = tracking, carrier = "FedEx"))
  d.insertEvent(ShipmentTrackingEventRecord(shipmentId = shipmentId, description = "Keep", sourceMessageId = "event"))
  d.markProcessed(ProcessedGmailMessage(messageId = "manual", orderId = orderId, extractionVersion = MANUAL_ASSOCIATION_VERSION))
  d.recordChange(orderId, "Notes", "", "Keep", SOURCE_MANUAL)
  d.insertShipment(ShipmentRecord(orderId = null, title = "Standalone", archived = true, estimatedDeliveryAt = 456))
  built.close()
  val raw = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
  raw.execSQL("ALTER TABLE shipments DROP COLUMN estimatedDeliveryEndAt")
  raw.execSQL("ALTER TABLE shipments DROP COLUMN estimatedDeliverySourceMessageId")
  raw.execSQL("ALTER TABLE shipments DROP COLUMN estimatedDeliverySourceAt")
  raw.version = 12
  raw.close()
  val migrated = Room.databaseBuilder(context, PackageDatabase::class.java, name).addMigrations(MIGRATION_12_13).allowMainThreadQueries().build()
  try {
   val m = migrated.orderDao()
   assertEquals(2, m.allShipments().size)
   assertEquals(itemId, m.getItems(orderId).single().id)
   assertTrue(m.getOrder(orderId)!!.archived)
   assertEquals(42L, m.getOrder(orderId)!!.archivedAt)
   val shipment = m.getShipment(shipmentId)!!
   assertEquals(123L, shipment.estimatedDeliveryAt)
   assertEquals(50L, shipment.shippedAt)
   assertEquals("", shipment.estimatedDeliverySourceMessageId)
   assertNull(shipment.estimatedDeliveryEndAt)
   assertNull(shipment.estimatedDeliverySourceAt)
   assertEquals("event", m.observeAllEvents().first().single().sourceMessageId)
   assertEquals(1, m.allTrackingNumbers().size)
   assertEquals(MANUAL_ASSOCIATION_VERSION, m.allProcessed().single().extractionVersion)
   assertEquals(1, m.changesForOrder(orderId).size)
   assertNull(reviseDeliveryEstimate(shipment, DeliveryEstimate(LocalDate.of(2026, 10, 30)), mail("new")))
  } finally { migrated.close(); context.deleteDatabase(name) }
 }
}
