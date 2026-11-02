package com.example.pixelpackagetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression tests for the Gmail text pipeline (HTML, entities, MIME words, links, carriers, classification) and staged association.
class GmailParsingTest {
 private fun mail(id: String, sender: String, subject: String, body: String = "", thread: String = "t$id") =
  GmailPreview(sender, subject, "Mon, 15 Jan 2024 10:00:00 +0000", "", thread, body, id)
 private fun order(id: Long, market: String, number: String, status: String = "Order Placed", name: String = market) =
  OrderRecord(id = id, marketplace = market, orderNumber = number, name = name, status = status)
 private fun assign(emails: List<GmailPreview>, orders: List<OrderRecord>, shipments: List<ShipmentRecord> = emptyList(),
  numbers: List<ShipmentTrackingNumberRecord> = emptyList(), aliases: AliasBook = AliasBook.default, manual: Map<String, Long> = emptyMap()) =
  assignEmails(emails.map(::buildEvidence), orders, shipments, numbers, aliases, manual)
 private val simpli = "Simpli Soda <orders@simplisoda.com>"
 private val fedex = "FedEx <TrackingUpdates@fedex.com>"

 @Test fun gmailDiscoveryQueryRemainsInboxOnlyAcrossPages() {
  assertEquals("messages?maxResults=100&labelIds=INBOX", gmailInboxMessagesListPath(null))
  assertEquals("messages?maxResults=100&labelIds=INBOX&pageToken=next%2Bpage%2Ftoken", gmailInboxMessagesListPath("next+page/token"))
 }

 // --- HTML, entities, whitespace ---

 @Test fun htmlKeepsLinkTargetsAndDecodesEntities() {
  val text = htmlToText("<p>Track: <a href=\"https://www.fedex.com/fedextrack/?trknbr=38429466112&amp;x=1\">Track it</a></p><style>.a{}</style>")
  assertTrue(text, text.contains("[link: https://www.fedex.com/fedextrack/?trknbr=38429466112&x=1]"))
  assertFalse(text.contains(".a{}"))
 }

 @Test fun numericEntitiesAndNonBreakingSpacesNoLongerHideATrackingNumber() {
  val body = htmlToText("<p>Putting the tracking number below into FedEx:&#160;<strong>38429466112</strong></p>")
  assertEquals(listOf("38429466112"), extractTrackingCandidates(mail("m", simpli, "Your order has shipped", body)))
  assertEquals("A B", normalizeSpaces("A\u00a0\u200bB").replace("  ", " "))
 }

 // --- tracking numbers: the requested example and evidence rules ---

 @Test fun theRequestedSentenceYieldsFedExAndTheNumber() {
  val email = mail("m", simpli, "Your order has shipped", "Putting the tracking number below into FedEx: 38429466112")
  assertEquals(listOf("38429466112"), extractTrackingCandidates(email))
  assertEquals("FedEx", detectCarrier(email, "38429466112"))
  val finding = extractEmailFinding(email, emptyList(), emptyList())
  assertEquals("38429466112", finding.tracking); assertEquals("FedEx", finding.carrier)
 }

 @Test fun numberOnTheNextLineAndSplitSentencesAreStillFound() {
  assertEquals(listOf("38429466112"), extractTrackingCandidates(mail("m", simpli, "Shipped", "Putting the tracking number below into FedEx:\n38429466112")))
  assertEquals(listOf("38429466112"), extractTrackingCandidates(mail("m", fedex, "Update", "TRACKING NUMBER\n38429466112\nFROM SIMPLI SODA")))
 }

 @Test fun carrierTrackingLinksAreAuthorizedSourcesButRetailerLinksAreNot() {
  val carrierLink = mail("m", simpli, "Shipped", "Track [link: https://www.fedex.com/fedextrack/?trknbr=38429466112]")
  assertEquals(listOf("38429466112"), extractTrackingCandidates(carrierLink))
  assertEquals("FedEx", detectCarrier(carrierLink, "38429466112"))
  val redirected = mail("m", simpli, "Shipped", "[link: https://click.example.com/r?u=https%3A%2F%2Fwww.ups.com%2Ftrack%3Ftracknum%3D1Z999AA10123456784]")
  assertEquals(listOf("1Z999AA10123456784"), extractTrackingCandidates(redirected))
  val retailerLink = mail("m", "eBay <ebay@ebay.com>", "Shipped", "Track package [link: https://www.ebay.com/vod/Fetch?itemid=1234567890123&transid=998877665544]")
  assertTrue(extractTrackingCandidates(retailerLink).isEmpty())
  val lookalike = mail("m", simpli, "Shipped", "[link: https://click.groups.com/?tracknum=38429466112]")
  assertTrue(extractTrackingCandidates(lookalike).isEmpty())
 }

