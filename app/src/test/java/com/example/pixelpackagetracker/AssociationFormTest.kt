package com.example.pixelpackagetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// State and prefill logic behind the Needs Review association dialog (the Compose layer only renders and forwards callbacks to these).
class AssociationFormTest {
 private fun order(id: Long, market: String, number: String, name: String = market, archived: Boolean = false) =
  OrderRecord(id = id, marketplace = market, orderNumber = number, name = name, archived = archived)
 private fun mail(sender: String, subject: String, body: String = "") = GmailPreview(sender, subject, "Mon, 15 Jan 2024 10:00:00 +0000", "", "t", body, "m1")
 private val simpli = "Simpli Soda <orders@simplisoda.com>"

 // --- prefill ---
 @Test fun prefillsRetailerTitleAndOrderNumberFromTheParsedEmail() {
  val email = mail(simpli, "Thanks for your order #28855918", "Order number: 28855918\nItem: Root Beer Variety Pack\nQty: 2\nPrice: \$24.00")
  val prefill = prefillAssociation(email, emptyList())
  assertEquals("Simpli Soda", prefill.retailer)
  assertEquals("Root Beer Variety Pack", prefill.title)
  assertEquals("28855918", prefill.orderNumber)
  assertNull(prefill.suggestedRetailer)
 }

 @Test fun missingInformationStaysEmptyInsteadOfBeingInvented() {
  val prefill = prefillAssociation(mail("Ian <ian@gmail.com>", "Hello", "Nothing useful here"), emptyList())
  assertEquals("", prefill.retailer)
  assertEquals("", prefill.title)
  assertEquals("", prefill.orderNumber)
  val noDisplay = prefillAssociation(mail("ian@gmail.com", "Hello"), emptyList())
  assertEquals("", noDisplay.retailer)
 }

 @Test fun severalOrderNumbersAreOfferedButNeverGuessed() {
  val prefill = prefillAssociation(mail(simpli, "Your orders", "Order #28855918 shipped. Order #28855919 processing."), emptyList())
  assertEquals("", prefill.orderNumber)
  assertEquals(listOf("28855918", "28855919"), prefill.orderNumbersInEmail)
 }

 @Test fun prefilledRetailerUsesTheSavedSpellingAndIsSuggested() {
  val saved = listOf(order(1, "eBay", "11-22222-33333"))
  val prefill = prefillAssociation(mail("ebay@ebay.com", "Order confirmed: Order #12-34567-89012"), saved)
  assertEquals("eBay", prefill.retailer)
  assertEquals("eBay", prefill.suggestedRetailer)
  assertEquals(RetailerOptionKind.SUGGESTED_SAVED, retailerOptions(prefill, saved).first().kind)
 }

 // --- merchant versus payment processor ---
 @Test fun paymentProcessorEmailUsesTheMerchantItNames() {
  val email = mail("\"PayPal\" <service@paypal.com>", "Receipt for your payment to Acme Widgets", "You paid \$20.00 to Acme Widgets for order\nOrder number: AW-12345")
  val prefill = prefillAssociation(email, emptyList())
  assertEquals("Acme Widgets", prefill.retailer)
  assertEquals("PayPal", prefill.processor)
  assertEquals("AW-12345", prefill.orderNumber)
  assertTrue(prefill.retailerHint.contains("PayPal only processed the payment"))
 }

 @Test fun paymentProcessorEmailWithoutAMerchantLeavesRetailerEmpty() {
  val prefill = prefillAssociation(mail("Customer Service <service@paypal.com>", "Order #40FQ6HG confirmed"), emptyList())
  assertEquals("", prefill.retailer)
  assertEquals("40FQ6HG", prefill.orderNumber)
  assertEquals("PayPal", prefill.processor)
  assertTrue(prefill.retailerHint.contains("does not name the merchant"))
  assertNull(merchantFromProcessorEmail(mail("service@paypal.com", "You paid to PayPal", "Payment to you")))
 }

 // --- retailer options ---
 @Test fun retailerOptionsListSuggestionThenEverySavedRetailerOnceThenAddNew() {
  val saved = listOf(order(1, "PayPal", "A"), order(2, "PayPal", "B"), order(3, "eBay", "C"))
  val prefill = prefillAssociation(mail("service@ebay.com", "Order"), saved)
  val kinds = retailerOptions(prefill, saved).map { it.kind to it.name }
  assertEquals(listOf(RetailerOptionKind.SUGGESTED_SAVED to "eBay", RetailerOptionKind.SAVED to "PayPal", RetailerOptionKind.ADD_NEW to ""), kinds)
  val newOne = retailerOptions(prefillAssociation(mail(simpli, "Order"), saved), saved)
  assertTrue(newOne.any { it.kind == RetailerOptionKind.SUGGESTED_NEW && it.name == "Simpli Soda" })
 }

