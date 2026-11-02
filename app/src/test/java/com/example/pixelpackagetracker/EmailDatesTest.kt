package com.example.pixelpackagetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailDatesTest {
 private fun mail(subject: String, date: String, body: String = "", sender: String = "Shop <orders@shop.com>") = GmailPreview(sender, subject, date, "", "t", body, "m")
 private val jan3 = "Wed, 03 Jan 2024 09:00:00 +0000"

 @Test fun aConfirmationMessageDateIsTheOrderDateEvenWhenTheBodyStatesAnotherDate() {
  val details = extractOrderDetails(mail("Order #A-1234567 confirmed", jan3, "Order date: January 1, 2024"))
  assertEquals(parseMailDate(jan3), details.placedAt)
  assertTrue(details.placedFromConfirmation)
  assertFalse(details.placedApproximate)
 }

 @Test fun aHeaderOnlyConfirmationAlsoDatesTheOrder() {
  val details = extractOrderDetails(mail("Ordered: Pixel case", jan3, sender = "auto-confirm@amazon.com"))
  assertEquals(parseMailDate(jan3), details.placedAt)
  assertTrue(details.placedFromConfirmation)
 }

 @Test fun shippingOrStatusMailWithoutALabeledDateHasNoOrderDate() {
  assertNull(extractOrderDetails(mail("Your order has shipped", jan3, "Shipped with FedEx.")).placedAt)
  assertNull(extractOrderDetails(mail("Order #A-1234567 update", jan3, "Your order is being processed.")).placedAt)
 }

 @Test fun aLabeledDateInOtherOrderMailIsNotTreatedAsTheConfirmationDate() {
  val details = extractOrderDetails(mail("Order #A-1234567 update", jan3, "Order date: January 1, 2024"))
  assertFalse(details.placedFromConfirmation)
  assertTrue(details.placedAt != null && details.placedAt != parseMailDate(jan3))
 }

 @Test fun aConfirmationWithAnUnreadableDateFallsBackToItsLabeledDate() {
  val details = extractOrderDetails(mail("Order #A-1234567 confirmed", "not a date", "Order date: January 1, 2024"))
  assertTrue(details.placedAt != null)
  assertFalse(details.placedFromConfirmation)
 }

 @Test fun carrierAcceptanceWordingIsDispatchEvidenceButALabelOrTrackingNumberIsNot() {
  val accepted = mail("Package update", jan3, "Your package was accepted by USPS.")
  assertTrue(hasDispatchWording(accepted))
  assertTrue(confirmsDispatch(accepted, classifyEmail(accepted)))
  val label = mail("Shipping label created", jan3, "A shipping label has been created. Tracking number: 9400111899223197428490")
  assertFalse(hasDispatchWording(label))
  val trackingOnly = mail("Tracking", jan3, "Putting the tracking number below into FedEx: 38429466112")
  assertFalse(confirmsDispatch(trackingOnly, classifyEmail(trackingOnly, hasTracking = true)))
  assertEquals("Label Created", statusForEmail(trackingOnly, classifyEmail(trackingOnly, hasTracking = true)))
 }
}