 @Test fun orderAndReferenceNumbersAreNeverTrackingNumbers() {
  assertTrue(extractTrackingCandidates(mail("m", simpli, "Shipped", "Your order #1234567890 has shipped. Tracking info for order 1234567890.")).isEmpty())
  assertTrue(extractTrackingCandidates(mail("m", simpli, "Hi", "Tracking reference 9988776655 for invoice 1122334455")).isEmpty())
  assertTrue(extractTrackingCandidates(mail("m", simpli, "Shipped", "Tracking number will be emailed once available. Phone 8005551234")).isEmpty())
 }

 @Test fun naturalLanguageTokensAreNotTrackingNumbersButMixedIdentifiersWithEnoughDigitsAre() {
  assertTrue(extractTrackingCandidates(mail("m", simpli, "Update", "Tracking number: 20Documents")).isEmpty())
  assertTrue(extractTrackingLinks("https://www.fedex.com/track?tracknum=20Documents").isEmpty())
  assertEquals(listOf("ABCD1234567890"), extractTrackingCandidates(mail("m", simpli, "Shipped", "Tracking number: ABCD1234567890")))
 }

 // --- carriers ---

 @Test fun carrierNamesInsideOrdinaryWordsAreIgnored() {
  assertNull(detectCarrier(mail("m", "x@y.com", "Hello", "Join our groups and setups, cups and ups")))
  assertEquals("UPS", detectCarrier(mail("m", "x@y.com", "Shipped", "Shipped via UPS Ground")))
  assertEquals("USPS", detectCarrier(mail("m", "eBay <ebay@ebay.com>", "Update", "Your item groups: Shipped with USPS First Class")))
  assertEquals("USPS", detectCarrier(mail("m", "x@y.com", "Shipped", "Your parcel"), "9400111899223344556677"))
  assertEquals("FedEx", detectCarrier(mail("m", fedex, "Hello", "nothing")))
 }

 // --- classification ---

 @Test fun confirmationsDoNotLookShippedButShippingNoticesDo() {
  assertEquals(EmailKind.CONFIRMATION, classifyEmail(mail("m", simpli, "Order #28855918 confirmed", "We'll email you when your order has shipped.")))
  assertEquals(EmailKind.CONFIRMATION, classifyEmail(mail("m", simpli, "Thanks for your order", "Your order will be shipped within 2 days. Shipped to: 1 Main St")))
  assertEquals(EmailKind.SHIPPED, classifyEmail(mail("m", simpli, "Your order #28855918 has shipped")))
  assertEquals(EmailKind.SHIPPED, classifyEmail(mail("m", "eBay <ebay@ebay.com>", "Order update", "Good news. Your order shipped with USPS. Track package")))
  assertEquals(EmailKind.SHIPPED, classifyEmail(mail("m", fedex, "FedEx Delivery Manager: your package is on its way")))
  assertEquals(EmailKind.SHIPPED, classifyEmail(mail("m", simpli, "Tracking", "Putting the tracking number below into FedEx: 38429466112"), hasTracking = true))
  assertEquals(EmailKind.OUT_FOR_DELIVERY, classifyEmail(mail("m", fedex, "Your package is out for delivery")))
    assertEquals(EmailKind.EXCEPTION, classifyEmail(mail("m", fedex, "Delivery exception")))
  assertEquals(EmailKind.DELIVERED, classifyEmail(mail("m", fedex, "Your package has been delivered")))
  assertEquals(EmailKind.OTHER, classifyEmail(mail("m", simpli, "Our newsletter", "Fresh flavors")))
    assertEquals("In Transit", statusForKind(EmailKind.SHIPPED)); assertEquals("Exception", statusForKind(EmailKind.EXCEPTION))
    assertTrue(isShippingKind(EmailKind.EXCEPTION)); assertNull(statusForKind(EmailKind.CONFIRMATION))
 }

 @Test fun deliveryForecastsAreNotDelivered() {
  assertFalse(classifyEmail(mail("m", fedex, "Your package will be delivered tomorrow")) == EmailKind.DELIVERED)
 }