 // --- form state ---
 @Test fun pickingARetailerUpdatesTheDisplayedFieldAndNothingElse() {
  val saved = listOf(order(1, "eBay", "C", name = "Keyboard"))
  val form = AssociationFormState(prefillAssociation(mail(simpli, "Order #28855918 confirmed"), saved))
  form.title = "My title"; form.number = "28855918"
  form.pickRetailer(retailerOptions(form.prefill, saved).first { it.name == "eBay" }, saved)
  assertEquals("eBay", form.retailer)
  assertEquals("My title", form.title)
  assertEquals("28855918", form.number)
 }

 @Test fun manualEditsSurviveLaterPicksAndRecomputedPrefill() {
  val saved = listOf(order(1, "eBay", "C", name = "Keyboard"))
  val email = mail(simpli, "Order #28855918 confirmed")
  val form = AssociationFormState(prefillAssociation(email, saved))
  form.retailer = "My Own Shop"; form.title = "Typed title"; form.number = "ZZ-1"
  prefillAssociation(email, saved)
  form.pickTitle("Keyboard", saved)
  assertEquals("My Own Shop", form.retailer)
  assertEquals("Keyboard", form.title)
  form.pickEmailNumber("28855918")
  assertEquals("28855918", form.number)
 }

 @Test fun addNewRetailerClearsASavedNameButKeepsAnUnsavedOneForEditing() {
  val saved = listOf(order(1, "eBay", "C"))
  val unsaved = AssociationFormState(prefillAssociation(mail(simpli, "Order"), saved))
  unsaved.pickRetailer(RetailerOption(RetailerOptionKind.ADD_NEW, ""), saved)
  assertEquals("Simpli Soda", unsaved.retailer); assertTrue(unsaved.addingRetailer)
  unsaved.retailer = "Brand New Shop"
  assertEquals("Brand New Shop", unsaved.retailer)
  val onSaved = AssociationFormState(prefillAssociation(mail("service@ebay.com", "Order"), saved))
  onSaved.pickRetailer(RetailerOption(RetailerOptionKind.ADD_NEW, ""), saved)
  assertEquals("", onSaved.retailer)
 }

 @Test fun pickingATitleFillsAnEmptyRetailerOnly() {
  val saved = listOf(order(1, "eBay", "C", name = "Keyboard"))
  val empty = AssociationFormState(prefillAssociation(mail("ian@gmail.com", "Hello"), saved))
  empty.pickTitle("Keyboard", saved)
  assertEquals("eBay", empty.retailer)
 }

 // --- ranking and duplicate guard ---
 @Test fun savedOrdersAreRankedVerifiedThenLikelyThenSameRetailerAndArchivedOrdersStayAvailable() {
  val orders = listOf(order(1, "Other Shop", "X-1"), order(2, "Simpli Soda", "11111111"), order(3, "Simpli Soda", "28855918"), order(4, "Simpli Soda", "28855918", archived = true))
  val email = mail(simpli, "Order #28855918 shipped")
  val ranked = rankSavedOrders(email, orders, listOf(1L), emptyList(), emptyList())
  assertEquals(listOf(4L, 3L, 1L, 2L), ranked.map { it.order.id })
  assertEquals(listOf(MatchTier.VERIFIED, MatchTier.VERIFIED, MatchTier.LIKELY, MatchTier.SAME_RETAILER), ranked.map { it.tier })
 }

 // --- strongest-match default ---
 private val amazon = "Amazon.com <auto-confirm@amazon.com>"
 private fun ranked(email: GmailPreview, orders: List<OrderRecord>) = rankSavedOrders(email, orders, emptyList(), emptyList(), emptyList(), prefillAssociation(email, orders).retailer)

 @Test fun theOrderDropdownDefaultsToTheStrongestVerifiedMatchAndCanBeChanged() {
  val orders = listOf(order(1, "Amazon", "111-0000000-0000001"), order(2, "Amazon", "111-9392767-7525812"), order(3, "eBay", "E1"))
  val email = mail(amazon, "Your order has shipped", "Order #111-9392767-7525812")
  val list = ranked(email, orders)
  assertEquals(2L, strongestMatchId(list))
  val form = AssociationFormState(prefillAssociation(email, orders), strongestMatchId(list))
  assertEquals(2L, form.selectedOrderId)
  form.selectOrder(1L)
  assertEquals(1L, form.selectedOrderId)
  form.selectOrder(null)
  assertNull(form.selectedOrderId)
 }

