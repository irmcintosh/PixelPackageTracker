package com.example.pixelpackagetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Needs Review association flows through the same ViewModel calls the dialog uses. In-memory Room only.
@RunWith(AndroidJUnit4::class)
class Wp02AssociationFormFlowTest {
 private lateinit var db: PackageDatabase
 private lateinit var vm: TrackerVm
 private lateinit var preferences: SettingsRepository
 private var originalDismissedIds: Set<String> = emptySet()
 private val dao get() = db.orderDao()
 private val simpli = "Simpli Soda <orders@simplisoda.com>"

 @Before fun setUp() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
    preferences = SettingsRepository(context)
    runBlocking { originalDismissedIds = preferences.dismissedGmailMessageIds() }
    vm = TrackerVm(db, preferences)
 }

 @After fun tearDown() = runBlocking<Unit> { preferences.setDismissedGmailMessageIds(originalDismissedIds); db.close() }

 private fun mail(id: String, sender: String, subject: String, body: String = "") = GmailPreview(sender, subject, "Mon, 15 Jan 2024 10:00:00 +0000", "", "t$id", body, id)
 private suspend fun seed(market: String, number: String, name: String = market) = dao.getOrder(dao.insertOrder(OrderRecord(marketplace = market, orderNumber = number, name = name)))!!

 @Test fun dismissedReviewEmailIsExcludedFromFutureScansAndImportsWithoutAnOrderMarker() = runBlocking<Unit> {
  val email = mail("dismiss-me", "Amazon.com <auto-confirm@amazon.com>", "Ordered 1 item: Tops", "Order #111-9392767-7525812")
  assertEquals(SaveOutcome.Saved, vm.dismissEmailFromReview(email))
  assertTrue("dismiss-me" in vm.dismissedEmailIds())
  assertTrue(vm.filterDismissedEmails(listOf(email)).isEmpty())
  assertTrue(dao.allProcessed().isEmpty())

  val summary = vm.updateOrdersFromEmails(listOf(email), emptyList())
  assertTrue(summary.reviews.isEmpty())
  val group = groupedScanResults(listOf(email), emptyList(), emptyList(), emptyList()).single()
  assertEquals("Skipped: all emails in this group were removed from review", vm.importScanGroup(group))
  assertTrue(dao.allOrders().isEmpty() && dao.allProcessed().isEmpty())
 }

 @Test fun anAmazonConfirmationWhoseBodyMentionsShippingStillCreatesTheOrderFromReview() = runBlocking<Unit> {
  val amazon = "\"Amazon.com\" <auto-confirm@amazon.com>"
  val email = mail("am2", amazon, "Ordered 1 item: Tops", "Order # 111-9392767-7525812\nYour package is on its way to you.\nShipped with Amazon Logistics.")
  assertEquals(SaveOutcome.Saved, vm.createOrderFromEmail(email, "Amazon.com", "111-9392767-7525812", ""))
  val order = dao.allOrders().single()
  assertEquals(order.id, dao.allProcessed().single { it.messageId == "am2" }.orderId)
 }

 @Test fun anEmailThatCannotBeAppliedLeavesNoOrderNoMarkerAndIsNotUsed() = runBlocking<Unit> {
  // An exception notice cannot establish a shipment on its own, so this email cannot be applied.
  val delivered = mail("d1", "\"Amazon.com\" <shipment-tracking@amazon.com>", "Delivery exception: Your package was delayed", "Order # 111-9392767-7525812\nDelivery exception: your package is delayed.")
  val outcome = vm.createOrderFromEmail(delivered, "Amazon.com", "111-9392767-7525812", "")
  assertTrue(outcome.toString(), outcome is SaveOutcome.Blocked)
  assertTrue((outcome as SaveOutcome.Blocked).message.contains("stays in Needs Review"))
  assertTrue(dao.allOrders().isEmpty() && dao.allShipments().isEmpty() && dao.allProcessed().isEmpty())
  // The same email can still be handled afterwards: it was not consumed.
  val saved = seed("Amazon.com", "111-9392767-7525812")
  assertTrue(vm.associateEmailWithOrder(delivered, saved.id) is SaveOutcome.Blocked)
  assertTrue(dao.allProcessed().isEmpty())
 }

 @Test fun onceAnEmailIsAssociatedItIsNotOfferedOrUsedAgain() = runBlocking<Unit> {
  val saved = seed("Amazon.com", "111-9392767-7525812")
  val email = mail("am3", "\"Amazon.com\" <shipment-tracking@amazon.com>", "Shipped: 1 item", "Order # 111-9392767-7525812\nTracking number: 1Z999AA10123456784\nCarrier: UPS")
  assertEquals(SaveOutcome.Saved, vm.associateEmailWithOrder(email, saved.id))
  val markers = vm.associationMarkers()
  val group = groupedScanResults(listOf(email), dao.allTrackingNumbers(), dao.allShipments(), dao.allOrders(), AliasBook.default,
   manualAssociationOwners(markers), persistedAssociationOwners(markers)).single()
  assertEquals("room:${saved.id}", group.key)
  assertTrue(vm.associateEmailWithOrder(email, saved.id) is SaveOutcome.Blocked)
  assertTrue(vm.createOrderFromEmail(email, "Brand New Shop", "ZZ-1", "") is SaveOutcome.Blocked)
  assertEquals(1, dao.allOrders().size)
  assertEquals(1, dao.allTrackingNumbers().size)
 }

 @Test fun anAmazonConfirmationFromTheInboxImportsAsOneNewOrderWithoutAnyReviewStep() = runBlocking<Unit> {
  val amazon = "\"Amazon.com\" <auto-confirm@amazon.com>"
  val confirmation = mail("am1", amazon, "Ordered 1 item: Tops", "Order # 111-9392767-7525812\nArriving Thursday\nTops")
  val group = groupedScanResults(listOf(confirmation), dao.allTrackingNumbers(), dao.allShipments(), dao.allOrders()).single()
  assertTrue(group.establishesOrder)

  assertTrue(vm.importScanGroup(group).startsWith("Imported order #111-9392767-7525812"))

  val order = dao.allOrders().single()
  assertEquals("Amazon.com", order.marketplace)
  assertEquals("Order Placed", order.status)
  assertEquals(order.id, dao.allProcessed().single { it.messageId == "am1" }.orderId)
  val rescan = groupedScanResults(listOf(confirmation), dao.allTrackingNumbers(), dao.allShipments(), dao.allOrders()).single()
  assertEquals("room:${order.id}", rescan.key)
 }

 @Test fun selectingAnExistingOrderAssociatesTheEmailAndCreatesNoDuplicate() = runBlocking<Unit> {
  val saved = seed("PayPal", "40FQ6HG")
  val email = mail("e1", "Customer Service <service@paypal.com>", "Order #40FQ6HG shipped", "Order #40FQ6HG\nTracking number: 38429466112\nCarrier: FedEx")
  val ranked = rankSavedOrders(email, dao.allOrders(), emptyList(), dao.allTrackingNumbers(), dao.allShipments())
  assertEquals(saved.id, ranked.first().order.id)
  assertEquals(MatchTier.VERIFIED, ranked.first().tier)

  assertEquals(SaveOutcome.Saved, vm.associateEmailWithOrder(email, ranked.first().order.id))

  assertEquals(1, dao.allOrders().size)
  assertEquals(saved.id, dao.allProcessed().single { it.messageId == "e1" }.orderId)
  assertEquals(1, dao.getShipments(saved.id).size)
  assertEquals(listOf("38429466112"), dao.allTrackingNumbers().map { it.number })
 }

 @Test fun aGenuinelyNewOrderIsCreatedFromThePrefilledValues() = runBlocking<Unit> {
  seed("eBay", "11-22222-33333")
  val email = mail("e2", simpli, "Thanks for your order #28855918", "Order number: 28855918\nItem: Root Beer Variety Pack\nQty: 2\nPrice: \$24.00")
  val form = AssociationFormState(prefillAssociation(email, dao.allOrders()))
  assertTrue(form.canCreate(dao.allOrders()))

  assertEquals(SaveOutcome.Saved, vm.createOrderFromEmail(email, form.retailer, form.number, form.title))

  val created = dao.allOrders().single { it.orderNumber == "28855918" }
  assertEquals("Simpli Soda", created.marketplace)
  assertEquals("Root Beer Variety Pack", created.name)
  assertEquals(2, dao.allOrders().size)
  assertEquals(created.id, dao.allProcessed().single { it.messageId == "e2" }.orderId)
 }

 @Test fun aManuallyEnteredRetailerIsUsedWhenTheEmailDoesNotNameOne() = runBlocking<Unit> {
  val email = mail("e3", "Customer Service <service@paypal.com>", "Order #AB-778899 confirmed", "Order #AB-778899")
  val form = AssociationFormState(prefillAssociation(email, emptyList()))
  assertEquals("", form.retailer)
  assertTrue(!form.canCreate(emptyList()))
  form.pickRetailer(RetailerOption(RetailerOptionKind.ADD_NEW, ""), emptyList())
  form.retailer = "Brand New Shop"
  assertTrue(form.canCreate(emptyList()))

  assertEquals(SaveOutcome.Saved, vm.createOrderFromEmail(email, form.retailer, form.number, form.title))

  val created = dao.allOrders().single()
  assertEquals("Brand New Shop", created.marketplace)
  assertEquals("AB-778899", created.orderNumber)
  assertEquals("Brand New Shop", created.name)
 }

 @Test fun creatingAnAlreadySavedRetailerAndOrderNumberIsBlockedAtTheDatabaseToo() = runBlocking<Unit> {
  seed("PayPal", "40FQ6HG", name = "Original")
  val email = mail("e4", "Customer Service <service@paypal.com>", "Order #40FQ6HG confirmed")
  val outcome = vm.createOrderFromEmail(email, "paypal", "40-FQ-6HG", "")
  assertTrue(outcome.toString(), outcome is SaveOutcome.Blocked)
  assertEquals(1, dao.allOrders().size)
  assertEquals("Original", dao.allOrders().single().name)
  assertTrue(dao.allProcessed().isEmpty())
 }

 @Test fun associatingTwiceOrCreatingAfterAssociatingNeverDuplicatesAnything() = runBlocking<Unit> {
  val saved = seed("Simpli Soda", "28855918")
  val email = mail("e5", simpli, "Order #28855918 has shipped", "Order #28855918\nTracking number: 38429466112\nCarrier: FedEx")
  assertEquals(SaveOutcome.Saved, vm.associateEmailWithOrder(email, saved.id))
  assertTrue(vm.createOrderFromEmail(email, "Simpli Soda", "28855918", "") is SaveOutcome.Blocked)
  assertEquals(1, dao.allOrders().size)
  assertEquals(1, dao.allShipments().size)
  assertEquals(1, dao.allTrackingNumbers().size)
 }
}
