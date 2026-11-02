package com.example.pixelpackagetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.Charset

// Regression tests for the three confirmed real-world failures (Simpli Soda, eBay, DSWATCH / DS Designer).
// Messages are built as Gmail API JSON and go through parseGmailMessage, then the real scan/import/association code and Room.
@RunWith(AndroidJUnit4::class)
class Wp02AssociationRegressionTest {
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private lateinit var prefs: SettingsRepository
 private var savedAliases: List<Set<String>> = emptyList()
 private val dao get() = db.orderDao()
 private val tracking = "38429466112"

 @Before fun setUp() = runBlocking<Unit> {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  prefs = SettingsRepository(context)
  savedAliases = prefs.aliasGroups()
  prefs.clearAliases()
  vm = TrackerVm(db, prefs)
 }

 // The real settings store is shared with the installed app, so the user's aliases are put back.
 @After fun tearDown() = runBlocking<Unit> { prefs.clearAliases(); savedAliases.forEach { prefs.addAliasGroup(it) }; db.close() }

 // --- Gmail API JSON fixtures ---
 private fun b64(text: String, charset: Charset = Charsets.UTF_8) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(charset))
 private fun textPart(mime: String, text: String, charset: String = "UTF-8") = JSONObject().put("mimeType", mime)
  .put("headers", JSONArray().put(JSONObject().put("name", "Content-Type").put("value", "$mime; charset=\"$charset\"")))
  .put("body", JSONObject().put("data", b64(text, Charset.forName(charset))))
 private fun gmail(id: String, thread: String, from: String, subject: String, html: String? = null, plain: String? = null, date: String = "Mon, 15 Jan 2024 10:00:00 +0000"): GmailPreview {
  val parts = JSONArray()
  plain?.let { parts.put(textPart("text/plain", it)) }
  html?.let { parts.put(textPart("text/html", it)) }
  val headers = JSONArray().put(JSONObject().put("name", "From").put("value", from)).put(JSONObject().put("name", "Subject").put("value", subject)).put(JSONObject().put("name", "Date").put("value", date))
  return parseGmailMessage(JSONObject().put("id", id).put("threadId", thread).put("snippet", "")
   .put("payload", JSONObject().put("mimeType", "multipart/alternative").put("headers", headers).put("parts", parts)))
 }
 private val simpli = "Simpli Soda <orders@simplisoda.com>"
 private val fedex = "FedEx <TrackingUpdates@fedex.com>"

 private fun simpliConfirmation(thread: String = "T-conf") = gmail("conf", thread, simpli, "Order #28855918 confirmed", date = "Wed, 03 Jan 2024 09:00:00 +0000",
  html = "<html><head><style>.x{color:red}</style></head><body><h1>Thank you for your order!</h1><p>Order number: <b>28855918</b></p><p>Order date: January 3, 2024</p>" +
   "<table><tr><td>Item: Root Beer Variety Pack</td></tr><tr><td>Qty: 2</td></tr><tr><td>Price: \$24.00</td></tr></table><p>Order total: \$48.00</p>" +
   "<a href=\"https://simplisoda.com/account/orders/abc123\">View your order</a></body></html>")
 private fun simpliShipping(withOrderNumber: Boolean, thread: String, withTracking: Boolean = true) = gmail("ship", thread, simpli,
  if(withOrderNumber) "Your order #28855918 has shipped" else "Your order has shipped",
  html = "<p>Your order has shipped via FedEx!</p>" + if(withTracking) "<p>Putting the tracking number below into FedEx:&nbsp;<strong>$tracking</strong></p>" else "")
 private fun fedexMail() = gmail("fx", "T-fx", fedex, "FedEx Delivery Manager: Your package from Simpli Soda is on its way",
  html = "<table><tr><td>TRACKING NUMBER</td><td><a href=\"https://www.fedex.com/fedextrack/?trknbr=$tracking\">$tracking</a></td></tr><tr><td>FROM</td><td>SIMPLI SODA</td></tr></table><p>Your package is on its way.</p>")

 private suspend fun seed(market: String, number: String, name: String = market, status: String = "Order Placed", notes: String = ""): OrderRecord =
  dao.getOrder(dao.insertOrder(OrderRecord(marketplace = market, orderNumber = number, name = name, status = status, notes = notes)))!!
 private suspend fun scan(emails: List<GmailPreview>, vararg orders: OrderRecord, reanalyze: Boolean = true) =
  vm.updateOrdersFromEmails(emails, orders.map { dao.getOrder(it.id)!! }, reanalyze)
 private suspend fun fields(order: OrderRecord) = dao.changesForOrder(order.id).map { it.field }

 @Test fun orderSpecificUpdateDoesNotShowUnrelatedDuplicatesButUpdateAllStillDoes() = runBlocking<Unit> {
  val paypal = seed("PayPal", "40FQ6HG")
  val dsWatch = seed("DSWATCH", "DSDF66669184")
  val dsDesigner = seed("DS Designer", "DSDF66669184")

  val targeted = vm.updateOrdersFromEmails(emptyList(), listOf(paypal), orderSpecific = true)
  assertEquals(listOf(paypal.id), targeted.results.map { it.orderId })
  assertTrue(targeted.reviews.none { it is ReviewItem.PossibleDuplicate })
  assertEquals(1, findDuplicateOrders(dao.allOrders()).size)

  val updateAll = vm.updateOrdersFromEmails(emptyList(), dao.allOrders())
  assertTrue(updateAll.reviews.none { it is ReviewItem.PossibleDuplicate })
  val globalDuplicate = findDuplicateOrders(dao.allOrders()).single()
  assertEquals(setOf(dsWatch.id, dsDesigner.id), setOf(globalDuplicate.first.id, globalDuplicate.second.id))
 }

 @Test fun inboxImportAndSavedOrderUpdateReuseTheSameNormalizedRetailerOrderIdentity() = runBlocking<Unit> {
  val saved = seed("PayPal", "40-FQ-6HG")
  val email = gmail("paypal-order", "T-paypal", "PayPal <orders@paypal.example>", "Order #40FQ6HG has shipped",
   html = "<p>Order number: 40FQ6HG</p><p>Your order has shipped.</p>")
  val update = scan(listOf(email), saved)
  assertEquals(ScanOutcome.UPDATED, update.results.single().outcome)
  assertEquals(1, dao.allOrders().size)

  val group = groupedScanResults(listOf(email), dao.allTrackingNumbers(), dao.allShipments(), dao.allOrders()).single()
  assertEquals("PayPal", group.title)
  assertTrue(vm.importScanGroup(group).startsWith("Existing order"))
  assertEquals(1, dao.allOrders().size)
 }

 @Test fun sameGmailThreadWithLeadingLetterOrderVariantUpdatesTheUniqueExistingOrder() = runBlocking<Unit> {
  val saved = seed("PayPal", "40FQ6HG")
  val original = gmail("pay-original", "pay-thread", "Customer Service <help@paypal.com>", "Order #M40FQ6HG confirmed")
  val shipping = gmail("pay-shipped", "pay-thread", "service@paypal.com", "Order #40FQ6HG shipped", html = "<p>Order #40FQ6HG</p><p>Shipped with USPS.</p>")

  val summary = vm.updateOrdersFromEmails(listOf(original, shipping), listOf(saved), orderSpecific = true)

  assertEquals(ScanOutcome.UPDATED, summary.results.single().outcome)
  assertEquals(1, dao.allOrders().size)
  assertEquals(saved.id, dao.allProcessed().single { it.messageId == "pay-shipped" }.orderId)
  assertEquals("In Transit", dao.getShipments(saved.id).single().status)
 }

 @Test fun inboxPreviewKeepsAThreadMatedOrderNumberVariantSeparateAndNeverImportsItAutomatically() = runBlocking<Unit> {
  val confirmation = gmail("pay-import-original", "pay-import-thread", "Customer Service <help@paypal.com>", "Order #M40FQ6HG confirmed")
  val shipping = gmail("pay-import-shipping", "pay-import-thread", "service@paypal.com", "Order #40FQ6HG shipped", html = "<p>Order #40FQ6HG</p><p>Your package shipped with USPS.</p>")
  val groups = groupedScanResults(listOf(confirmation, shipping), emptyList(), emptyList(), emptyList())
  assertEquals(2, groups.size)
  assertEquals(setOf("M40FQ6HG", "40FQ6HG"), groups.map { it.orderNumber }.toSet())
  assertTrue(groups.all { it.review?.startsWith("Conflicting identifiers") == true })
  assertTrue(dao.allOrders().isEmpty() && dao.allProcessed().isEmpty())
 }

 private suspend fun assertSimpliEndState(order: OrderRecord, orderCount: Int = 1) {
  assertEquals(orderCount, dao.allOrders().size)
  val ship = dao.getShipments(order.id).single()
  val number = dao.getTrackingNumbersForShipment(ship.id).single()
  assertEquals(tracking, number.number); assertEquals("FedEx", number.carrier)
  assertEquals(1, dao.allTrackingNumbers().size)
  assertEquals(setOf("conf", "ship", "fx"), dao.allProcessed().map { it.messageId }.toSet())
  assertEquals(3, dao.allProcessed().size)
  assertEquals("In Transit", ship.status)
  assertEquals("Shipped", dao.getOrder(order.id)!!.status)
 }

 // ---------- Case A: Simpli Soda ----------

 @Test fun simpliSodaThreeEmailsProduceOneOrderOneFedExShipmentAndThreeCorrespondenceRecords() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918", notes = "Order total: \$48.00")
  val inbox = listOf(simpliConfirmation(), simpliShipping(true, "T-ship"), fedexMail())
  val first = scan(inbox + inbox, order)
  val result = first.results.single()
  assertEquals(ScanOutcome.UPDATED, result.outcome)
  assertEquals(setOf("conf", "ship", "fx"), result.diagnostics.map { it.messageId }.toSet())
  assertTrue(result.diagnostics.all { it.retailer == "Simpli Soda" && it.matchingEvidence.isNotBlank() && it.result.isNotBlank() })
  assertTrue(result.diagnostics.all { it.candidateSavedOrderIds == listOf(order.id) && it.databaseCommitted })
  assertEquals(listOf(tracking), result.diagnostics.single { it.messageId == "ship" }.trackingNumbers)
  assertSimpliEndState(order)
  val change = result.changes.single { it.field == "Tracking number" }
  assertEquals("Not available", change.before); assertEquals(tracking, change.after)
  assertTrue(change.source!!.contains("Simpli Soda"))
  assertTrue(result.changes.any { it.field == "Shipment status" && it.after == "In Transit" })
  assertNotNull(dao.getOrder(order.id)!!.placedAt)
  assertEquals("Root Beer Variety Pack", dao.getItems(order.id).single().name)
  assertEquals("https://simplisoda.com/account/orders/abc123", dao.getOrder(order.id)!!.orderUrl)
  // Repeat scans are idempotent.
  val history = dao.changesForOrder(order.id)
  repeat(2) { assertEquals(ScanOutcome.UNCHANGED, scan(inbox, order).results.single().outcome) }
  assertEquals(history, dao.changesForOrder(order.id))
  assertSimpliEndState(order)
 }

 @Test fun shippingMailWithoutOrderNumberIsSentToReviewEvenWhenInTheOrderThread() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val summary = scan(listOf(simpliConfirmation("T1"), simpliShipping(false, "T1"), fedexMail()), order)
  assertTrue(summary.reviews.filterIsInstance<ReviewItem.EmailNeedsReview>().any { it.email.messageId == "ship" })
  assertTrue(dao.getShipments(order.id).isEmpty())
  assertTrue(dao.allTrackingNumbers().isEmpty())
  assertTrue(summary.reviews.filterIsInstance<ReviewItem.EmailNeedsReview>().isNotEmpty())
 }

 @Test fun shippingMailWithoutOrderNumberOrSavedTrackingDoesNotMatchRetailerIdentityAlone() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val other = seed("eBay", "16-15304-06041")
  val summary = scan(listOf(simpliConfirmation(), simpliShipping(false, "T-other"), fedexMail()), order, other)
  assertTrue(summary.reviews.filterIsInstance<ReviewItem.EmailNeedsReview>().any { it.email.messageId == "ship" })
  assertTrue(dao.getShipments(order.id).isEmpty())
  assertTrue(dao.getShipments(other.id).isEmpty() && dao.changesForOrder(other.id).isEmpty())
 }

 @Test fun theResultIsTheSameWhenTheCarrierEmailIsProcessedFirst() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  scan(listOf(fedexMail(), simpliShipping(true, "T-other"), simpliConfirmation()), order)
  assertSimpliEndState(order)
 }

 @Test fun carrierMailNamingTheShipperWithoutKnownTrackingStaysForReview() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val summary = scan(listOf(simpliConfirmation(), simpliShipping(false, "T-other", withTracking = false), fedexMail()), order)
  assertTrue(summary.reviews.filterIsInstance<ReviewItem.EmailNeedsReview>().isNotEmpty())
  assertTrue(dao.getShipments(order.id).isEmpty())
  assertTrue(dao.allTrackingNumbers().isEmpty())
 }

 @Test fun messagesMarkedProcessedByTheOldParserAreAppliedByAManualUpdateButNotByBackgroundSync() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918", notes = "Order total: \$48.00")
  // The previous build recorded these as processed although nothing was applied.
  listOf("conf", "ship", "fx").forEach { dao.markProcessed(ProcessedGmailMessage(messageId = it, orderId = order.id, extractionVersion = 2)) }
  val inbox = listOf(simpliConfirmation(), simpliShipping(true, "T-ship"), fedexMail())
  val background = syncOrdersFromMessages(db, prefs, inbox)
  assertEquals(ScanOutcome.UNCHANGED, background.update!!.results.single().outcome)
  assertTrue(dao.allTrackingNumbers().isEmpty())
  val manual = scan(inbox, order).results.single()
  assertEquals(ScanOutcome.UPDATED, manual.outcome)
  assertTrue(manual.changes.single { it.field == "Tracking number" }.kind == ChangeKind.BACKFILL)
  assertSimpliEndState(order)
 }

 @Test fun backgroundSyncAppliesNewMailThroughTheSameAssociationPath() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val summary = syncOrdersFromMessages(db, prefs, listOf(simpliShipping(true, "T-ship"), fedexMail()))
  assertEquals(ScanOutcome.UPDATED, summary.update!!.results.single().outcome)
  assertEquals(tracking, dao.allTrackingNumbers().single().number)
  assertEquals(ScanOutcome.UNCHANGED, syncOrdersFromMessages(db, prefs, listOf(simpliShipping(true, "T-ship"), fedexMail())).update!!.results.single().outcome)
  assertEquals(1, dao.getShipments(order.id).size)
 }

 // ---------- Case B: eBay ----------

 private fun ebayShipped(subject: String = "Your order is on its way") = gmail("eb1", "T-eb", "eBay <ebay@ebay.com>", subject,
  html = "<p>Order number: 16-15304-06041</p><p>Shipped with USPS</p>" +
   "<a href=\"https://www.ebay.com/vod/FetchOrderDetails?itemid=1234567890123&amp;transid=998877665544\">Track package</a>")

 @Test fun ebayShippingNoticeWithoutATrackingNumberCreatesAUspsShipmentOnTheExistingOrder() = runBlocking<Unit> {
  val order = seed("eBay", "16-15304-06041", name = "Camera strap")
  val result = scan(listOf(ebayShipped()), order).results.single()
  assertEquals(ScanOutcome.UPDATED, result.outcome)
  assertEquals(1, dao.allOrders().size)
  val ship = dao.getShipments(order.id).single()
  assertEquals("USPS", ship.carrier)
  assertEquals("eBay", ship.title)
  assertEquals("In Transit", ship.status)
  assertTrue("no tracking number may be invented from the link", dao.allTrackingNumbers().isEmpty())
  assertEquals("Shipped", dao.getOrder(order.id)!!.status)
  assertTrue(fields(order).containsAll(listOf("Shipment", "Shipment status", "Order status")))
  assertFalse("Tracking number" in fields(order))
  val history = dao.changesForOrder(order.id)
  assertEquals(ScanOutcome.UNCHANGED, scan(listOf(ebayShipped()), order).results.single().outcome)
  assertEquals(history, dao.changesForOrder(order.id)); assertEquals(1, dao.getShipments(order.id).size)
 }

 @Test fun reviewedEmailWithNoSavedOrderCanCreateTheOrderWithRetailerTitleAndNumber() = runBlocking<Unit> {
  val email = ebayShipped()
  assertEquals("eBay", retailerNameFromSender(email.sender))

  assertEquals(SaveOutcome.Saved, vm.createOrderFromEmail(email, "eBay", "16-15304-06041", "Camera strap"))

  val order = dao.allOrders().single()
  assertEquals(Triple("eBay", "16-15304-06041", "Camera strap"), Triple(order.marketplace, order.orderNumber, order.name))
  assertEquals(MANUAL_ASSOCIATION_VERSION, dao.allProcessed().single().extractionVersion)
  assertEquals("In Transit", dao.getShipments(order.id).single().status)
  assertTrue(dao.allTrackingNumbers().isEmpty())
  assertEquals(ScanOutcome.UNCHANGED, scan(listOf(email), order).results.single().outcome)
  assertTrue(vm.createOrderFromEmail(email, "eBay", "16-15304-06041", "Again") is SaveOutcome.Blocked)
  assertEquals(1, dao.allOrders().size)
 }

 @Test fun creatingAnOrderForAnExistingRetailerAndNumberIsRefusedAndChangesNothing() = runBlocking<Unit> {
  seed("eBay", "16-15304-06041")
  val outcome = vm.createOrderFromEmail(ebayShipped(), "EBAY", "16-15304-06041", "Duplicate")
  assertTrue(outcome.toString(), outcome is SaveOutcome.Blocked)
  assertEquals(1, dao.allOrders().size)
  assertTrue(dao.allProcessed().isEmpty() && dao.allShipments().isEmpty())
 }

 @Test fun ebayDeliveryNoticeUpdatesTheSameShipmentAndOrder() = runBlocking<Unit> {
  val order = seed("eBay", "16-15304-06041")
  scan(listOf(ebayShipped()), order)
  val delivered = gmail("eb2", "T-eb2", "eBay <ebay@ebay.com>", "Your order was delivered", html = "<p>Order number: 16-15304-06041</p>")
  assertEquals(ScanOutcome.UPDATED, scan(listOf(ebayShipped(), delivered), order).results.single().outcome)
  assertEquals(1, dao.getShipments(order.id).size)
  assertEquals("Delivered", dao.getShipments(order.id).single().status)
  assertEquals("Shipped", dao.getOrder(order.id)!!.status)
 }

 @Test fun deliveryExceptionUpdatesTheMatchedShipmentAndIsRecordedAsProcessed() = runBlocking<Unit> {
  val order = seed("eBay", "16-15304-06041")
  // The shipment was already in transit before the exception email was sent.
  val shipmentId = dao.insertShipment(ShipmentRecord(orderId = order.id, status = "In Transit", statusUpdatedAt = parseMailDate("Mon, 08 Jan 2024 10:00:00 +0000")!!))
  val exception = gmail("ex1", "T-ex", "eBay <ebay@ebay.com>", "Your order has a delivery exception",
   html = "<p>Order number: 16-15304-06041</p><p>USPS could not deliver this package.</p>")

  val result = scan(listOf(exception), order).results.single()

  assertEquals(ScanOutcome.UPDATED, result.outcome)
  assertEquals("Exception", dao.getShipment(shipmentId)!!.status)
  assertEquals(listOf("ex1"), dao.allProcessed().map { it.messageId })
  assertTrue(result.changes.any { it.field == "Shipment status" && it.after == "Exception" })
  assertEquals(ScanOutcome.UNCHANGED, scan(listOf(exception), order).results.single().outcome)
 }

 @Test fun deliveryExceptionWithoutAShipmentStaysForReviewAndIsNotMarkedProcessed() = runBlocking<Unit> {
  val order = seed("eBay", "16-15304-06041")
  val exception = gmail("ex2", "T-ex2", "eBay <ebay@ebay.com>", "Your order has a delivery exception",
   html = "<p>Order number: 16-15304-06041</p><p>USPS could not deliver this package.</p>")

  val summary = scan(listOf(exception), order)

  assertEquals(ScanOutcome.REVIEW_REQUIRED, summary.results.single().outcome)
  assertEquals(0, dao.getShipments(order.id).size)
  assertTrue(dao.allProcessed().isEmpty())
  assertTrue(summary.reviews.filterIsInstance<ReviewItem.EmailNeedsReview>().any { it.reason.contains("no single shipment") })
 }

 @Test fun aManuallyChosenOrderStatusSurvivesEbayShippingEvidenceWhileTheShipmentStatusUpdates() = runBlocking<Unit> {
  val order = seed("eBay", "16-15304-06041")
  assertEquals(SaveOutcome.Saved, vm.setOrderStatus(order.id, "Processing"))
  scan(listOf(ebayShipped()), order)
  assertEquals("Processing", dao.getOrder(order.id)!!.status)
  assertEquals("In Transit", dao.getShipments(order.id).single().status)
 }

 // ---------- Order card progress synchronized with persisted shipment status ----------

 private val ebayOrder = "16-15304-06041"
 private fun ebayMail(id: String, subject: String, date: String, extra: String = "") =
  gmail(id, "T-$id", "eBay <ebay@ebay.com>", subject, html = "<p>Order number: $ebayOrder</p>$extra", date = date)
 private fun ebayOutForDelivery(id: String = "ofd", date: String = "Wed, 17 Jan 2024 08:00:00 +0000") = ebayMail(id, "Your package is out for delivery", date)
 private fun ebayException(id: String, date: String) = ebayMail(id, "Your order has a delivery exception", date, "<p>USPS could not deliver this package.</p>")
 private suspend fun cardProgress(order: OrderRecord): PurchaseProgress {
  val saved = dao.getOrder(order.id)!!
  return purchaseProgress(saved.status, !mayDeriveOrderStatus(dao.latestChangeForField(order.id, "Order status")))
 }

 @Test fun outForDeliveryFromGmailShowsStageFourAndMatchesTheShipmentDetails() = runBlocking<Unit> {
  val order = seed("eBay", ebayOrder)
  scan(listOf(ebayShipped()), order)
  assertEquals("4 of 4 · Shipped", cardProgress(order).summary)
  scan(listOf(ebayShipped(), ebayOutForDelivery()), order)
  val ship = dao.getShipments(order.id).single()
  assertEquals("Out for Delivery", ship.status)
  val progress = cardProgress(order)
  assertEquals("4 of 4 · Shipped", progress.summary)
  assertEquals("Shipped", progress.label)
  assertEquals("Shipped", dao.getOrder(order.id)!!.status)
 }

 @Test fun repeatedAndOlderGmailUpdatesNeverRegressTheDisplayedShipmentStatus() = runBlocking<Unit> {
  val order = seed("eBay", ebayOrder)
  scan(listOf(ebayShipped(), ebayOutForDelivery()), order)
  val olderShipped = ebayMail("old-ship", "Your order has shipped", "Sun, 14 Jan 2024 10:00:00 +0000", "<p>Shipped with USPS</p>")
  repeat(3) { scan(listOf(ebayShipped(), ebayOutForDelivery(), olderShipped), order) }
  assertEquals("Out for Delivery", dao.getShipments(order.id).single().status)
  assertEquals("4 of 4 · Shipped", cardProgress(order).summary)
 }

 @Test fun aStaleExceptionNeverReopensButANewerOneIsShownExplicitly() = runBlocking<Unit> {
  val order = seed("eBay", ebayOrder)
  scan(listOf(ebayShipped(), ebayOutForDelivery(), ebayException("ex-old", "Tue, 16 Jan 2024 09:00:00 +0000")), order)
  assertEquals("Out for Delivery", dao.getShipments(order.id).single().status)
  scan(listOf(ebayException("ex-old-2", "Tue, 16 Jan 2024 12:00:00 +0000")), order)
  assertEquals("a backfilled older exception must not undo Out for Delivery", "Out for Delivery", dao.getShipments(order.id).single().status)

  val second = seed("eBay", "16-15304-09999")
  val ship = "<p>Order number: 16-15304-09999</p>"
  val newer = listOf(
   gmail("s2", "T-s2", "eBay <ebay@ebay.com>", "Your order is on its way", html = "$ship<p>Shipped with USPS</p>", date = "Mon, 15 Jan 2024 10:00:00 +0000"),
   gmail("o2", "T-o2", "eBay <ebay@ebay.com>", "Your package is out for delivery", html = ship, date = "Wed, 17 Jan 2024 08:00:00 +0000"),
   gmail("e2", "T-e2", "eBay <ebay@ebay.com>", "Your order has a delivery exception", html = "$ship<p>USPS could not deliver this package.</p>", date = "Wed, 17 Jan 2024 18:00:00 +0000"))
  scan(newer, second)
  assertEquals("Exception", dao.getShipments(second.id).single().status)
  val progress = cardProgress(second)
  assertEquals("4 of 4 · Shipped", progress.summary)
  assertFalse(progress.attention)
 }

 @Test fun customsIsNotRegressedByOlderShippingMailButIsAdvancedByOutForDelivery() = runBlocking<Unit> {
  val order = seed("eBay", ebayOrder)
  scan(listOf(ebayShipped()), order)
  val shipment = dao.getShipments(order.id).single()
  assertEquals(SaveOutcome.Saved, vm.setShipmentStatus(shipment.id, "Customs"))
  scan(listOf(ebayMail("ship-again", "Your order has shipped", "Tue, 16 Jan 2024 10:00:00 +0000", "<p>Shipped with USPS</p>")), order)
  assertEquals("Customs", dao.getShipment(shipment.id)!!.status)
  assertEquals("4 of 4 · Shipped", cardProgress(order).summary)
  scan(listOf(ebayOutForDelivery()), order)
  assertEquals("Out for Delivery", dao.getShipment(shipment.id)!!.status)
 }

 @Test fun oneDeliveredShipmentNeverChangesTheOrderStatusOfAMultiShipmentOrder() = runBlocking<Unit> {
  val order = seed("eBay", ebayOrder, status = "Shipped")
  val first = dao.insertShipment(ShipmentRecord(orderId = order.id, status = "In Transit"))
  val second = dao.insertShipment(ShipmentRecord(orderId = order.id, status = "In Transit"))
  vm.setShipmentStatus(first, "Delivered")
  vm.setShipmentStatus(second, "Out for Delivery")
  assertEquals("Shipped", dao.getOrder(order.id)!!.status)
  val progress = cardProgress(order)
  assertEquals("4 of 4 · Shipped", progress.summary)
  assertEquals(listOf("Delivered", "Out for Delivery"), dao.getShipments(order.id).sortedBy { it.id }.map { it.status })
  vm.setShipmentStatus(second, "Delivered")
  assertEquals("Shipped", dao.getOrder(order.id)!!.status)
  assertEquals("4 of 4 · Shipped", cardProgress(order).summary)
 }

 @Test fun aManualDeliveredOrderStatusIsKeptWhileProgressFollowsTheShipment() = runBlocking<Unit> {
  val order = seed("eBay", ebayOrder)
  scan(listOf(ebayShipped()), order)
  assertEquals(SaveOutcome.Saved, vm.setOrderStatus(order.id, "Delivered"))
  scan(listOf(ebayShipped(), ebayOutForDelivery()), order)
  assertEquals("Delivered", dao.getOrder(order.id)!!.status)
  assertEquals("Out for Delivery", dao.getShipments(order.id).single().status)
  val progress = cardProgress(order)
  assertEquals("4 of 4 · Delivered", progress.summary)
  assertEquals(null, progress.manualNote)
 }

 @Test fun anOutForDeliveryEmailWithoutTrackingCanCreateTheOrderFromReview() = runBlocking<Unit> {
  val email = ebayMail("ofd-new", "OUT FOR DELIVERY: Google Pixel C 64GB", "Wed, 21 Oct 2026 10:05:57 -0700")
  assertEquals(SaveOutcome.Saved, vm.createOrderFromEmail(email, "eBay", "18-15293-46910", "Ebay 46910"))
  val order = dao.allOrders().single { it.orderNumber == "18-15293-46910" }
  val ship = dao.getShipments(order.id).single()
  assertEquals("Out for Delivery", ship.status)
  assertTrue(dao.allTrackingNumbers().isEmpty())
  assertEquals("4 of 4 · Shipped", cardProgress(order).summary)
  assertEquals(listOf("ofd-new"), dao.allProcessed().map { it.messageId })
 }

 @Test fun aBodyOnlyShippedNoticeStillCountsAndAConfirmationNeverDoes() = runBlocking<Unit> {
  val order = seed("eBay", "16-15304-06041")
  val confirmation = gmail("eb0", "T0", "eBay <ebay@ebay.com>", "Thanks for your order", html = "<p>Order number: 16-15304-06041</p><p>We'll email you when your order has shipped with USPS.</p>")
  scan(listOf(confirmation), order)
  assertTrue(dao.getShipments(order.id).isEmpty())
  val bodyOnly = gmail("eb3", "T3", "eBay <ebay@ebay.com>", "Order update", html = "<p>Order number: 16-15304-06041</p><p>Good news. Your order shipped with USPS.</p>")
  scan(listOf(bodyOnly), order)
  assertEquals("In Transit", dao.getShipments(order.id).single().status)
 }

 // ---------- Case C: DSWATCH / DS Designer ----------

 private fun dsMail(id: String = "ds1") = gmail(id, "T-$id", "DSWATCH <service@dswatch.com>", "Your order DSDF66669184 has shipped", html = "<p>Your order has shipped via USPS.</p>")

 @Test fun existingDuplicateCardsAreNeverChangedByAnAmbiguousEmailAndAreOfferedForMerge() = runBlocking<Unit> {
  val a = seed("DSWATCH", "DSDF66669184")
  val b = seed("DS Designer", "DSDF66669184")
  dao.insertItem(OrderItemRecord(orderId = a.id, name = "Watch"))
  val summary = scan(listOf(dsMail()), a, b)
  assertEquals(listOf(ScanOutcome.REVIEW_REQUIRED, ScanOutcome.REVIEW_REQUIRED), summary.results.map { it.outcome })
  assertEquals(2, dao.allOrders().size); assertTrue(dao.allShipments().isEmpty()); assertEquals(1, dao.getItems(a.id).size)
  val email = summary.reviews.filterIsInstance<ReviewItem.EmailNeedsReview>().single()
  assertEquals(setOf(a.id, b.id), email.candidateOrderIds.toSet())
  assertTrue(findDuplicateOrders(dao.allOrders()).single().sameRetailer)
 }

 @Test fun aVerifiedAliasMatchesTheSingleSavedOrderAndImportDoesNotCreateASecondCard() = runBlocking<Unit> {
  val saved = seed("DS Designer", "DSDF66669184")
  assertEquals(ScanOutcome.UPDATED, scan(listOf(dsMail()), saved).results.single().outcome)
  val group = groupedScanResults(listOf(dsMail("ds2")), emptyList(), emptyList(), dao.allOrders(), AliasBook.default).single().copy(title = "DSWATCH")
  assertTrue(vm.importScanGroup(group).startsWith("Existing order"))
  assertEquals(1, dao.allOrders().size)
  // A manual save of the alias name is also recognised as a duplicate.
  val draft = OrderDraft(market = "DSWATCH", name = "Watch", number = "DSDF66669184")
  assertEquals(SaveOutcome.Duplicate, vm.saveOrder(draft))
 }

 @Test fun unverifiedRetailerNamesWithTheSameOrderNumberAreNotMergedOrCrossMatched() = runBlocking<Unit> {
  val foo = seed("Foo Shop", "ZX99887766"); val bar = seed("Bar Store", "ZX99887766")
  val email = gmail("f1", "Tf", "Foo Shop <a@fooshop.com>", "Order ZX99887766 has shipped", html = "<p>Shipped with UPS</p>")
  val summary = scan(listOf(email), foo, bar)
  assertEquals(ScanOutcome.UPDATED, summary.results.first { it.orderId == foo.id }.outcome)
  assertEquals(ScanOutcome.UNCHANGED, summary.results.first { it.orderId == bar.id }.outcome)
  assertTrue(dao.getShipments(bar.id).isEmpty())
  assertFalse(findDuplicateOrders(dao.allOrders()).single().sameRetailer)
  assertEquals(2, dao.allOrders().size)
  assertTrue(vm.mergeOrders(foo.id, seed("Baz", "OTHER-1").id) is SaveOutcome.Blocked)
  assertEquals(3, dao.allOrders().size)
 }

 @Test fun mergingDuplicatesKeepsEveryItemShipmentTrackingNumberEventHistoryAndMarker() = runBlocking<Unit> {
  val a = dao.getOrder(dao.insertOrder(OrderRecord(marketplace = "DSWATCH", orderNumber = "DSDF66669184", name = "Watch", status = "Shipped", notes = "A note")))!!
  val b = dao.getOrder(dao.insertOrder(OrderRecord(marketplace = "DS Designer", orderNumber = "DSDF66669184", name = "Watch", placedAt = 1_700_000_000_000, orderUrl = "https://example.com/o/1", notes = "B note")))!!
  dao.insertItem(OrderItemRecord(orderId = a.id, name = "Watch"))
  dao.insertItem(OrderItemRecord(orderId = b.id, name = "Watch")); dao.insertItem(OrderItemRecord(orderId = b.id, name = "Watch strap"))
  val sa = dao.insertShipment(ShipmentRecord(orderId = a.id, status = "In Transit"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sa, number = "TRK1111111111", carrier = "USPS"))
  val sb1 = dao.insertShipment(ShipmentRecord(orderId = b.id, status = "In Transit"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sb1, number = "TRK1111111111", carrier = "USPS"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sb1, number = "TRK2222222222", carrier = "UPS", role = "handoff"))
  dao.insertEvent(ShipmentTrackingEventRecord(shipmentId = sb1, description = "Arrived at facility"))
  val sb2 = dao.insertShipment(ShipmentRecord(orderId = b.id, status = "Awaiting Tracking"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sb2, number = "TRK3333333333", carrier = "FedEx"))
  dao.recordChange(a.id, "Notes", "", "A note"); dao.recordChange(b.id, "Notes", "", "B note")
  dao.markProcessed(ProcessedGmailMessage(messageId = "ma", orderId = a.id)); dao.markProcessed(ProcessedGmailMessage(messageId = "mb", orderId = b.id)); dao.markProcessed(ProcessedGmailMessage(messageId = "ma", orderId = b.id))

  assertEquals(SaveOutcome.Saved, vm.mergeOrders(a.id, b.id))

  assertEquals(listOf(a.id), dao.allOrders().map { it.id })
  val kept = dao.getOrder(a.id)!!
  assertEquals("Shipped", kept.status); assertEquals("A note \u00b7 B note", kept.notes)
  assertEquals(1_700_000_000_000, kept.placedAt); assertEquals("https://example.com/o/1", kept.orderUrl)
  assertEquals(setOf("Watch", "Watch strap"), dao.getItems(a.id).map { it.name }.toSet()); assertEquals(2, dao.getItems(a.id).size)
  assertEquals(2, dao.getShipments(a.id).size)
  assertEquals(setOf("TRK1111111111", "TRK2222222222", "TRK3333333333"), dao.allTrackingNumbers().map { it.number }.toSet())
  assertEquals(3, dao.allTrackingNumbers().size)
  assertEquals(1, dao.observeAllEvents().first().size)
  assertEquals(setOf("ma", "mb"), dao.allProcessed().map { it.messageId }.toSet())
  val rows = dao.changesForOrder(a.id)
  assertTrue(rows.count { it.field == "Notes" } >= 2); assertTrue("Merged duplicate order" in rows.map { it.field })
  assertEquals(0, dao.wasDeleted("DS Designer", "DSDF66669184"))
 }

 @Test fun mergeCanRememberAnUnverifiedAliasSoLaterScansTreatTheNamesAsOneRetailer() = runBlocking<Unit> {
  val foo = seed("Foo Shop", "ZX99887766"); val bar = seed("Bar Store", "ZX99887766")
  assertEquals(SaveOutcome.Saved, vm.mergeOrders(foo.id, bar.id, rememberAlias = true))
  assertEquals(1, dao.allOrders().size)
  assertEquals(1, dao.wasDeleted("Bar Store", "ZX99887766"))
  assertTrue(vm.retailerAliasGroups().any { g -> g.any { it == "Bar Store" } && g.any { it == "Foo Shop" } })
  val email = gmail("b1", "Tb", "Bar Store <x@barstore.com>", "Order ZX99887766 has shipped", html = "<p>Shipped with UPS</p>")
  assertEquals(ScanOutcome.UPDATED, scan(listOf(email), foo).results.single().outcome)
 }

 // ---------- manual association fallback ----------

 private suspend fun twoSimpliOrders(): Pair<OrderRecord, OrderRecord> = seed("Simpli Soda", "28855918") to seed("Simpli Soda", "28855777")

 @Test fun anAmbiguousShippingEmailIsOfferedForReviewThenAssociatedOnceAndRemembered() = runBlocking<Unit> {
  val (a, b) = twoSimpliOrders()
  val email = simpliShipping(false, "T-x")
  val first = scan(listOf(email), a, b)
  assertEquals(listOf(ScanOutcome.REVIEW_REQUIRED, ScanOutcome.REVIEW_REQUIRED), first.results.map { it.outcome })
  assertTrue(dao.allShipments().isEmpty() && dao.allProcessed().isEmpty())
  val item = first.reviews.filterIsInstance<ReviewItem.EmailNeedsReview>().single()
  assertEquals(setOf(a.id, b.id), item.candidateOrderIds.toSet())
  val preview = vm.previewAssociation(item.email, b.id)
  assertEquals(listOf(tracking), preview.trackingNumbers); assertEquals("FedEx", preview.carrier); assertEquals("In Transit", preview.status); assertNull(preview.conflict)

  assertEquals(SaveOutcome.Saved, vm.associateEmailWithOrder(item.email, b.id))
  assertEquals(tracking, dao.allTrackingNumbers().single().number)
  assertEquals(b.id, dao.getShipments(b.id).single().orderId); assertTrue(dao.getShipments(a.id).isEmpty())
  assertEquals(MANUAL_ASSOCIATION_VERSION, dao.allProcessed().single().extractionVersion)
  assertTrue("Email associated" in fields(b))
  // Remembered: later scans neither ask again nor change anything.
  val second = scan(listOf(email), a, b)
  assertTrue(second.reviews.filterIsInstance<ReviewItem.EmailNeedsReview>().isEmpty())
  assertEquals(listOf(ScanOutcome.UNCHANGED, ScanOutcome.UNCHANGED), second.results.map { it.outcome })
  assertTrue(vm.associateEmailWithOrder(item.email, a.id) is SaveOutcome.Blocked)
  withTimeout(5_000) { vm.numbers.first { list -> list.any { it.number == tracking } }; vm.shipments.first { list -> list.any { it.orderId == b.id } } }
 }

 @Test fun associatingWithAChosenShipmentResolvesATrackingNumberThatMatchedNoSingleShipment() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val s1 = dao.insertShipment(ShipmentRecord(orderId = order.id)); val s2 = dao.insertShipment(ShipmentRecord(orderId = order.id))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = s1, number = "AAAAAAAAAA11", carrier = "FedEx"))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = s2, number = "BBBBBBBBBB22", carrier = "UPS"))
  val email = simpliShipping(true, "T-ship")
  val summary = scan(listOf(email), order)
  assertEquals(ScanOutcome.REVIEW_REQUIRED, summary.results.single().outcome)
  assertTrue(dao.allProcessed().isEmpty())
  assertEquals(SaveOutcome.Saved, vm.associateEmailWithOrder(email, order.id, s2))
  assertEquals(listOf("BBBBBBBBBB22", tracking), dao.getTrackingNumbersForShipment(s2).map { it.number })
  assertEquals("handoff", dao.getTrackingNumbersForShipment(s2).last().role)
  assertEquals(1, dao.getTrackingNumbersForShipment(s1).size)
 }

 @Test fun aConflictingTrackingNumberBlocksTheAssociationAndChangesNothing() = runBlocking<Unit> {
  val (a, b) = twoSimpliOrders()
  val sid = dao.insertShipment(ShipmentRecord(orderId = a.id))
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = tracking, carrier = "FedEx"))
  val email = simpliShipping(false, "T-x")
  assertNotNull(vm.previewAssociation(email, b.id).conflict)
  assertTrue(vm.associateEmailWithOrder(email, b.id) is SaveOutcome.Blocked)
  assertTrue(dao.getShipments(b.id).isEmpty() && dao.allProcessed().isEmpty() && dao.changesForOrder(b.id).isEmpty())
 }

 @Test fun aFailedAssociationRollsBackTheMarkerTheShipmentAndTheHistory() = runBlocking<Unit> {
  val (a, _) = twoSimpliOrders()
  db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_marker BEFORE INSERT ON processed_gmail_messages BEGIN SELECT RAISE(ABORT, 'forced failure'); END")
  val outcome = vm.associateEmailWithOrder(simpliShipping(false, "T-x"), a.id)
  assertTrue(outcome.toString(), outcome is SaveOutcome.Blocked)
  assertTrue(dao.allShipments().isEmpty() && dao.allTrackingNumbers().isEmpty() && dao.allProcessed().isEmpty() && dao.changesForOrder(a.id).isEmpty())
 }

 // ---------- tracking-number collisions and the existing history ----------

 @Test fun aTrackingNumberClaimedByTwoOrdersIsNotAttachedToEitherAndCarrierMailStaysUnassigned() = runBlocking<Unit> {
  val a = seed("Simpli Soda", "28855918"); val b = seed("namokiMODS", "NM-77777")
  val namoki = gmail("n1", "Tn", "namokiMODS <hello@namokimods.com>", "Order #NM-77777 has shipped", html = "<p>Tracking number: $tracking</p>")
  val summary = scan(listOf(simpliShipping(true, "T-s"), namoki, fedexMail()), a, b)
  assertEquals(listOf(ScanOutcome.REVIEW_REQUIRED, ScanOutcome.REVIEW_REQUIRED), summary.results.map { it.outcome })
  assertTrue(dao.allTrackingNumbers().isEmpty() && dao.allProcessed().isEmpty())
 }

 @Test fun existingChangeHistoryStillRecordsGmailChangesWithProvenance() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  scan(listOf(simpliShipping(true, "T-ship")), order)
  val rows = dao.changesForOrder(order.id).filter { it.sourceMessageId != SOURCE_DERIVED }
  assertTrue(rows.all { it.sourceMessageId == "ship" && it.sourceDate.isNotBlank() })
  assertEquals(dao.changesForOrder(order.id).size, dao.observeChanges(order.id).first().size)
 }

 @Test fun customerServiceMessageMentioningOrderNumberWithoutSupportedFieldsIsIgnored() = runBlocking<Unit> {
  val order = seed("Simpli Soda", "28855918")
  val confirmation = simpliConfirmation()
  val service = gmail("service-only", "T-service", simpli, "Customer service case for order #28855918",
   html = "<p>Please contact our support team about your question.</p>")

  val result = scan(listOf(confirmation, service), order).results.single()

  assertEquals(ScanOutcome.UPDATED, result.outcome)
  assertEquals(listOf("conf"), dao.allProcessed().map { it.messageId })
  assertTrue(dao.changesForOrder(order.id).none { it.sourceMessageId == "service-only" })
 }

 // ---------- Gmail MIME handling ----------

 @Test fun mimeMessageWithAttachmentBackedHtmlLatin1AndEncodedHeadersIsParsedForTheTrackingNumber() {
  val html = "<p>Caf\u00e9 order <b>28855918</b></p><a href=\"https://www.fedex.com/fedextrack/?trknbr=$tracking\">Track</a>"
  val big = JSONObject().put("mimeType", "text/html")
   .put("headers", JSONArray().put(JSONObject().put("name", "Content-Type").put("value", "text/html; charset=ISO-8859-1")))
   .put("body", JSONObject().put("attachmentId", "ATT1").put("size", 99999))
  val payload = JSONObject().put("mimeType", "multipart/mixed")
   .put("headers", JSONArray().put(JSONObject().put("name", "From").put("value", "=?UTF-8?Q?Simpli_Soda?= <orders@simplisoda.com>"))
    .put(JSONObject().put("name", "Subject").put("value", "=?UTF-8?Q?Your_Simpli_Soda_order_has_shipped?=")))
   .put("parts", JSONArray().put(JSONObject().put("mimeType", "multipart/alternative").put("parts", JSONArray().put(textPart("text/plain", "Plain copy")).put(big)))
    .put(JSONObject().put("mimeType", "image/png").put("body", JSONObject().put("attachmentId", "IMG"))))
  val preview = parseGmailMessage(JSONObject().put("id", "x").put("threadId", "t").put("snippet", "It&#39;s here").put("payload", payload)) { id ->
   if(id == "ATT1") b64(html, Charsets.ISO_8859_1) else null
  }
  assertEquals("Simpli Soda <orders@simplisoda.com>", preview.sender)
  assertEquals("Your Simpli Soda order has shipped", preview.subject)
  assertEquals("It's here", preview.snippet)
  assertTrue(preview.body, preview.body.contains("Caf\u00e9 order 28855918"))
  assertTrue(preview.body.contains("[link: https://www.fedex.com/fedextrack/?trknbr=$tracking]"))
  assertEquals(listOf(tracking), extractTrackingCandidates(preview))
  assertEquals("FedEx", detectCarrier(preview, tracking))
 }

 @Test fun parserKeepsPlainTextLinksInAngleBrackets() {
  val preview = gmail("p", "t", simpli, "Shipped", plain = "Track here <https://www.fedex.com/fedextrack/?trknbr=$tracking>\nThanks")
  assertEquals(listOf(tracking), extractTrackingCandidates(preview))
 }
}