 @Test fun anOrderConfirmationSubjectMayContainTheOrderNumber() {
  assertTrue(isConfirmationSubject("Order #28855918 confirmed")); assertTrue(isConfirmationSubject("[Shop] Order DSDF66669184 placed"))
  assertFalse(isConfirmationSubject("Your package is on its way"))
 }

 // --- MIME header words, items, links ---

 @Test fun encodedHeaderWordsAreDecoded() {
  assertEquals("Simpli Soda <orders@simplisoda.com>", decodeMimeWords("=?UTF-8?B?U2ltcGxpIFNvZGE=?= <orders@simplisoda.com>"))
  assertEquals("Caf\u00e9 Order", decodeMimeWords("=?UTF-8?Q?Caf=C3=A9_Order?="))
  assertEquals("Your order", decodeMimeWords("=?UTF-8?Q?Your?= =?UTF-8?Q?_order?="))
 }

 @Test fun itemsAndOrderLinksAreExtractedConservatively() {
  val items = extractItems("Item: Root Beer Variety Pack\nQty: 2\nPrice: \$24.00\nItem: ID: 12345\nItems: 3")
  assertEquals(listOf(ParsedItem("Root Beer Variety Pack", 2, 2400L)), items)
  val email = mail("m", simpli, "Order #28855918 confirmed", "View your order [link: https://simplisoda.com/account/orders/abc123] Unsubscribe [link: https://simplisoda.com/unsub]")
  assertEquals("https://simplisoda.com/account/orders/abc123", pickOrderUrl(email, email.body))
 }

 // --- staged association ---

 @Test fun shippingMailWithoutAnOrderNumberIsNotAssociatedByThreadAlone() {
  val orders = listOf(order(1, "Simpli Soda", "28855918"))
  val confirmation = mail("c", simpli, "Order #28855918 confirmed", thread = "T1")
  val shipping = mail("s", simpli, "Your order has shipped", "Putting the tracking number below into FedEx: 38429466112", thread = "T1")
  val result = assign(listOf(confirmation, shipping), orders)
  assertEquals(EmailAssignment.Matched(1, true, false), result["c"])
  assertEquals(setOf(1L), (result["s"] as EmailAssignment.Ambiguous).orderIds)
  assertTrue((result["s"] as EmailAssignment.Ambiguous).reason.contains("cannot associate"))
 }

 @Test fun aThreadMateNamingAnotherOrderIsNotJoined() {
  val orders = listOf(order(1, "Simpli Soda", "28855918"), order(2, "Simpli Soda", "28855999"))
  val result = assign(listOf(mail("c", simpli, "Order #28855918 confirmed", thread = "T"), mail("s", simpli, "Order #28855999 has shipped", thread = "T")), orders)
  assertEquals(EmailAssignment.Matched(2, true, false), result["s"])
 }

 @Test fun retailerShippingMailWithoutOrderNumberUsesRetailerOnlyAsReviewSuggestion() {
  val ship = mail("s", simpli, "Your order has shipped", "Putting the tracking number below into FedEx: 38429466112")
  val one = assign(listOf(ship), listOf(order(1, "Simpli Soda", "28855918"), order(2, "eBay", "11-22222-33333")))["s"] as EmailAssignment.Ambiguous
  assertEquals(setOf(1L), one.orderIds)
  assertTrue(one.reason.contains("retailer/thread evidence alone"))
  val two = assign(listOf(ship), listOf(order(1, "Simpli Soda", "28855918"), order(2, "Simpli Soda", "28855777")))["s"] as EmailAssignment.Ambiguous
  assertEquals(setOf(1L, 2L), two.orderIds)
  // A delivered or archived order is not a plausible target.
  assertEquals(EmailAssignment.Unrelated, assign(listOf(ship), listOf(order(1, "Simpli Soda", "28855918", status = "Delivered")))["s"])
 }

 @Test fun shippingMailNamingAnotherUnsavedOrderIsNeverContextMatched() {
  val ship = mail("s", simpli, "Order #99999999 has shipped", "Tracking number: 38429466112")
  assertEquals(EmailAssignment.Unrelated, assign(listOf(ship), listOf(order(1, "Simpli Soda", "28855918")))["s"])
 }