 @Test fun weakOrNoMatchesDefaultToANewOrder() {
  val email = mail(amazon, "Order #111-9392767-7525812 confirmed")
  assertNull(strongestMatchId(ranked(email, emptyList())))
  assertNull(strongestMatchId(ranked(email, listOf(order(1, "Amazon", "111-0000000-0000001")))))
  assertNull(strongestMatchId(ranked(email, listOf(order(1, "eBay", "E1")))))
 }

 @Test fun twoEquallyStrongMatchesAreFlaggedAsTied() {
  val orders = listOf(order(1, "Amazon", "111-9392767-7525812"), order(2, "Amazon.com", "1119392767-7525812"))
  val list = ranked(mail(amazon, "Order #111-9392767-7525812 shipped"), orders)
  assertTrue(topMatchIsTied(list))
  assertFalse(topMatchIsTied(ranked(mail(amazon, "Order #111-9392767-7525812 shipped"), orders.take(1))))
 }

 @Test fun amazonAndAmazonDotComAreTheSameRetailerButDifferentShopsAreNot() {
  assertTrue(sameRetailerName("Amazon", "Amazon.com"))
  assertTrue(sameRetailerName("amazon.com", "AMAZON"))
  assertFalse(sameRetailerName("Amazon", "Amazon Fresh"))
  assertFalse(sameRetailerName("Costco", "Cost"))
  val saved = listOf(order(1, "Amazon", "111-9392767-7525812"))
  assertEquals("Amazon", prefillAssociation(mail(amazon, "Order"), saved).retailer)
  assertEquals(1L, existingOrderFor("Amazon.com", "1119392767-7525812", saved)?.id)
  assertEquals(1, retailerOptions(prefillAssociation(mail(amazon, "Order"), saved + order(2, "Amazon.com", "X-1")), saved + order(2, "Amazon.com", "X-1")).count { it.name.startsWith("Amazon") })
 }

 // --- retailer -> order number (parent -> child) ---
 private fun rank(email: GmailPreview, orders: List<OrderRecord>, retailer: String) = rankSavedOrders(email, orders, emptyList(), emptyList(), emptyList(), retailer)

 @Test fun orderNumberChoicesAreOnlyTheChosenRetailersOrdersThenEmailNumbersThenManualEntry() {
  val orders = listOf(order(1, "Amazon", "111-0000000-0000001"), order(2, "Amazon.com", "111-0000000-0000002"), order(3, "eBay", "E1"))
  val email = mail(amazon, "Ordered 1 item: Tops", "Order # 111-9392767-7525812")
  val prefill = prefillAssociation(email, orders)
  val choices = orderChoices(prefill, "Amazon", rank(email, orders, "Amazon"))
  assertEquals(setOf(1L, 2L), choices.filterIsInstance<OrderChoice.Saved>().map { it.ranked.order.id }.toSet())
  assertEquals(listOf("111-9392767-7525812"), choices.filterIsInstance<OrderChoice.FromEmail>().map { it.number })
  assertEquals(OrderChoice.Manual, choices.last())
  assertEquals(listOf(OrderChoice.Manual), orderChoices(prefillAssociation(mail("x@y.com", "Hi"), orders), "", rank(email, orders, "")))
 }

 @Test fun anEmailNumberThatMatchesASavedOrderIsListedOnceAsTheSavedOrder() {
  val orders = listOf(order(1, "Amazon", "111-9392767-7525812"))
  val email = mail(amazon, "Your order", "Order # 111-9392767-7525812")
  val choices = orderChoices(prefillAssociation(email, orders), "Amazon", rank(email, orders, "Amazon"))
  assertEquals(2, choices.size)
  assertTrue(choices.first() is OrderChoice.Saved && choices.last() == OrderChoice.Manual)
 }

