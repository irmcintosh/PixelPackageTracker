package com.example.pixelpackagetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailDiscoveryTest {
 private val ebay = "eBay <ebay@ebay.com>"
 private fun mail(id: String, sender: String, subject: String, body: String = "") =
  GmailPreview(sender, subject, "Wed, 21 Oct 2026 10:05:57 -0700", "", "t$id", body, id)
 private fun eligibility(email: GmailPreview) = orderEligibility(buildEvidence(email))

 private val outForDelivery = mail("ofd", ebay, "OUT FOR DELIVERY: Google Pixel C 64GB...",
  "Order #18-15293-46910\nEstimated delivery: Thu, Oct 22 - Wed, Oct 28\nYour item is out for delivery with USPS.")
 private val orderUpdate = mail("upd", ebay, "Order update: Google Pixel C 64GB...", "Order #18-15293-46910\nEstimated delivery: Thu, Oct 22 - Wed, Oct 28")

 @Test fun anAuthenticRetailerOutForDeliveryNoticeEstablishesTheOrderWithoutAConfirmation() {
  val result = eligibility(outForDelivery)
  assertTrue(result.reason, result.eligible)
  assertEquals("Retailer shipping notice", result.basis)
  assertTrue(result.signals.containsAll(listOf("order status", "item", "delivery information")))
 }

 @Test fun aRetailerOrderUpdateWithoutAShippingStatusAlsoEstablishesTheOrder() {
  assertTrue(eligibility(orderUpdate).eligible)
 }

 @Test fun theSameDecisionDrivesScanGroupingAndTheGroupRecordsItsBasis() {
  val group = groupedScanResults(listOf(outForDelivery, orderUpdate), emptyList(), emptyList(), emptyList()).single()
  assertTrue(group.establishesOrder)
  assertEquals("Retailer shipping notice", group.basis)
  assertEquals("18-15293-46910", group.orderNumber)
  assertEquals("eBay", group.title)
  assertEquals(2, group.emails.size)
  assertFalse(group.key.startsWith("review:"))
 }

 @Test fun anUnrelatedEmailThatMentionsAnOrderNumberDoesNotEstablishAnOrder() {
  val unrelated = listOf(
   mail("cs", ebay, "Re: your question about order 18-15293-46910", "We are looking into your request."),
   mail("one", ebay, "Your order 18-15293-46910", "Thanks for shopping with us."),
   mail("promo", ebay, "Save 20% on your next purchase after order 18-15293-46910", "Order #18-15293-46910 Estimated delivery soon. Sale ends Friday."),
   mail("refund", ebay, "Refund issued for order 18-15293-46910", "Order #18-15293-46910 Estimated delivery cancelled."),
   mail("forward", "Someone <someone@gmail.com>", "OUT FOR DELIVERY: Google Pixel C 64GB", "Order #18-15293-46910\nEstimated delivery: Thu, Oct 22"),
   mail("paypal", "service@paypal.com <service@paypal.com>", "OUT FOR DELIVERY: Google Pixel C 64GB", "Order #18-15293-46910\nEstimated delivery: Thu, Oct 22"),
   mail("carrier", "USPS <updates@usps.com>", "OUT FOR DELIVERY: Google Pixel C 64GB", "Order #18-15293-46910\nEstimated delivery: Thu, Oct 22"))
  unrelated.forEach { assertFalse(it.subject, eligibility(it).eligible) }
  unrelated.forEach { email ->
   val groups = groupedScanResults(listOf(email), emptyList(), emptyList(), emptyList())
   assertTrue(email.subject, groups.none { it.establishesOrder })
  }
 }

 @Test fun twoOrderNumbersOrNoOrderNumberNeverEstablishAnOrder() {
  assertFalse(eligibility(mail("two", ebay, "OUT FOR DELIVERY: Google Pixel C 64GB", "Order #18-15293-46910 and order #18-15293-99999\nEstimated delivery: Thu")).eligible)
  assertFalse(eligibility(mail("none", ebay, "OUT FOR DELIVERY: Google Pixel C 64GB", "Estimated delivery: Thu, Oct 22")).eligible)
 }

 @Test fun anOrderConfirmationStillEstablishesAnOrderOnItsOwn() {
  val result = eligibility(mail("c", ebay, "Order #18-15293-46910 confirmed"))
  assertTrue(result.eligible)
  assertEquals("Order confirmation", result.basis)
 }

 @Test fun aVerifiedCarrierEmailWithTrackingBecomesAStandaloneShipmentNotAnOrder() {
  val fedex = mail("fx", "FedEx <TrackingUpdates@fedex.com>", "Your package is on its way", "Tracking number: 38429466112")
  val evidence = standaloneShipmentEvidence(buildEvidence(fedex))
  assertNotNull(evidence)
  assertEquals("FedEx", evidence!!.carrier)
  assertEquals("In Transit", evidence.status)
  val group = groupedScanResults(listOf(fedex), emptyList(), emptyList(), emptyList()).single()
  assertEquals("shipment:new:38429466112", group.key)
  assertFalse(group.establishesOrder)
  assertNull(group.orderNumber)
 }

 @Test fun aSavedStandaloneShipmentIsUpdatedInPlaceNotDuplicated() {
  val fedex = mail("fx", "FedEx <TrackingUpdates@fedex.com>", "Your package is out for delivery", "Tracking number: 38429466112")
  val saved = ShipmentRecord(id = 10, orderId = null, status = "In Transit")
  val number = ShipmentTrackingNumberRecord(id = 1, shipmentId = 10, number = "38429466112", carrier = "FedEx")
  val group = groupedScanResults(listOf(fedex), listOf(number), listOf(saved), emptyList()).single()
  assertEquals("shipment:saved:10", group.key)
 }

 @Test fun standaloneEvidenceNeedsTrackingCarrierAndAPositiveStatus() {
  assertNull(standaloneShipmentEvidence(buildEvidence(mail("a", "FedEx <TrackingUpdates@fedex.com>", "Your package is on its way"))))
  assertNull(standaloneShipmentEvidence(buildEvidence(mail("b", "FedEx <TrackingUpdates@fedex.com>", "Update", "Tracking numbers: 38429466112 and 29384756102"))))
  assertNull(standaloneShipmentEvidence(buildEvidence(mail("c", "Shop <orders@shop.example>", "Your order is on its way", "Tracking number: 38429466112 will be sent when it ships"))))
 }

 @Test fun theTitleIsTakenFromTheEmailAndNeverFromGenericSubjectText() {
  assertEquals("Google Pixel C 64GB", subjectItemName("OUT FOR DELIVERY: Google Pixel C 64GB..."))
  assertEquals("Google Pixel C 64GB", subjectItemName("Order update: Google Pixel C 64GB..."))
  assertEquals("Tops", subjectItemName("Ordered 1 item: Tops"))
  assertNull(subjectItemName("Shipped: 1 item"))
  assertNull(subjectItemName("Your order has shipped"))
  assertEquals("Google Pixel C 64GB", orderTitleFromEmails(listOf(outForDelivery)))
  assertEquals("Google Pixel C 64GB", prefillAssociation(outForDelivery, emptyList()).title)
 }

 @Test fun aTracklessCarrierIsStatedOrUniqueButNeverGuessed() {
  assertEquals("USPS", carrierForTracklessShipment(outForDelivery))
  assertEquals("USPS", carrierForTracklessShipment(mail("s", ebay, "Shipped: Widget", "Order #12-34567-89012\nShipped with USPS")))
  assertNull(carrierForTracklessShipment(mail("n", ebay, "Shipped: Widget", "Order #12-34567-89012")))
  assertNull(carrierForTracklessShipment(mail("m", ebay, "Shipped: Widget", "Order #12-34567-89012\nCompare FedEx and UPS rates")))
 }

 @Test fun inboxOnlyDiscoveryQueryIsUnchanged() {
  assertEquals("messages?maxResults=100&labelIds=INBOX", gmailInboxMessagesListPath(null))
  assertEquals("messages?maxResults=100&labelIds=INBOX&pageToken=p2", gmailInboxMessagesListPath("p2"))
 }
}
