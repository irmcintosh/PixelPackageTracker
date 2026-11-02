package com.example.pixelpackagetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderWorkflowTest {
 private fun mail(subject: String, body: String = "") = GmailPreview("Shop <orders@shop.com>", subject, "Mon, 5 Oct 2020 10:00:00 +0000", "", "t", body, "m")
 private fun order(status: String, archived: Boolean = false) = OrderRecord(id = 1, marketplace = "Shop", orderNumber = "A-1234567", name = "Item", status = status, archived = archived)

 @Test fun processingAndCancelledNoticesAreOrderStatuses() {
  assertEquals("Processing", orderStatusNotice(mail("Order update", "Your order is being processed."), EmailKind.OTHER))
  assertEquals("Cancelled", orderStatusNotice(mail("Your order has been cancelled"), EmailKind.OTHER))
 }

 @Test fun conditionalShippingAndConfirmationWordingIsNotAnOrderStatus() {
  assertNull(orderStatusNotice(mail("Order update", "We'll notify you when your order is processed."), EmailKind.OTHER))
  assertNull(orderStatusNotice(mail("Your order has shipped", "Your order is being processed."), EmailKind.SHIPPED))
  assertNull(orderStatusNotice(mail("Order confirmed", "We're processing your order."), EmailKind.CONFIRMATION))
 }

 @Test fun deliveryAndPartialShippingAreNotSelectableOrderStatuses() {
  assertEquals(listOf("Order Placed", "Processing", "Preparing for Shipment", "Shipped", "Cancelled"), selectableOrderStatuses)
 }

 @Test fun aTrackingNumberOnlyNoticeIsALabelNotADispatch() {
  val trackingOnly = GmailPreview("Shop <orders@shop.com>", "Tracking", "Mon, 5 Oct 2020 10:00:00 +0000", "", "t", "Putting the tracking number below into FedEx: 38429466112", "m")
  assertEquals(EmailKind.SHIPPED, classifyEmail(trackingOnly, hasTracking = true))
  assertEquals("Label Created", statusForEmail(trackingOnly, EmailKind.SHIPPED))
  assertFalse(confirmsDispatch(trackingOnly, EmailKind.SHIPPED))
  val dispatched = mail("Your order has shipped", "Shipped with FedEx.")
  assertEquals("In Transit", statusForEmail(dispatched, EmailKind.SHIPPED))
  assertTrue(confirmsDispatch(dispatched, EmailKind.SHIPPED))
  assertEquals("Out for Delivery", statusForEmail(dispatched, EmailKind.OUT_FOR_DELIVERY))
 }

 @Test fun aShippedOrderWithoutUsableShipmentInformationAsksForIt() {
  val shipped = order("Shipped")
  assertTrue(needsShipmentInformation(shipped, emptyList(), emptyList()))
  val blank = ShipmentRecord(id = 5, orderId = 1)
  assertTrue(needsShipmentInformation(shipped, listOf(blank), emptyList()))
  assertFalse(needsShipmentInformation(shipped, listOf(blank.copy(carrier = "USPS")), emptyList()))
  assertFalse(needsShipmentInformation(shipped, listOf(blank), listOf(ShipmentTrackingNumberRecord(shipmentId = 5, number = "9400111899223344556677", carrier = "USPS"))))
 }

 @Test fun onlyAnActiveShippedOrderAsksForShipmentInformation() {
  assertFalse(needsShipmentInformation(order("Order Placed"), emptyList(), emptyList()))
  assertFalse(needsShipmentInformation(order("Cancelled"), emptyList(), emptyList()))
  assertFalse(needsShipmentInformation(order("Shipped", archived = true), emptyList(), emptyList()))
 }
}
