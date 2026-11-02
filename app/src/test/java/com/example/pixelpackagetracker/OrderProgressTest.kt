package com.example.pixelpackagetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderProgressTest {
 private fun progress(order: String, manual: Boolean = false) = purchaseProgress(order, manual)

 @Test fun theOrdersTabHasFourStagesPlacedProcessingPreparingShipped() {
  assertEquals("1 of 4 · Placed", progress("Order Placed").summary)
  assertEquals("2 of 4 · Processing", progress("Processing").summary)
  assertEquals("3 of 4 · Preparing for Shipment", progress("Preparing for Shipment").summary)
  assertEquals("4 of 4 · Shipped", progress("Shipped").summary)
  listOf("Order Placed", "Processing", "Preparing for Shipment", "Shipped").forEach { assertEquals(4, progress(it).total) }
 }

 @Test fun cancelledIsSeparateFromTheFourStages() {
  val cancelled = progress("Cancelled")
  assertEquals("0 of 4 · Cancelled", cancelled.summary)
  assertEquals("Order cancelled", cancelled.description)
  assertNull(cancelled.manualNote)
  assertEquals("Order marked Cancelled manually", progress("Cancelled", manual = true).manualNote)
 }

 @Test fun olderDeliveryLikeOrderStatusesAreShownAsShippedNotAsAFifthStage() {
  listOf("Partially Shipped", "In Transit", "Out for Delivery").forEach { assertEquals(it, "4 of 4 · Shipped", progress(it).summary) }
  assertEquals("4 of 4 · Delivered", progress("Delivered").summary)
  assertEquals("2 of 4 · Processing", progress("Preparing").summary)
 }

 @Test fun preparingDescribesALabelOrTrackingNumberNotACarrierPickup() {
  assertTrue(progress("Preparing for Shipment").description.startsWith("Label or tracking number created"))
 }

 @Test fun userSelectableStatusesIncludePreparingForShipment() {
  assertEquals(listOf("Order Placed", "Processing", "Preparing for Shipment", "Shipped", "Cancelled"), selectableOrderStatuses)
 }

 @Test fun standaloneShipmentsUseThreeShipmentOnlyStages() {
  assertEquals("0 of 3 · Awaiting Tracking", standaloneShipmentProgress("Awaiting Tracking").summary)
  assertEquals("1 of 3 · Label Created", standaloneShipmentProgress("Label Created").summary)
  assertEquals("2 of 3 · In Transit", standaloneShipmentProgress("In Transit").summary)
  assertEquals("2 of 3 · Out for Delivery", standaloneShipmentProgress("Out for Delivery").summary)
  assertEquals("2 of 3 · Customs", standaloneShipmentProgress("Customs").summary)
  assertTrue(standaloneShipmentProgress("Exception").attention)
  assertEquals("3 of 3 · Delivered", standaloneShipmentProgress("Delivered").summary)
 }

 @Test fun shipmentStatusTextIsStillCanonical() {
  listOf("In Transit", "Out for Delivery", "Customs", "Exception", "Delivered").forEach { status ->
   assertEquals(status, canonicalShipmentStatus(status))
   assertEquals(status, standaloneShipmentProgress(status).label)
  }
  assertEquals("Out for Delivery", canonicalShipmentStatus("out for delivery"))
 }
 // --- Gmail transition rules ---
 private val t0 = 1_000L
 private val older = 500L
 private val newer = 2_000L

 @Test fun forwardProgressAppliesAndStaleMailNeverMovesBackwards() {
  assertTrue(gmailShipmentStatusApplies("In Transit", "Out for Delivery", older, t0))
  assertFalse(gmailShipmentStatusApplies("Out for Delivery", "In Transit", newer, t0))
  assertFalse(gmailShipmentStatusApplies("In Transit", "Label Created", newer, t0))
  assertFalse(gmailShipmentStatusApplies("Out for Delivery", "Out for Delivery", newer, t0))
  assertTrue(gmailShipmentStatusApplies("Out for Delivery", "Delivered", older, t0))
 }

 @Test fun deliveredIsNeverReopenedByGmail() {
  listOf("In Transit", "Out for Delivery", "Exception", "Label Created").forEach {
   assertFalse(gmailShipmentStatusApplies("Delivered", it, newer, t0))
  }
 }

 @Test fun exceptionRequiresNewerEvidenceUnlessTheShipmentHadNotMoved() {
  assertTrue(gmailShipmentStatusApplies("Out for Delivery", "Exception", newer, t0))
  assertFalse(gmailShipmentStatusApplies("Out for Delivery", "Exception", older, t0))
  assertFalse(gmailShipmentStatusApplies("In Transit", "Exception", null, t0))
  assertTrue(gmailShipmentStatusApplies("Awaiting Tracking", "Exception", older, t0))
  assertTrue(gmailShipmentStatusApplies("Label Created", "Exception", older, t0))
 }

 @Test fun exceptionIsResolvedOnlyByNewerMovementOrDelivery() {
  assertTrue(gmailShipmentStatusApplies("Exception", "In Transit", newer, t0))
  assertTrue(gmailShipmentStatusApplies("Exception", "Out for Delivery", newer, t0))
  assertFalse(gmailShipmentStatusApplies("Exception", "Out for Delivery", older, t0))
  assertFalse(gmailShipmentStatusApplies("Exception", "Label Created", newer, t0))
  assertTrue(gmailShipmentStatusApplies("Exception", "Delivered", older, t0))
 }

 @Test fun customsIsNotRegressedButCanBeClearedByNewerMail() {
  assertFalse(gmailShipmentStatusApplies("Customs", "Label Created", newer, t0))
  assertFalse(gmailShipmentStatusApplies("Customs", "In Transit", older, t0))
  assertTrue(gmailShipmentStatusApplies("Customs", "In Transit", newer, t0))
  assertTrue(gmailShipmentStatusApplies("Customs", "Out for Delivery", older, t0))
  assertFalse(gmailShipmentStatusApplies("Customs", "Exception", older, t0))
  assertTrue(gmailShipmentStatusApplies("Customs", "Exception", newer, t0))
 }
}
