package com.example.pixelpackagetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailMatchingTest {
 private fun order(id: Long, market: String, number: String, name: String = market) = OrderRecord(id = id, marketplace = market, orderNumber = number, name = name)
 private fun mail(id: String, sender: String, subject: String, body: String = "", thread: String = "t$id") =
  GmailPreview(sender, subject, "Mon, 15 Jan 2024 10:00:00 +0000", "", thread, body, id)
 private fun assign(emails: List<GmailPreview>, orders: List<OrderRecord>, shipments: List<ShipmentRecord> = emptyList(), numbers: List<ShipmentTrackingNumberRecord> = emptyList()) =
  assignEmails(emails.map(::buildEvidence), orders, shipments, numbers)
 private fun ship(id: Long, orderId: Long?) = ShipmentRecord(id = id, orderId = orderId)
 private fun num(id: Long, shipmentId: Long, number: String) = ShipmentTrackingNumberRecord(id = id, shipmentId = shipmentId, number = number, carrier = "FedEx")
 private val simpli = "Simpli Soda <orders@simplisoda.com>"

 // --- exact retailer + order number matching ---
 @Test fun exactRetailerAndOrderNumberMatch() {
  val result = assign(listOf(mail("m1", simpli, "Your Simpli Soda order #28855918 has shipped")), listOf(order(1, "Simpli Soda", "28855918")))
  assertEquals(EmailAssignment.Matched(1, viaOrderNumber = true, viaTracking = false), result["m1"])
 }

 @Test fun orderNumberMatchIgnoresCaseAndPunctuationButNeverSubstrings() {
  val orders = listOf(order(1, "eBay", "11-22222-33333"))
  val ebay = "eBay <ebay@ebay.com>"
  assertEquals(EmailAssignment.Matched(1, true, false), assign(listOf(mail("a", ebay, "Order number: 11-22222-33333")), orders)["a"])
  // Longer number containing the saved number, and a number extended with a suffix, are different orders.
  assertEquals(EmailAssignment.Unrelated, assign(listOf(mail("b", ebay, "Order number: 911-22222-33333")), orders)["b"])
  assertEquals(EmailAssignment.Unrelated, assign(listOf(mail("c", ebay, "Order number: 11-22222-33333-1")), orders)["c"])
  assertEquals(EmailAssignment.Unrelated, assign(listOf(mail("d", ebay, "Order number: 11-22222-3333")), orders)["d"])
 }

 @Test fun prefixCollisionBetweenTwoSavedOrdersPicksTheExactOne() {
  val orders = listOf(order(1, "Simpli Soda", "288559"), order(2, "Simpli Soda", "28855918"))
  val result = assign(listOf(mail("m1", simpli, "Order #28855918 shipped")), orders)
  assertEquals(EmailAssignment.Matched(2, true, false), result["m1"])
 }

 @Test fun savedOrderWithoutRetailerEvidenceIsNotMatched() {
  val result = assign(listOf(mail("m1", "someone@example.org", "Order #28855918 shipped", "Thanks")), listOf(order(1, "Simpli Soda", "28855918")))
  assertEquals(EmailAssignment.Unrelated, result["m1"])
 }

 // --- cross-retailer collisions ---
 @Test fun identicalOrderNumbersAtTwoRetailersGoToTheRetailerThatSentTheEmail() {
  val orders = listOf(order(1, "Simpli Soda", "ABC-12345"), order(2, "namokiMODS", "ABC-12345"))
  val result = assign(listOf(mail("m1", simpli, "Order #ABC-12345 shipped"), mail("m2", "namokiMODS <hello@namokimods.com>", "Order #ABC-12345 shipped")), orders)
  assertEquals(EmailAssignment.Matched(1, true, false), result["m1"])
  assertEquals(EmailAssignment.Matched(2, true, false), result["m2"])
 }

 @Test fun identicalOrderNumbersWithoutDistinguishingRetailerEvidenceAreAmbiguous() {
  val orders = listOf(order(1, "Simpli Soda", "ABC-12345"), order(2, "namokiMODS", "ABC-12345"))
  val forwarded = mail("m1", "Ian <ian@gmail.com>", "Fwd: Order #ABC-12345", "Simpli Soda and namokiMODS both wrote")
  val result = assign(listOf(forwarded), orders)["m1"] as EmailAssignment.Ambiguous
  assertEquals(setOf(1L, 2L), result.orderIds)
 }

 @Test fun trackingNumberBreaksACrossRetailerTie() {
  val orders = listOf(order(1, "Simpli Soda", "ABC-12345"), order(2, "namokiMODS", "ABC-12345"))
  val forwarded = mail("m1", "Ian <ian@gmail.com>", "Fwd: Order #ABC-12345", "Simpli Soda and namokiMODS. Tracking: 38429466112")
  val result = assign(listOf(forwarded), orders, listOf(ship(10, 2)), listOf(num(1, 10, "38429466112")))
  assertEquals(EmailAssignment.Matched(2, true, true), result["m1"])
 }

 // --- tracking associations ---
 @Test fun orderNumberAndTrackingPointingAtDifferentOrdersIsAmbiguous() {
  val orders = listOf(order(1, "Simpli Soda", "28855918"), order(2, "namokiMODS", "NM-77777"))
  val email = mail("m1", simpli, "Order #28855918 shipped", "Tracking number: 38429466112")
  val result = assign(listOf(email), orders, listOf(ship(10, 2)), listOf(num(1, 10, "38429466112")))["m1"] as EmailAssignment.Ambiguous
  assertEquals(setOf(1L, 2L), result.orderIds)
}

 @Test fun unknownExplicitOrderNumberCannotFallThroughToAnotherOrdersSavedTrackingNumber() {
  val existing = order(1, "namokiMODS", "NM-77777")
  val email = mail("m1", simpli, "Order #NEW-ORDER-7788 shipped", "Tracking number: 38429466112")
  val result = assign(listOf(email), listOf(existing), listOf(ship(10, 1)), listOf(num(1, 10, "38429466112")))["m1"]
  assertEquals(setOf(1L), (result as EmailAssignment.Ambiguous).orderIds)
 }

 @Test fun trackingNumberSavedOnTwoOrdersIsAmbiguousForCarrierMail() {
  val orders = listOf(order(1, "A Shop", "A-1000"), order(2, "B Shop", "B-2000"))
  val carrier = mail("c1", "FedEx <tracking@fedex.com>", "Your shipment 38429466112 is on the way")
  val result = assign(listOf(carrier), orders, listOf(ship(10, 1), ship(11, 2)), listOf(num(1, 10, "38429466112"), num(2, 11, "38429466112")))["c1"]
  assertEquals(setOf(1L, 2L), (result as EmailAssignment.Ambiguous).orderIds)
 }

 @Test fun orderlessCarrierMailAssociatesThroughAnAlreadySavedTrackingNumber() {
  val savedOrder = order(1, "Simpli Soda", "28855918")
  val carrier = mail("c1", "FedEx <tracking@fedex.com>", "Your shipment is in transit", "Tracking number: 38429466112")
  val result = assign(listOf(carrier), listOf(savedOrder), listOf(ship(10, 1)), listOf(num(1, 10, "38429466112")))
  assertEquals(EmailAssignment.Matched(1, false, true), result["c1"])
 }

 @Test fun carrierMailJoinsAnOrderThroughOneUniqueTrackingNumber() {
  val orders = listOf(order(1, "Simpli Soda", "28855918"), order(2, "namokiMODS", "NM-77777"))
  val retailer = mail("r1", simpli, "Order #28855918 shipped", "Tracking number: 38429466112")
  val carrier = mail("c1", "FedEx <tracking@fedex.com>", "Your shipment 38429466112 is on the way")
  val unrelated = mail("c2", "FedEx <tracking@fedex.com>", "Your shipment 99999999999 is on the way")
  val result = assign(listOf(retailer, carrier, unrelated), orders)
  assertEquals(EmailAssignment.Matched(1, true, false), result["r1"])
  assertEquals(EmailAssignment.Matched(1, false, true), result["c1"])
  assertEquals(EmailAssignment.Unrelated, result["c2"])
 }

 @Test fun trackingNumberClaimedByTwoOrdersIsNotAssignedToEither() {
  val orders = listOf(order(1, "Simpli Soda", "28855918"), order(2, "namokiMODS", "NM-77777"))
  val a = mail("r1", simpli, "Order #28855918 shipped", "Tracking number: 38429466112")
  val b = mail("r2", "namokiMODS <hello@namokimods.com>", "Order #NM-77777 shipped", "Tracking number: 38429466112")
  val carrier = mail("c1", "FedEx <tracking@fedex.com>", "Your shipment 38429466112 is on the way")
  val result = assign(listOf(a, b, carrier), orders)
  listOf("r1", "r2", "c1").forEach { assertTrue(it, result[it] is EmailAssignment.Ambiguous) }
 }

 @Test fun messageNamingSeveralOrderNumbersIsAmbiguous() {
  val orders = listOf(order(1, "Simpli Soda", "28855918"), order(2, "Simpli Soda", "28855919"))
  val digest = mail("m1", simpli, "Your orders", "Order #28855918 shipped. Order #28855919 processing.")
  assertEquals(setOf(1L, 2L), (assign(listOf(digest), orders)["m1"] as EmailAssignment.Ambiguous).orderIds)
 }

 @Test fun multipleEmailsForOneOrderAreAllAssignedToIt() {
  val orders = listOf(order(1, "Simpli Soda", "28855918"))
  val emails = listOf(
   mail("m1", simpli, "Thanks for your order #28855918", "Order date: January 3, 2024"),
   mail("m2", simpli, "Your Simpli Soda order #28855918 has shipped", "Tracking number: 38429466112"),
   mail("m3", "FedEx <tracking@fedex.com>", "Shipment 38429466112 delivered"))
  val result = assign(emails, orders)
  assertTrue(result.values.all { it is EmailAssignment.Matched && it.orderId == 1L })
 }

 @Test fun persistedMessageAssociationOutranksConflictingInferredOrderAndMultipleOwnersAreAmbiguous() {
  val orders = listOf(order(1, "Simpli Soda", "28855918"), order(2, "Simpli Soda", "28855777"))
  val email = mail("saved", simpli, "Order #28855777 has shipped")
  val saved = assignEmails(listOf(buildEvidence(email)), orders, emptyList(), emptyList(), persisted = mapOf("saved" to setOf(1L)))
  assertEquals(EmailAssignment.Matched(1, false, false, viaPersisted = true), saved["saved"])
  val conflict = assignEmails(listOf(buildEvidence(email)), orders, emptyList(), emptyList(), persisted = mapOf("saved" to setOf(1L, 2L)))
  assertEquals(setOf(1L, 2L), (conflict["saved"] as EmailAssignment.Ambiguous).orderIds)
 }

 @Test fun savedOrderResolutionUsesNormalizedNumberAndVerifiedRetailer() {
  val saved = order(1, "Foo Shop", "ZX-99887766")
  val email = mail("m", "Foo Shop <orders@fooshop.com>", "Your order #ZX99887766 has shipped")
  assertEquals(listOf(saved), exactSavedOrderCandidates("ZX99887766", listOf(email), listOf(saved)))
  assertTrue(exactSavedOrderCandidates("ZX99887766", listOf(email), listOf(order(2, "Bar Store", "ZX-99887766"))).isEmpty())
 }

 @Test fun leadingLetterOrderNumberDifferenceIsFlaggedAsPossibleVariantNotAnExactMatch() {
  assertTrue(possibleOrderNumberVariant("M40FQ6HG", "40FQ6HG"))
  assertFalse(possibleOrderNumberVariant("X40FQ6HG", "Y40FQ6HG"))
  assertFalse(possibleOrderNumberVariant("140FQ6HG", "40FQ6HG"))
 }

 // --- identifiers ---
 @Test fun identifiersMatchWholeTokensOnly() {
  val tokens = identifierTokens("Tracking 123456789012345 and 9400 1118 9922 3344 5566 77 done")
  assertTrue("123456789012345" in tokens)
  assertFalse("12345678901" in tokens)
  assertTrue("9400111899223344556677" in tokens)
 }

 @Test fun orderNumbersRequireALabelAndADigit() {
  assertEquals(listOf("28855918"), extractOrderNumbers("Your order #28855918 has shipped"))
  assertEquals(listOf("11-22222-33333"), extractOrderNumbers("Order number: 11-22222-33333 Order #: 11-22222-33333"))
  assertTrue(extractOrderNumbers("Order number: Details below").isEmpty())
  assertEquals(2, extractOrderNumbers("Order #28855918 and order #28855919").size)
 }

 @Test fun trackingCandidatesAreNeverInvented() {
  val noTracking = mail("e1", "eBay <ebay@ebay.com>", "Your order has shipped", "Order number: 11-22222-33333. Your item is with its carrier USPS.")
  assertTrue(extractTrackingCandidates(noTracking).isEmpty())
  assertNull(extractEmailFinding(noTracking, emptyList(), emptyList()).tracking)
  val spaced = mail("e2", "x@y.com", "Hello", "Tracking number: Items")
  assertTrue(extractTrackingCandidates(spaced).isEmpty())
 }

 @Test fun savedTrackingNumberIsNotFoundInsideALongerNumber() {
  val longer = mail("e1", "FedEx <t@fedex.com>", "Update", "Reference 138429466112999 only")
  val saved = listOf(num(1, 10, "38429466112"))
  assertNull(extractEmailFinding(longer, saved, listOf(ship(10, 1))).matchedShipment)
 }

 // --- retailer evidence ---
 @Test fun retailerEvidenceLevels() {
  val o = order(1, "Simpli Soda", "1")
  assertEquals(RetailerEvidence.STRONG, retailerEvidence(o, mail("a", simpli, "x")))
  assertEquals(RetailerEvidence.STRONG, retailerEvidence(o, mail("a", "x@mail.simplisoda.com", "x")))
  assertEquals(RetailerEvidence.WEAK, retailerEvidence(o, mail("a", "ian@gmail.com", "Fwd: Simpli Soda receipt")))
  assertEquals(RetailerEvidence.NONE, retailerEvidence(o, mail("a", "ian@gmail.com", "Hello", "nothing relevant")))
  // The generic marketplace "Other" never matches by name.
  assertEquals(RetailerEvidence.NONE, retailerEvidence(order(2, "Other", "1", name = "Keyboard"), mail("a", "x@other.com", "Other things")))
 }

 // --- parser version / provenance rules ---
 @Test fun reanalysisOnlyForOlderParserVersionsWhenAllowed() {
  assertTrue(shouldProcessMessage(null, reanalyze = false))
  assertFalse(shouldProcessMessage(GMAIL_PARSER_VERSION, reanalyze = true))
  assertTrue(shouldProcessMessage(GMAIL_PARSER_VERSION - 1, reanalyze = true))
  assertFalse(shouldProcessMessage(GMAIL_PARSER_VERSION - 1, reanalyze = false))
 }

 @Test fun manualOrderStatusIsNotDerivedOverButDerivedAndGmailStatusesAre() {
  fun row(source: String) = OrderChangeHistory(orderId = 1, field = "Order status", oldValue = "a", newValue = "b", sourceMessageId = source)
  assertTrue(mayDeriveOrderStatus(null))
  assertTrue(mayDeriveOrderStatus(row("18c2f0a1b2c3d4e5")))
  assertTrue(mayDeriveOrderStatus(row(SOURCE_DERIVED)))
  assertFalse(mayDeriveOrderStatus(row(SOURCE_MANUAL)))
 }

 @Test fun historySourceDescriptions() {
  assertEquals("Manual edit", describeHistorySource(SOURCE_MANUAL, ""))
  assertEquals("Inbox import", describeHistorySource(SOURCE_IMPORT, ""))
  assertEquals("Source not recorded", describeHistorySource("", ""))
  assertEquals("Gmail message dated D", describeHistorySource("abc", "D"))
  assertEquals("Tracking number (shipment 2)", shipmentFieldLabel("Tracking number", 1, 2))
  assertEquals("Tracking number", shipmentFieldLabel("Tracking number", 0, 1))
 }

 // --- correspondence grouping ---
 @Test fun groupingDeduplicatesByMessageIdAndJoinsRetailerAndCarrierThroughTracking() {
  val confirmation = mail("c0", simpli, "Order #28855918 confirmed")
  val retailer = mail("r1", simpli, "Your Simpli Soda order #28855918 has shipped", "Tracking number: 38429466112")
  val carrier = mail("c1", "FedEx <tracking@fedex.com>", "Your shipment 38429466112 is on the way")
  val groups = groupedScanResults(listOf(confirmation, retailer, retailer, carrier), emptyList(), emptyList(), emptyList())
  assertEquals(1, groups.size)
  assertEquals(listOf("c0", "r1", "c1"), groups.single().emails.map { it.messageId })
  assertEquals("28855918", groups.single().orderNumber)
  assertEquals("38429466112", groups.single().tracking)
 }

 @Test fun groupingKeepsEqualOrderNumbersFromDifferentRetailersApart() {
  val a = mail("a", simpli, "Order #ABC-12345 shipped")
  val b = mail("b", "namokiMODS <hello@namokimods.com>", "Order #ABC-12345 shipped")
  assertEquals(2, groupedScanResults(listOf(a, b), emptyList(), emptyList(), emptyList()).size)
 }

 @Test fun groupingUsesVerifiedRetailerAliasesForExactOrderNumbers() {
  val dswatch = mail("ds1", "DSWATCH <orders@dswatch.com>", "Order #DSDF66669184 confirmed")
  val designer = mail("ds2", "DS Designer <service@dsdesigner.example>", "Order #DSDF66669184 has shipped")
  val groups = groupedScanResults(listOf(dswatch, designer), emptyList(), emptyList(), emptyList())
  assertEquals(1, groups.size)
  assertEquals(setOf("ds1", "ds2"), groups.single().emails.map { it.messageId }.toSet())
 }

 @Test fun groupingFlagsLeadingLetterOrderNumberVariantWithoutCombiningOrImportingIt() {
  val marked = mail("ps1", "Customer Service <help@paypal.com>", "Order #M40FQ6HG confirmed")
  val unmarked = mail("ps2", "service@paypal.com", "Order #40FQ6HG shipped")
  val groups = groupedScanResults(listOf(marked, unmarked), emptyList(), emptyList(), emptyList())
  assertEquals(2, groups.size)
  assertTrue(groups.all { it.review?.startsWith("Conflicting identifiers") == true })
 }

 @Test fun sameThreadOrderNumberVariantIsNeverJoinedAutomaticallyToTheExistingOrder() {
  val saved = order(7, "PayPal", "40FQ6HG")
  val original = mail("original", "Customer Service <help@paypal.com>", "Order #M40FQ6HG confirmed", thread = "pay-thread")
  val reply = mail("reply", "service@paypal.com", "Order #40FQ6HG shipped", thread = "pay-thread")
  val groups = groupedScanResults(listOf(original, reply), emptyList(), emptyList(), listOf(saved))
  val existing = groups.single { it.key == "room:7" }
  assertEquals(listOf("reply"), existing.emails.map { it.messageId })
  val variant = groups.single { it.key != "room:7" }
  assertEquals(listOf("original"), variant.emails.map { it.messageId })
  assertTrue(variant.candidateOrderIds.contains(7L))
 }

 @Test fun sameThreadOrderNumberVariantStaysSeparateWhenNoOrderIsSaved() {
  val confirmation = mail("original", "Customer Service <help@paypal.com>", "Order #M40FQ6HG confirmed", thread = "pay-thread")
  val shipping = mail("shipping", "service@paypal.com", "Order #40FQ6HG shipped", thread = "pay-thread")
  val groups = groupedScanResults(listOf(confirmation, shipping), emptyList(), emptyList(), emptyList())
  assertEquals(2, groups.size)
  assertEquals(listOf("original"), groups.single { it.orderNumber == "M40FQ6HG" }.emails.map { it.messageId })
  assertTrue(groups.all { it.review?.startsWith("Conflicting identifiers") == true })
 }

 @Test fun existingOrderMatchedByExactRetailerAndOrderNumberIsAnExistingGroupNotANewImport() {
  val saved = order(3, "Simpli Soda", "28855918")
  val ship = mail("s", simpli, "Your Simpli Soda order #28855918 has shipped", "Tracking number: 38429466112")
  val group = groupedScanResults(listOf(ship), emptyList(), emptyList(), listOf(saved)).single()
  assertEquals("room:3", group.key)
  assertFalse(group.establishesOrder)
  assertTrue(group.review!!.startsWith("Existing order"))
 }

 @Test fun savedShipmentBelongsToItsOrderWhenCarrierMailArrives() {
  val saved = order(3, "Simpli Soda", "28855918")
  val carrier = mail("c", "FedEx <tracking@fedex.com>", "Your shipment is in transit", "Tracking number: 38429466112")
  val group = groupedScanResults(listOf(carrier), listOf(num(1, 10, "38429466112")), listOf(ship(10, 3)), listOf(saved)).single()
  assertEquals("room:3", group.key)
  assertEquals(listOf("c"), group.emails.map { it.messageId })
 }

 @Test fun twoTrackingNumbersWithoutPackageOrHandoffEvidenceAreHeldOutForReviewNotAppliedToTheOrder() {
  val confirmation = mail("c", simpli, "Order #28855918 confirmed")
  val shipping = mail("s", simpli, "Order #28855918 has shipped", "Tracking number: 38429466112\nTracking number: 29384756102")
  val groups = groupedScanResults(listOf(confirmation, shipping), emptyList(), emptyList(), emptyList())
  val order = groups.single { !it.key.startsWith("review:") }
  assertEquals(listOf("c"), order.emails.map { it.messageId })
  assertEquals(null, order.tracking)
  val held = groups.single { it.key.startsWith("review:") }
  assertEquals(listOf("s"), held.emails.map { it.messageId })
  assertTrue(held.review!!.startsWith("Lists several tracking numbers"))
 }

 @Test fun differentOrderNumbersInOneThreadAreNeverMergedAndEqualNumbersAtOtherRetailersStayApart() {
  val a = mail("a", simpli, "Order #28855918 confirmed", thread = "T")
  val b = mail("b", simpli, "Order #28855999 confirmed", thread = "T")
  val groups = groupedScanResults(listOf(a, b), emptyList(), emptyList(), emptyList())
  assertEquals(2, groups.size)
  assertEquals(setOf("28855918", "28855999"), groups.map { it.orderNumber }.toSet())
 }

 @Test fun confirmationWithUnfamiliarWordingAndRetailerCreatesANewOrderGroup() {
  val subjects = listOf("Thank you for your order #5521788", "Ordered: Widget", "We received your order 5521788", "Your receipt for order 5521788")
  subjects.forEachIndexed { index, subject ->
   val body = if(index == 1) "Order #5521788" else ""
   val group = groupedScanResults(listOf(mail("m$index", "New Store <shop@newstore.example>", subject, body)), emptyList(), emptyList(), emptyList()).single()
   assertTrue(subject, group.establishesOrder)
   assertEquals(subject, "5521788", group.orderNumber)
  }
 }

 @Test fun malformedSenderHeadersFallBackToTheDomainBrandInsteadOfShowingFragments() {
  assertEquals("Paypal", retailerNameFromSender("Com\" <service@paypal.com>"))
  assertEquals("Paypal", retailerNameFromSender("\"service@paypal.com\" <service@paypal.com>"))
  assertEquals("paypal.com", senderDomain("\"service@paypal.com\" <service@paypal.com>"))
  assertEquals("x.example", senderDomain("a@x.example"))
  assertEquals("Simpli Soda", retailerNameFromSender("\"Simpli Soda\" <orders@simplisoda.com>"))
  assertEquals("Paypal", retailerNameFromSender("Customer Service <service@paypal.com>"))
 }

 @Test fun anOrderConfirmationIsNeverClassifiedAsShippingProgressEvenWhenItsBodyMentionsDelivery() {
  val amazon = "\"Amazon.com\" <auto-confirm@amazon.com>"
  val body = "Order # 111-9392767-7525812\nYour package is on its way to you.\nShipped with Amazon Logistics.\nTrack your package"
  assertEquals(EmailKind.CONFIRMATION, classifyEmail(mail("a1", amazon, "Ordered 1 item: Tops", body)))
  assertEquals(EmailKind.CONFIRMATION, classifyEmail(mail("a1", "Orders <orders@shop.example>", "Ordered 2 items", body)))
  // The same body from a shipping sender and subject is still shipping progress.
  assertEquals(EmailKind.SHIPPED, classifyEmail(mail("a2", "\"Amazon.com\" <shipment-tracking@amazon.com>", "Shipped: 1 item", body)))
 }

 @Test fun amazonStyleConfirmationWithoutAColonAfterOrderedSeedsANewOrderGroup() {
  val amazon = "\"Amazon.com\" <auto-confirm@amazon.com>"
  val confirmation = mail("a1", amazon, "Ordered 1 item: Tops", "Order # 111-9392767-7525812\nArriving Thursday\nTops\nOrder Total: \$19.99")
  val group = groupedScanResults(listOf(confirmation), emptyList(), emptyList(), emptyList()).single()
  assertTrue(group.establishesOrder)
  assertEquals("111-9392767-7525812", group.orderNumber)
  assertEquals("Amazon.com", group.title)
  assertTrue(!group.key.startsWith("review:"))
 }

 @Test fun aVerifiedRetailerShipmentNoticeWithOrderDetailsEstablishesTheOrderAndAConfirmationStillOwnsTheBasis() {
  val amazon = "\"Amazon.com\" <auto-confirm@amazon.com>"
  val shipper = "\"Amazon.com\" <shipment-tracking@amazon.com>"
  val confirmation = mail("a1", amazon, "Ordered 1 item: Tops", "Order # 111-9392767-7525812")
  val shipped = mail("a2", shipper, "Shipped: 1 item", "Order # 111-9392767-7525812\nTracking number: 1Z999AA10123456784\nCarrier: UPS")
  val alone = groupedScanResults(listOf(shipped), emptyList(), emptyList(), emptyList()).single()
  assertTrue(alone.establishesOrder)
  assertEquals("Retailer shipping notice", alone.basis)
  assertFalse(alone.key.startsWith("review:"))
  val together = groupedScanResults(listOf(confirmation, shipped), emptyList(), emptyList(), emptyList()).single()
  assertTrue(together.establishesOrder)
  assertEquals("Order confirmation", together.basis)
  assertEquals(setOf("a1", "a2"), together.emails.map { it.messageId }.toSet())
  assertEquals("1Z999AA10123456784", together.tracking)
 }

 @Test fun candidateEvidenceExplainsRetailerOrderNumberAndTracking() {
  val saved = order(1, "Simpli Soda", "28855918")
  val text = candidateEvidenceText(saved, mail("m", simpli, "Order #28855918 shipped", "Tracking number: 38429466112"), listOf(num(1, 10, "38429466112")), listOf(ship(10, 1)))
  assertTrue(text, text.contains("sender matches the retailer") && text.contains("exact order number") && text.contains("tracking matches a saved shipment"))
  val other = candidateEvidenceText(saved, mail("m", "x@example.org", "Order #99999999 shipped"), emptyList(), emptyList())
  assertTrue(other, other.contains("retailer not verified") && other.contains("different order number"))
 }

 @Test fun groupingUsesEveryExtractedTrackingNumberToJoinCarrierCorrespondence() {
  val confirmation = mail("confirm", simpli, "Order #28855918 confirmed")
  val retailer = mail("ret", simpli, "Order #28855918 has shipped", "Tracking number: 38429466112")
  val handoff = mail("handoff", simpli, "Order #28855918 updated", "Tracking number: 9400111899223344556677")
  val carrier = mail("carrier", "USPS <updates@usps.com>", "Tracking update", "Tracking number: 9400111899223344556677")
  val group = groupedScanResults(listOf(confirmation, retailer, handoff, carrier), emptyList(), emptyList(), emptyList()).single()
  assertEquals(setOf("confirm", "ret", "handoff", "carrier"), group.emails.map { it.messageId }.toSet())
 }

 @Test fun sameOrderNumberFollowupFromStronglyVerifiedAlternateSenderJoinsConfirmation() {
  val confirmation = mail("confirm", "namokiMODS <orders@namokimods.com>", "Order #4RZ128844G confirmed")
  val followup = mail("ship", "namokiMODS Shipping <dispatch@fulfillment.example>", "Order #4RZ128844G has shipped", "Tracking number: 1Z999AA10123456784")
  val group = groupedScanResults(listOf(confirmation, followup), emptyList(), emptyList(), emptyList()).single()
  assertEquals(setOf("confirm", "ship"), group.emails.map { it.messageId }.toSet())
  assertEquals("4RZ128844G", group.orderNumber)
 }

 @Test fun multiTrackingShippingEmailWithHandoffWordingStaysGroupedWithItsConfirmation() {
  val confirmation = mail("confirm", "namokiMODS <orders@namokimods.com>", "Order #4RZ128844G confirmed")
  val shipping = mail("multi", "namokiMODS <orders@namokimods.com>", "Order #4RZ128844G has shipped",
   "Tracking number: 1Z999AA10123456784\nTracking number: 9400111899223344556677\nHanded off to USPS for final delivery.")
  val group = groupedScanResults(listOf(confirmation, shipping), emptyList(), emptyList(), emptyList()).single()
  assertEquals(setOf("confirm", "multi"), group.emails.map { it.messageId }.toSet())
  assertTrue(group.establishesOrder)
 }

 @Test fun uspsRoutingPrefixedNumberIsTheSameTrackingNumber() {
  val plain = mail("a", "USPS <updates@usps.com>", "Update", "Tracking number: 9200190323035502367037")
  val prefixed = mail("b", "USPS <updates@usps.com>", "Update", "Tracking number: 420286019200190323035502367037")
  assertEquals(listOf("9200190323035502367037"), extractTrackingCandidates(prefixed))
  assertEquals(listOf("9200190323035502367037"), dedupeTracking(extractTrackingCandidates(plain) + extractTrackingCandidates(prefixed)))
  assertTrue(sameTrackingNumber("9200190323035502367037", "9200 1903 2303 5502 3670 37"))
  assertFalse(sameTrackingNumber("99123456789012", "123456789012"))
  assertFalse(sameTrackingNumber("123456789012", "123456789013"))
 }

 @Test fun twoEmailsOfOneOrderWithTheSameTrackingInDifferentFormsShowOneTrackingNumber() {
  val confirmation = mail("c", "namokiMODS <orders@namokimods.com>", "Order #4RZ128844G confirmed", "Tracking number: 9200190323035502367037")
  val shipping = mail("s", "namokiMODS <orders@namokimods.com>", "Order #4RZ128844G has shipped", "Tracking number: 420286019200190323035502367037")
  val group = groupedScanResults(listOf(confirmation, shipping), emptyList(), emptyList(), emptyList()).single()
  assertEquals("9200190323035502367037", group.tracking)
  assertEquals(setOf("c", "s"), group.emails.map { it.messageId }.toSet())
 }

 @Test fun trackingNumberThatIsOnlyAFragmentOfALongerOneIsNotMergedWithIt() {
  val email = mail("frag", "USPS <updates@usps.com>", "Tracking update", "Tracking number: 9200190323035502367037 and 92001903230355023670")
  assertEquals(listOf("9200190323035502367037", "92001903230355023670"), extractTrackingCandidates(email))
 }

 @Test fun groupingDoesNotMatchASavedOrderFromAnotherRetailer() {
  val saved = listOf(order(1, "namokiMODS", "ABC-12345"))
  val group = groupedScanResults(listOf(mail("a", simpli, "Order #ABC-12345 shipped")), emptyList(), emptyList(), saved).single()
  assertEquals("Simpli Soda", group.title)
  assertTrue(group.review!!.startsWith("Order number is present"))
 }

 @Test fun groupingUsesSavedOrderRetailerWhenRetailerAndNumberAgree() {
  val saved = listOf(order(1, "Simpli Soda", "28855918", name = "Soda bundle"))
  val group = groupedScanResults(listOf(mail("a", simpli, "Order #28855918 shipped")), emptyList(), emptyList(), saved).single()
  assertEquals("Simpli Soda", group.title)
 }

 @Test fun groupingFlagsRetailerShipmentEvidenceWithoutTrackingNumber() {
  val email = mail("e1", "eBay <ebay@ebay.com>", "Your item has shipped", "Order number: 11-22222-33333. Your item is with its carrier USPS.")
  val group = groupedScanResults(listOf(email), emptyList(), emptyList(), emptyList()).single()
  assertFalse(group.establishesOrder)
  assertTrue(group.review!!.startsWith("Order number is present"))
  assertNull(group.tracking)
  assertEquals("11-22222-33333", group.orderNumber)
 }

 @Test fun unrelatedMessageWithNaturalLanguageNearTrackingLabelIsNotShownAsShipmentReview() {
  val email = mail("notice", "Ian <ian@example.net>", "Account notice", "Tracking number: 20Documents")
  assertTrue(groupedScanResults(listOf(email), emptyList(), emptyList(), emptyList()).isEmpty())
 }

 @Test fun customerServiceMessageWithOrderNumberButNoSupportedUpdateIsIgnored() {
  val confirmation = mail("confirm", simpli, "Thanks for your order #28855918", "Order date: January 3, 2024")
  val service = mail("service", simpli, "Customer service case about order #28855918", "Please call us about your question.")
  val group = groupedScanResults(listOf(confirmation, service), emptyList(), emptyList(), emptyList()).single()
  assertEquals(listOf("confirm"), group.emails.map { it.messageId })
 }
}