 @Test fun carrierMailFollowsTheTrackingNumberOfAContextMatchedRetailerEmail() {
  val orders = listOf(order(1, "Simpli Soda", "28855918"))
  val retailer = mail("r", simpli, "Your Simpli Soda order #28855918 has shipped", "Putting the tracking number below into FedEx: 38429466112")
  val carrier = mail("f", fedex, "Your package is on its way", "TRACKING NUMBER 38429466112")
  val result = assign(listOf(retailer, carrier), orders)
  assertEquals(EmailAssignment.Matched(1, true, false), result["r"])
  assertEquals(EmailAssignment.Matched(1, false, true), result["f"])
 }

 @Test fun carrierMailNamingTheShipperIsOnlyAReviewSuggestionWithoutKnownTracking() {
  val carrier = mail("f", fedex, "Your package is on its way", "TRACKING NUMBER 38429466112 FROM SIMPLI SODA")
  assertEquals(setOf(1L), (assign(listOf(carrier), listOf(order(1, "Simpli Soda", "28855918")))["f"] as EmailAssignment.Ambiguous).orderIds)
  assertTrue(assign(listOf(carrier), listOf(order(1, "Simpli Soda", "28855918"), order(2, "Simpli Soda", "28855777")))["f"] is EmailAssignment.Ambiguous)
  assertEquals(EmailAssignment.Unrelated, assign(listOf(carrier), listOf(order(1, "eBay", "11-22222-33333")))["f"])
 }

 @Test fun aConfirmedManualAssociationIsAuthoritative() {
  val orders = listOf(order(1, "Simpli Soda", "28855918"), order(2, "Simpli Soda", "28855777"))
  val ship = mail("s", simpli, "Your order has shipped", "Putting the tracking number below into FedEx: 38429466112")
  assertTrue(assign(listOf(ship), orders)["s"] is EmailAssignment.Ambiguous)
  assertEquals(EmailAssignment.Matched(2, false, false, manual = true), assign(listOf(ship), orders, manual = mapOf("s" to 2L))["s"])
 }

 // --- aliases and duplicates ---

 @Test fun verifiedAliasesResolveToOneRetailerAndUnverifiedNamesDoNot() {
  val book = AliasBook.default
  assertTrue(book.same("DSWATCH", "DS Designer")); assertTrue(book.same("ds-designer", "dswatch"))
  assertFalse(book.same("DSWATCH", "Simpli Soda"))
  val email = mail("m", "DSWATCH <service@dswatch.com>", "Your order DSDF66669184 has shipped")
  assertEquals(EmailAssignment.Matched(1, true, false), assign(listOf(email), listOf(order(1, "DS Designer", "DSDF66669184")))["m"])
  // Two unrelated retailers with the same order number stay separate.
  val orders = listOf(order(1, "Foo Shop", "ZX99887766"), order(2, "Bar Store", "ZX99887766"))
  assertEquals(EmailAssignment.Matched(1, true, false), assign(listOf(mail("f", "Foo Shop <a@foo.com>", "Order ZX99887766 shipped")), orders)["f"])
 }

 @Test fun aliasGroupsMergeEncodeAndDecode() {
  val book = AliasBook.of(listOf(setOf("DS Designer", "DS Watches"), setOf("Foo", "Bar")))
  assertTrue(book.same("DSWATCH", "DS Watches")); assertTrue(book.same("foo", "BAR"))
  assertEquals(listOf(setOf("Foo", "Bar")), AliasBook.decode(AliasBook.encode(listOf(setOf("Foo", "Bar")))))
  assertTrue(AliasBook.decode("lonely").isEmpty())
 }

 @Test fun duplicateCandidatesAreFlaggedButNeverMergedAutomatically() {
  val orders = listOf(order(1, "DSWATCH", "DSDF66669184"), order(2, "DS Designer", "DSDF66669184"), order(3, "Foo", "ZX99887766"), order(4, "Bar", "ZX99887766"), order(5, "Foo", "1001"), order(6, "Bar", "1001"))
  val candidates = findDuplicateOrders(orders)
  assertEquals(setOf(1L to 2L, 3L to 4L), candidates.map { it.first.id to it.second.id }.toSet())
  assertTrue(candidates.first { it.first.id == 1L }.sameRetailer)
  assertFalse(candidates.first { it.first.id == 3L }.sameRetailer)
 }

 @Test fun parserVersionWasBumpedSoStuckMarkersAreReanalyzed() {
  assertTrue(GMAIL_PARSER_VERSION >= 3)
  assertTrue(shouldProcessMessage(2, reanalyze = true)); assertFalse(shouldProcessMessage(2, reanalyze = false))
  assertFalse(shouldProcessMessage(MANUAL_ASSOCIATION_VERSION, reanalyze = true))
 }
}
