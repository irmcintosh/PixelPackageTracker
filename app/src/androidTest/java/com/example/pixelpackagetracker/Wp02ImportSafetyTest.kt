package com.example.pixelpackagetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// WP-02 matching, import safety, history and reanalysis. Uses an in-memory Room database only.
@RunWith(AndroidJUnit4::class)
class Wp02ImportSafetyTest {
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private val dao get() = db.orderDao()
 private val simpli = "Simpli Soda <orders@simplisoda.com>"
 private val namoki = "namokiMODS <hello@namokimods.com>"
 private val carrierSender = "FedEx <tracking@fedex.com>"

 @Before fun setUp() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  vm = TrackerVm(db, SettingsRepository(context))
 }

 @After fun tearDown() { db.close() }

 private fun mail(id: String, subject: String, body: String = "", sender: String = simpli, thread: String = "t$id") =
  GmailPreview(sender, subject, "Mon, 15 Jan 2024 10:00:00 +0000", "", thread, body, id)
 private fun confirmation(id: String, number: String) =
  mail(id, "Thanks for your order #$number", "Order number: $number\nOrder date: January 3, 2024")
 private fun shipped(id: String, number: String, tracking: String = "38429466112") =
  mail(id, "Your Simpli Soda order #$number has shipped", "Order #$number\nTracking number: $tracking\nCarrier: FedEx")
 private suspend fun seed(market: String, number: String, name: String = market, status: String = "Order Placed", statusAt: Long = System.currentTimeMillis()): OrderRecord =
  dao.getOrder(dao.insertOrder(OrderRecord(marketplace = market, orderNumber = number, name = name, status = status, statusUpdatedAt = statusAt)))!!
 private suspend fun fresh(order: OrderRecord) = dao.getOrder(order.id)!!
 private suspend fun scan(emails: List<GmailPreview>, vararg orders: OrderRecord, reanalyze: Boolean = true) =
  vm.updateOrdersFromEmails(emails, orders.map { fresh(it) }, reanalyze)
 private suspend fun historyFields(order: OrderRecord) = dao.changesForOrder(order.id).map { it.field }
 private fun group(vararg emails: GmailPreview) = groupedScanResults(emails.toList(), emptyList(), emptyList(), emptyList()).single()

 // --- 1. exact retailer/order matching and collisions ---

 @Test fun emailIsAppliedOnlyToTheRetailerThatSentIt() = runBlocking<Unit> {
  val a = seed("Simpli Soda", "ABC-12345")
  val b = seed("namokiMODS", "ABC-12345")
  val summary = scan(listOf(shipped("m1", "ABC-12345")), a, b)
  assertEquals(ScanOutcome.UPDATED, summary.results.first { it.orderId == a.id }.outcome)
  assertEquals(ScanOutcome.UNCHANGED, summary.results.first { it.orderId == b.id }.outcome)
  assertEquals(1, dao.getShipments(a.id).size)
  assertTrue(dao.getShipments(b.id).isEmpty())
  assertTrue(dao.changesForOrder(b.id).isEmpty())
 }

 @Test fun orderNumberPrefixIsNeverAMatch() = runBlocking<Unit> {
  val prefix = seed("Simpli Soda", "288559")
  val exact = seed("Simpli Soda", "28855918")
  scan(listOf(shipped("m1", "28855918")), prefix, exact)
  assertTrue(dao.getShipments(prefix.id).isEmpty())
  assertEquals(1, dao.getShipments(exact.id).size)
 }

 @Test fun sameOrderNumberAtTwoRetailersWithoutRetailerEvidenceGoesToReviewForBoth() = runBlocking<Unit> {
  val a = seed("Simpli Soda", "ABC-12345")
  val b = seed("namokiMODS", "ABC-12345")
  val forwarded = mail("m1", "Fwd: Order #ABC-12345", "Simpli Soda and namokiMODS\nTracking number: 38429466112", sender = "Ian <ian@gmail.com>")
  val summary = scan(listOf(forwarded), a, b)
  assertEquals(listOf(ScanOutcome.REVIEW_REQUIRED, ScanOutcome.REVIEW_REQUIRED), summary.results.map { it.outcome })
  assertEquals(0, summary.updated)
  assertTrue(dao.allShipments().isEmpty() && dao.allTrackingNumbers().isEmpty() && dao.allProcessed().isEmpty())
 }

 @Test fun trackingNumberClaimedByTwoOrdersIsNotAssignedToEither() = runBlocking<Unit> {
  val a = seed("Simpli Soda", "28855918")
  val b = seed("namokiMODS", "NM-77777")
  val summary = scan(listOf(shipped("m1", "28855918"),
   mail("m2", "Order #NM-77777 shipped", "Tracking number: 38429466112\nCarrier: FedEx", sender = namoki)), a, b)
  assertEquals(listOf(ScanOutcome.REVIEW_REQUIRED, ScanOutcome.REVIEW_REQUIRED), summary.results.map { it.outcome })
  assertTrue(dao.allTrackingNumbers().isEmpty())
  assertTrue(dao.allShipments().isEmpty())
 }

 @Test fun trackingNumberAlreadySavedOnAnotherOrderIsNeverMovedByRetailerMail() = runBlocking<Unit> {
  val a = seed("Simpli Soda", "28855918")
  val b = seed("namokiMODS", "NM-77777")
  val sid = dao.insertShipment(ShipmentRecord(orderId = b.id, status = "In Transit"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = "38429466112", carrier = "FedEx"))
  val summary = scan(listOf(shipped("m1", "28855918")), a, b)
  assertEquals(ScanOutcome.REVIEW_REQUIRED, summary.results.first { it.orderId == a.id }.outcome)
  assertEquals(1, dao.allTrackingNumbers().size)
  assertTrue(dao.getShipments(a.id).isEmpty())
 }

 // --- 2. correspondence grouping under one order ---

 @Test fun retailerAndCarrierEmailsAreGroupedUnderTheOrderWithoutDuplicates() = runBlocking<Unit> {
  val a = seed("Simpli Soda", "28855918")
  val other = seed("namokiMODS", "NM-77777")
  val emails = listOf(
   mail("m1", "Thanks for your order #28855918", "Order date: January 3, 2024"),
   shipped("m2", "28855918"),
   mail("m3", "Your shipment 38429466112 has been delivered", sender = carrierSender))
  val result = scan(emails + emails, a, other).results.first { it.orderId == a.id }
  assertEquals(ScanOutcome.UPDATED, result.outcome)
  assertEquals(setOf("m1", "m2", "m3"), dao.allProcessed().filter { it.orderId == a.id }.map { it.messageId }.toSet())
  assertEquals(3, dao.allProcessed().size)
  assertEquals(1, dao.getShipments(a.id).size)
  assertEquals(1, dao.allTrackingNumbers().size)
  assertEquals("Delivered", dao.getShipments(a.id).single().status)
  assertNotNull(fresh(a).placedAt)
  assertEquals("Shipped", fresh(a).status)
  assertTrue(dao.changesForOrder(other.id).isEmpty() && dao.getShipments(other.id).isEmpty())
 }

 @Test fun retailerShippedNoticeWithoutTrackingCreatesAShipmentButNoTrackingNumber() = runBlocking<Unit> {
  val order = seed("eBay", "11-22222-33333")
  val notice = mail("e1", "Your item has shipped", "Order number: 11-22222-33333. Your item is with its carrier USPS.", sender = "eBay <ebay@ebay.com>")
  val result = scan(listOf(notice), order).results.single()
  assertEquals(ScanOutcome.UPDATED, result.outcome)
  assertEquals("In Transit", dao.getShipments(order.id).single().status)
  assertTrue(dao.allTrackingNumbers().isEmpty())
  assertEquals(ScanOutcome.UNCHANGED, scan(listOf(notice), order).results.single().outcome)
  assertEquals(1, dao.getShipments(order.id).size)
 }

 @Test fun secondTrackingNumberWithoutHandoffEvidenceIsNotAssumedToBeAHandoff() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  scan(listOf(shipped("m1", "28855918", "38429466112")), order)
  val second = scan(listOf(shipped("m2", "28855918", "9400111899223344556677")), order)
  assertEquals(ScanOutcome.REVIEW_REQUIRED, second.results.single().outcome)
  assertEquals(1, dao.getShipments(order.id).size)
  assertEquals(listOf("38429466112"), dao.allTrackingNumbers().map { it.number })
  assertEquals(setOf("m1"), dao.allProcessed().map { it.messageId }.toSet())
 }

 @Test fun secondTrackingNumberWithExplicitCarrierHandoffWordingIsRecordedAsHandoff() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  scan(listOf(shipped("m1", "28855918", "38429466112")), order)
  val handoff = mail("m2", "Your Simpli Soda order #28855918 update", "Order #28855918\nYour package was handed off to USPS for final delivery.\nTracking number: 9400111899223344556677\nCarrier: USPS")
  assertEquals(ScanOutcome.UPDATED, scan(listOf(handoff), order).results.single().outcome)
  assertEquals(1, dao.getShipments(order.id).size)
  assertEquals(listOf("primary", "handoff"), dao.allTrackingNumbers().map { it.role })
  assertTrue("Additional tracking number" in historyFields(order))
 }

 // --- 3. transactional import safety ---

 @Test fun failedImportRollsBackEverythingAndLeavesNoProcessedMarkers() = runBlocking<Unit> {
    val g = group(confirmation("c1", "28855918"), shipped("m1", "28855918"))
  db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_marker BEFORE INSERT ON processed_gmail_messages BEGIN SELECT RAISE(ABORT, 'forced failure'); END")
  val outcome = vm.importScanGroup(g)
  assertTrue(outcome, outcome.startsWith("Failed"))
  assertTrue(dao.allOrders().isEmpty() && dao.allShipments().isEmpty() && dao.allTrackingNumbers().isEmpty() && dao.allProcessed().isEmpty())
  db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_marker")
  assertTrue(vm.importScanGroup(g).startsWith("Imported"))
  assertEquals(1, dao.allOrders().size)
  assertEquals(setOf("c1", "m1"), dao.allProcessed().map { it.messageId }.toSet())
 }

 @Test fun importNeedingReviewLeavesNoPartialChanges() = runBlocking<Unit> {
  val owner = seed("namokiMODS", "NM-77777")
  val sid = dao.insertShipment(ShipmentRecord(orderId = owner.id, status = "In Transit"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = "38429466112", carrier = "FedEx"))
    val outcome = vm.importScanGroup(group(confirmation("c1", "28855918"), shipped("m1", "28855918")))
  assertTrue(outcome, outcome.startsWith("Review required"))
  assertEquals(1, dao.allOrders().size)
  assertEquals(1, dao.allShipments().size)
  assertEquals(1, dao.allTrackingNumbers().size)
  assertTrue(dao.allProcessed().isEmpty() && dao.changesForOrder(owner.id).isEmpty())
 }

 @Test fun importingTheSameGroupTwiceDoesNotDuplicateOrdersShipmentsTrackingOrHistory() = runBlocking<Unit> {
    val g = group(confirmation("c1", "28855918"), shipped("m1", "28855918"), mail("m2", "Your shipment 38429466112 is on the way", sender = carrierSender))
  assertTrue(vm.importScanGroup(g).startsWith("Imported"))
  val order = dao.allOrders().single()
  assertEquals("Shipped", order.status)
  assertEquals("In Transit", dao.getShipments(order.id).single().status)
  assertTrue("A new order's initial values are not recorded as updates", dao.changesForOrder(order.id).isEmpty())
  assertTrue(vm.importScanGroup(g).startsWith("Existing order"))
  assertEquals(1, dao.allOrders().size); assertEquals(1, dao.allShipments().size); assertEquals(1, dao.allTrackingNumbers().size)
  assertTrue(dao.changesForOrder(order.id).isEmpty())
  assertEquals(3, dao.allProcessed().size)
  // A following Update scan finds the imported messages already processed.
  assertEquals(ScanOutcome.UNCHANGED, scan(listOf(shipped("m1", "28855918")), order).results.single().outcome)
 }

 @Test fun importLinksAStandaloneShipmentInsteadOfCreatingASecondOne() = runBlocking<Unit> {
  val sid = dao.insertShipment(ShipmentRecord(orderId = null, status = "In Transit", title = "Loose parcel"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = "38429466112", carrier = "FedEx"))
    assertTrue(vm.importScanGroup(group(confirmation("c1", "28855918"), shipped("m1", "28855918"))).startsWith("Imported"))
  val order = dao.allOrders().single()
  assertEquals(order.id, dao.getShipment(sid)!!.orderId)
  assertEquals(1, dao.allShipments().size); assertEquals(1, dao.allTrackingNumbers().size)
 }

 @Test fun aShippingNoticeWithTooLittleOrderEvidenceCannotCreateAnOrderButAGenuineOneCan() = runBlocking<Unit> {
  val thin = mail("m0", "Your Simpli Soda order #28855918 has shipped", "Order #28855918")
  val outcome = vm.importScanGroup(ScanGroup("review:m0", "Simpli Soda", "28855918", null, null, listOf(thin), null))
  assertTrue(outcome, outcome.startsWith("Review required"))
  assertTrue(dao.allOrders().isEmpty() && dao.allShipments().isEmpty() && dao.allProcessed().isEmpty())
  val genuine = group(shipped("m1", "28855918"))
  assertTrue(genuine.establishesOrder)
  assertTrue(vm.importScanGroup(genuine).startsWith("Imported order #28855918"))
  assertEquals("FedEx", dao.getShipments(dao.allOrders().single().id).single().carrier)
 }

 @Test fun sameTrackingInTwoSpellingsImportsAsOneTrackingNumberOnOneShipment() = runBlocking<Unit> {
  val plain = mail("m1", "Your Simpli Soda order #4RZ128844G has shipped", "Order #4RZ128844G\nTracking number: 9200190323035502367037\nCarrier: USPS")
  val prefixed = mail("m2", "Your Simpli Soda order #4RZ128844G update", "Order #4RZ128844G\nTracking number: 420286019200190323035502367037\nCarrier: USPS")
  val group = group(confirmation("c1", "4RZ128844G"), plain, prefixed)

  assertTrue(vm.importScanGroup(group).startsWith("Imported order #4RZ128844G"))

  val order = dao.allOrders().single()
  assertEquals(1, dao.getShipments(order.id).size)
  assertEquals(listOf("9200190323035502367037"), dao.allTrackingNumbers().map { it.number })
  assertEquals(setOf("c1", "m1", "m2"), dao.allProcessed().map { it.messageId }.toSet())
 }

 @Test fun multiTrackingEmailWithoutPackageOrHandoffEvidenceImportsTheOrderAndLeavesTheEmailForReview() = runBlocking<Unit> {
  val multiTracking = mail("m1", "Your Simpli Soda order #4RZ128844G has shipped",
   "Order #4RZ128844G\nTracking number: 1Z999AA10123456784\nTracking number: 9400111899223344556677\nCarrier: FedEx")
  val groups = groupedScanResults(listOf(confirmation("c1", "4RZ128844G"), multiTracking), emptyList(), emptyList(), emptyList())
  val group = groups.single { !it.key.startsWith("review:") }
  assertEquals(setOf("c1"), group.emails.map { it.messageId }.toSet())
  assertEquals(setOf("m1"), groups.single { it.key.startsWith("review:") }.emails.map { it.messageId }.toSet())

  val outcome = vm.importScanGroup(group)
  assertTrue(outcome, outcome.startsWith("Imported order #4RZ128844G"))

  val order = dao.allOrders().single()
  assertEquals("Order Placed", order.status)
  assertEquals(setOf("c1"), dao.allProcessed().map { it.messageId }.toSet())
  assertTrue(dao.getShipments(order.id).isEmpty() && dao.allTrackingNumbers().isEmpty())
 }

 @Test fun multiTrackingEmailWithSeparatePackageWordingCreatesOneChildShipmentPerPackage() = runBlocking<Unit> {
  val multi = mail("m1", "Your Simpli Soda order #4RZ128844G has shipped",
   "Order #4RZ128844G\nPackage 1 of 2\nTracking number: 1Z999AA10123456784\nPackage 2 of 2\nTracking number: 9400111899223344556677")
  assertTrue(vm.importScanGroup(group(confirmation("c1", "4RZ128844G"), multi)).startsWith("Imported order #4RZ128844G"))
  val order = dao.allOrders().single()
  val shipments = dao.getShipments(order.id)
  assertEquals(2, shipments.size)
  assertTrue(shipments.all { it.orderId == order.id && dao.getTrackingNumbersForShipment(it.id).size == 1 })
  assertEquals(setOf("c1", "m1"), dao.allProcessed().map { it.messageId }.toSet())
 }

 @Test fun multiTrackingEmailWithCarrierHandoffWordingImportsOneShipmentWithHandoff() = runBlocking<Unit> {
  val multi = mail("m1", "Your Simpli Soda order #4RZ128844G has shipped",
   "Order #4RZ128844G\nTracking number: 1Z999AA10123456784\nTracking number: 9400111899223344556677\nHanded off to USPS for final delivery.")
  val group = group(confirmation("c1", "4RZ128844G"), multi)
  assertTrue(vm.importScanGroup(group).startsWith("Imported order #4RZ128844G"))
  val order = dao.allOrders().single()
  val shipment = dao.getShipments(order.id).single()
  assertEquals(listOf("primary", "handoff"), dao.getTrackingNumbersForShipment(shipment.id).map { it.role })
  assertTrue(vm.importScanGroup(group).startsWith("Existing order"))
  assertEquals(2, dao.allTrackingNumbers().size)
 }

 @Test fun orderShipmentTrackingHierarchyIsStoredThroughForeignKeysAndKeepsEachOrdersChildrenApart() = runBlocking<Unit> {
  val first = group(confirmation("c1", "28855918"), shipped("m1", "28855918", "38429466112"))
  val second = group(mail("c2", "Thanks for your order #NM-77777", "Order number: NM-77777", sender = namoki),
   mail("m2", "Order #NM-77777 has shipped", "Order #NM-77777\nTracking number: 1Z999AA10123456784\nCarrier: UPS", sender = namoki))
  listOf(first, second).forEach { assertTrue(vm.importScanGroup(it).startsWith("Imported")) }
  val orders = dao.allOrders()
  assertEquals(2, orders.size)
  orders.forEach { order ->
   val shipment = dao.getShipments(order.id).single()
   assertEquals(order.id, shipment.orderId)
   assertEquals(1, dao.getTrackingNumbersForShipment(shipment.id).size)
   assertTrue(dao.allProcessed().filter { it.orderId == order.id }.map { it.messageId }.toSet().size == 2)
  }
  assertEquals(2, dao.allShipments().size)
  assertEquals(2, dao.allTrackingNumbers().size)
 }

 @Test fun importOfExistingOrderRecordsBackfillHistoryWithGmailProvenance() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val email = mail("m1", "Thanks for your order #28855918", "Order date: January 3, 2024\nOrder total: \$45.00", thread = "tt")
  val outcome = vm.importScanGroup(group(email))
  assertTrue(outcome, outcome.startsWith("Existing order"))
  val rows = dao.changesForOrder(order.id)
  assertTrue("Placed date" in rows.map { it.field })
  assertTrue(rows.all { it.sourceMessageId == "m1" && it.sourceDate.isNotBlank() })
 }

 @Test fun scanAndImportRunningTogetherCreateNoDuplicates() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
    val g = group(confirmation("c1", "28855918"), shipped("m1", "28855918"))
  val other = TrackerVm(db, SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext))
  listOf(
   async(Dispatchers.Default) { vm.importScanGroup(g) },
   async(Dispatchers.Default) { other.updateOrdersFromEmails(listOf(shipped("m1", "28855918")), listOf(order)).updated.toString() },
   async(Dispatchers.Default) { vm.updateOrdersFromEmails(listOf(shipped("m1", "28855918")), listOf(order)).updated.toString() }
  ).awaitAll()
  assertEquals(1, dao.allOrders().size); assertEquals(1, dao.getShipments(order.id).size); assertEquals(1, dao.allTrackingNumbers().size)
  assertEquals(2, dao.allProcessed().size)
  assertEquals(1, dao.changesForOrder(order.id).count { it.field == "Tracking number" })
 }

 // --- 4. change history ---

 @Test fun manualEditRecordsOldAndNewValuesWithManualProvenanceAndNothingElse() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918", name = "Soda")
  val sid = dao.insertShipment(ShipmentRecord(orderId = order.id, status = "Awaiting Tracking"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = "11111111111", carrier = "FedEx"))
  val draft = OrderDraft(editId = order.id, market = "Simpli Soda", name = "Soda bundle", number = "28855918", status = "Processing", notes = "gift",
   shipments = listOf(ShipmentDraft(title = "Box 1", number = "38429466112", carrier = "FedEx", status = "In Transit", confirmed = true)))
  assertEquals(SaveOutcome.Saved, vm.saveOrder(draft))
  val rows = dao.changesForOrder(order.id)
  assertEquals(setOf("Title", "Notes", "Order status", "Shipment title", "Shipment status", "Tracking number"), rows.map { it.field }.toSet())
  assertTrue(rows.all { it.sourceMessageId == SOURCE_MANUAL })
  rows.single { it.field == "Title" }.let { assertEquals("Soda", it.oldValue); assertEquals("Soda bundle", it.newValue) }
  rows.single { it.field == "Tracking number" }.let { assertEquals("11111111111", it.oldValue); assertEquals("38429466112", it.newValue) }
  rows.single { it.field == "Shipment status" }.let { assertEquals("Awaiting Tracking", it.oldValue); assertEquals("In Transit", it.newValue) }
  // Saving identical data again is bookkeeping only: no history rows and no timestamp churn.
  val orderBefore = fresh(order); val shipBefore = dao.getShipment(sid)!!
  assertEquals(SaveOutcome.Saved, vm.saveOrder(draft))
  assertEquals(rows.size, dao.changesForOrder(order.id).size)
  assertEquals(orderBefore, fresh(order)); assertEquals(shipBefore, dao.getShipment(sid))
 }

 @Test fun manualSaveRejectsATrackingNumberThatBelongsToAnotherShipment() = runBlocking<Unit> {
  val a = seed("Simpli Soda", "28855918")
  val sid = dao.insertShipment(ShipmentRecord(orderId = a.id, status = "In Transit"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = "38429466112", carrier = "FedEx"))
  val b = seed("namokiMODS", "NM-77777", name = "Mods")
  val draft = OrderDraft(editId = b.id, market = "namokiMODS", name = "Changed", number = "NM-77777", status = "Order Placed",
   shipments = listOf(ShipmentDraft(number = "3842 9466 112", carrier = "FedEx", status = "In Transit", confirmed = true)))
  assertTrue(vm.saveOrder(draft) is SaveOutcome.Blocked)
  assertEquals("Mods", fresh(b).name)
  assertEquals(1, dao.allTrackingNumbers().size); assertEquals(1, dao.allShipments().size)
  assertTrue(dao.changesForOrder(b.id).isEmpty())
 }

 @Test fun shipmentAndTrackingEditsAreRecordedAndDuplicatesRefused() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val other = seed("namokiMODS", "NM-77777")
  assertEquals(SaveOutcome.Saved, vm.addShipmentToOrder(order.id, ShipmentDraft(number = "38429466112", carrier = "FedEx", status = "In Transit")))
  val sid = dao.getShipments(order.id).single().id
  assertTrue(vm.addShipmentToOrder(other.id, ShipmentDraft(number = "38429466112", carrier = "FedEx")) is SaveOutcome.Blocked)
  assertEquals(SaveOutcome.Saved, vm.addTrackingNumber(sid, "9400111899223344556677", "USPS"))
  assertEquals(SaveOutcome.Saved, vm.addTrackingNumber(sid, "9400111899223344556677", "USPS"))
  assertEquals(2, dao.getTrackingNumbersForShipment(sid).size)
  val first = dao.getTrackingNumbersForShipment(sid).first()
  assertEquals(SaveOutcome.Saved, vm.editShipment(sid, first.id, "38429466113", "FedEx", 1_800_000_000_000))
  assertEquals(SaveOutcome.Saved, vm.setShipmentStatus(sid, "Delivered"))
  val rows = dao.changesForOrder(order.id)
  assertEquals(listOf("Shipment", "Tracking number", "Order status", "Order archive", "Additional tracking number", "Estimated delivery", "Tracking number", "Shipment status"), rows.map { it.field })
  assertEquals(SOURCE_DERIVED, rows.single { it.field == "Order status" }.sourceMessageId)
  assertEquals("Shipped", fresh(order).status)
  assertTrue(fresh(order).archived)
  // Re-applying an identical shipment status writes nothing, and delivery never becomes an order status.
  val count = rows.size
  vm.setShipmentStatus(sid, "Delivered")
  assertEquals(count, dao.changesForOrder(order.id).size)
 }

 @Test fun linkingAStandaloneShipmentIsRecordedOnTheOrder() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val sid = dao.insertShipment(ShipmentRecord(orderId = null, title = "Loose parcel"))
  assertEquals(SaveOutcome.Saved, vm.linkShipmentToOrder(sid, order.id))
  assertEquals(order.id, dao.getShipment(sid)!!.orderId)
  assertEquals(listOf("Shipment linked"), historyFields(order))
 }

 // --- 5. scan results / repeated scans ---

 @Test fun threeScansOfAnUnchangedInboxReportExactlyOneSetOfChanges() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val inbox = listOf(shipped("m1", "28855918"))
  val first = scan(inbox, order)
  assertEquals(ScanOutcome.UPDATED, first.results.single().outcome)
  val historyAfterFirst = dao.changesForOrder(order.id)
  assertEquals(first.fieldChanges, historyAfterFirst.size)
  repeat(2) { assertEquals(ScanOutcome.UNCHANGED, scan(inbox, order).results.single().outcome) }
  assertEquals(historyAfterFirst, dao.changesForOrder(order.id))
  assertNotNull(fresh(order).lastSyncedAt)
 }

 @Test fun scanSummaryCountsDistinguishUpdatedUnchangedReviewAndFailed() = runBlocking<Unit> {
  val updated = seed("Simpli Soda", "28855918")
  val unchanged = seed("namokiMODS", "NM-77777")
  val ghost = OrderRecord(id = 4242, marketplace = "Ghost", orderNumber = "GH-99999", name = "Ghost")
  val summary = vm.updateOrdersFromEmails(listOf(shipped("m1", "28855918")), listOf(updated, unchanged, ghost))
  assertEquals(listOf(ScanOutcome.UPDATED, ScanOutcome.UNCHANGED, ScanOutcome.FAILED), summary.results.map { it.outcome })
  assertEquals("1 updated · 1 unchanged · 0 need review · 1 failed", summary.headline())
 }

 @Test fun failedScanLeavesNoMarkersOrPartialData() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_history BEFORE INSERT ON order_change_history BEGIN SELECT RAISE(ABORT, 'forced failure'); END")
  assertEquals(ScanOutcome.FAILED, scan(listOf(shipped("m1", "28855918")), order).results.single().outcome)
  assertTrue(dao.allProcessed().isEmpty() && dao.allShipments().isEmpty() && dao.allTrackingNumbers().isEmpty())
  assertNull(fresh(order).lastSyncedAt)
 }

 // --- 6. parser-version reanalysis ---

 @Test fun olderParserVersionIsReanalyzedOnDemandAsBackfillWithoutDuplicates() = runBlocking<Unit> {
  val order = dao.getOrder(dao.insertOrder(OrderRecord(marketplace = "Simpli Soda", orderNumber = "28855918", name = "Soda", notes = "my own note", placedAt = 1_700_000_000_000)))!!
  val sid = dao.insertShipment(ShipmentRecord(orderId = order.id, status = "Awaiting Tracking"))
  dao.markProcessed(ProcessedGmailMessage(messageId = "m1", orderId = order.id, extractionVersion = GMAIL_PARSER_VERSION - 1))
  val inbox = listOf(shipped("m1", "28855918"))
  // Background scans do not reanalyze older markers.
  assertEquals(ScanOutcome.UNCHANGED, scan(inbox, order, reanalyze = false).results.single().outcome)
  assertTrue(dao.allTrackingNumbers().isEmpty())
  val result = scan(inbox, order).results.single()
  assertEquals(ScanOutcome.UPDATED, result.outcome)
  assertEquals(ChangeKind.BACKFILL, result.changes.single { it.field == "Tracking number" }.kind)
  assertEquals(GMAIL_PARSER_VERSION, dao.allProcessed().single().extractionVersion)
  assertEquals(1, dao.allProcessed().size); assertEquals(1, dao.getShipments(order.id).size); assertEquals(1, dao.allTrackingNumbers().size)
  assertEquals(sid, dao.getShipments(order.id).single().id)
  assertEquals("my own note", fresh(order).notes); assertEquals(1_700_000_000_000, fresh(order).placedAt)
  val count = dao.changesForOrder(order.id).size
  assertEquals(ScanOutcome.UNCHANGED, scan(inbox, order).results.single().outcome)
  assertEquals(count, dao.changesForOrder(order.id).size)
 }

 // --- 7. Orders tab state / manual status rule ---

 @Test fun manualOrderStatusSurvivesGmailWhileShipmentStatusStillAdvances() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  assertEquals(SaveOutcome.Saved, vm.setOrderStatus(order.id, "Processing"))
  val result = scan(listOf(shipped("m1", "28855918")), order).results.single()
  assertEquals(ScanOutcome.UPDATED, result.outcome)
  assertTrue(result.changes.none { it.field == "Order status" })
  assertEquals("Processing", fresh(order).status)
  assertEquals("In Transit", dao.getShipments(order.id).single().status)
  withTimeout(5_000) {
   vm.orders.first { list -> list.any { it.id == order.id && it.status == "Processing" } }
   vm.shipments.first { list -> list.any { it.orderId == order.id && it.status == "In Transit" } }
  }
 }

 @Test fun gmailAdvancesAnOrderStatusThatWasOnlyDerivedOrNeverSet() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val result = scan(listOf(shipped("m1", "28855918")), order).results.single()
  assertEquals("Shipped", result.changes.single { it.field == "Order status" }.after)
  assertEquals("Shipped", fresh(order).status)
 }

 @Test fun ordersFlowShowsPersistedGmailChangesAndMovesTheUpdatedOrderToTheTop() = runBlocking<Unit> {
  val older = seed("Simpli Soda", "28855918", statusAt = 1_000)
  val newer = seed("namokiMODS", "NM-77777", statusAt = 2_000)
  withTimeout(5_000) { assertEquals(newer.id, vm.orders.first { it.size == 2 }.first().id) }
  scan(listOf(shipped("m1", "28855918")), older, newer)
  withTimeout(5_000) {
   val shown = vm.orders.first { list -> list.firstOrNull()?.id == older.id }
   assertEquals("Shipped", shown.first().status)
   assertNotNull(shown.first().lastSyncedAt)
   vm.numbers.first { list -> list.any { it.number == "38429466112" } }
   assertEquals(dao.changesForOrder(older.id).size, dao.observeChanges(older.id).first().size)
  }
 }
}