 @Test fun theFormStartsOnTheStrongestSavedOrderOfTheEmailsRetailerElseOnTheEmailNumberAsANewOrder() {
  val orders = listOf(order(1, "Amazon", "111-9392767-7525812"), order(2, "eBay", "E1"))
  val email = mail(amazon, "Your order", "Order # 111-9392767-7525812")
  val known = initialAssociationForm(prefillAssociation(email, orders), rank(email, orders, "Amazon"))
  assertEquals(1L, known.selectedOrderId); assertEquals("Amazon", known.retailer); assertFalse(known.canCreate(orders))
  val fresh = initialAssociationForm(prefillAssociation(email, emptyList()), emptyList())
  assertNull(fresh.selectedOrderId); assertEquals("111-9392767-7525812", fresh.number); assertTrue(fresh.canCreate(emptyList()))
 }

 @Test fun aPaymentProcessorEmailWithNoMerchantStartsOnTheVerifiedSavedOrdersRetailer() {
  val orders = listOf(order(1, "PayPal", "40FQ6HG"))
  val email = mail("Customer Service <service@paypal.com>", "Order #40FQ6HG shipped", "Order #40FQ6HG")
  val form = initialAssociationForm(prefillAssociation(email, orders), rank(email, orders, ""))
  assertEquals(1L, form.selectedOrderId)
  assertEquals("PayPal", form.retailer)
 }

 @Test fun changingTheRetailerReselectsItsStrongestOrderOrFallsBackToTheEmailNumber() {
  val orders = listOf(order(1, "Amazon", "111-9392767-7525812"), order(2, "eBay", "E1"))
  val email = mail(amazon, "Your order", "Order # 111-9392767-7525812")
  val form = initialAssociationForm(prefillAssociation(email, orders), rank(email, orders, "Amazon"))
  form.pickRetailer(RetailerOption(RetailerOptionKind.SAVED, "eBay"), orders, ranked = rank(email, orders, "eBay"))
  assertEquals("eBay", form.retailer); assertNull(form.selectedOrderId); assertEquals("111-9392767-7525812", form.number)
  form.pickRetailer(RetailerOption(RetailerOptionKind.SAVED, "Amazon"), orders, ranked = rank(email, orders, "Amazon"))
  assertEquals(1L, form.selectedOrderId)
 }

 @Test fun anOrderNumberTheUserTypesIsKeptWhenTheyChangeRetailerAndCreatesANewOrder() {
  val orders = listOf(order(1, "Amazon", "111-9392767-7525812"), order(2, "eBay", "E1"))
  val email = mail(amazon, "Your order", "Order # 111-9392767-7525812")
  val form = initialAssociationForm(prefillAssociation(email, orders), rank(email, orders, "Amazon"))
  form.enterNumberManually()
  assertNull(form.selectedOrderId); assertEquals("", form.number); assertTrue(form.manualNumber)
  form.number = "999-0000000-1234567"
  assertTrue(form.canCreate(orders))
  form.pickRetailer(RetailerOption(RetailerOptionKind.SAVED, "eBay"), orders, ranked = rank(email, orders, "eBay"))
  assertEquals("999-0000000-1234567", form.number); assertNull(form.selectedOrderId)
  form.number = "E1"
  assertFalse(form.canCreate(orders))
  assertEquals(2L, form.existing(orders)?.id)
 }

 @Test fun typingAnewRetailerNameDeselectsTheSavedOrderOfTheOldRetailer() {
  val orders = listOf(order(1, "Amazon", "111-9392767-7525812"))
  val email = mail(amazon, "Your order", "Order # 111-9392767-7525812")
  val form = initialAssociationForm(prefillAssociation(email, orders), rank(email, orders, "Amazon"))
  assertEquals(1L, form.selectedOrderId)
  form.pickRetailer(RetailerOption(RetailerOptionKind.ADD_NEW, ""), orders)
  form.editRetailer("Brand New Shop")
  assertNull(form.selectedOrderId)
  assertEquals("111-9392767-7525812", form.number)
  assertTrue(form.canCreate(orders))
 }

 @Test fun anExistingRetailerAndOrderNumberCannotBeCreatedAgain() {
  val saved = listOf(order(1, "PayPal", "40FQ6HG"), order(2, "DSWATCH", "DSDF1"))
  val form = AssociationFormState(prefillAssociation(mail("service@paypal.com", "Hi"), saved))
  form.retailer = "paypal"; form.number = "40-FQ-6HG"
  assertEquals(1L, form.existing(saved)?.id)
  assertFalse(form.canCreate(saved))
  form.number = "40FQ6HX"
  assertTrue(form.canCreate(saved))
  form.retailer = "DS Designer"; form.number = "DSDF1"
  assertEquals(2L, form.existing(saved)?.id)
  form.retailer = ""; form.number = "N-1"
  assertFalse(form.canCreate(emptyList()))
 }
}
