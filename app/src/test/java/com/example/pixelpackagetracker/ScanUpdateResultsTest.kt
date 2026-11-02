package com.example.pixelpackagetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanUpdateResultsTest {
 private fun change(field: String = "Tracking number", before: String = "Not available", after: String = "38429466112", source: String? = "Simpli Soda — \"Shipped\"") =
  ScanFieldChange(1, field, before, after, ChangeKind.NEW_VALUE, source, "", 1_700_000_000_000)

 @Test fun genuineChangeIsMeaningful() {
  assertTrue(isMeaningfulChange("Not available", "38429466112"))
  assertTrue(isMeaningfulChange("Awaiting Tracking", "In Transit"))
 }

 @Test fun identicalAbsentOrWhitespaceOnlyValuesAreNotMeaningful() {
  assertFalse(isMeaningfulChange("In Transit", "In Transit"))
  assertFalse(isMeaningfulChange("In  Transit ", "In Transit"))
  assertFalse(isMeaningfulChange("", "Not available"))
  assertFalse(isMeaningfulChange("Not present", null))
 }

 @Test fun classification() {
  assertEquals(ChangeKind.NEW_VALUE, classifyChange("Not available", backfillEvidence = false, forwardProgress = false))
  assertEquals(ChangeKind.BACKFILL, classifyChange("", backfillEvidence = true, forwardProgress = false))
  assertEquals(ChangeKind.NEW_VALUE, classifyChange("Awaiting Tracking", backfillEvidence = false, forwardProgress = true))
  assertEquals(ChangeKind.CORRECTION, classifyChange("Discovered from Gmail Inbox", backfillEvidence = false, forwardProgress = false))
 }

 @Test fun sourceIsNeverInvented() {
  assertNull(describeSource("", "  "))
  assertEquals("Simpli Soda — \"Your order shipped\"", describeSource("Simpli Soda <orders@simplisoda.com>", "Your order shipped"))
  assertEquals("orders@simplisoda.com", describeSource("<orders@simplisoda.com>", ""))
 }

 @Test fun detailLinesShowBeforeAfterSourceAndKind() {
  val lines = change().detailLines { "TIME" }
  assertEquals(listOf("Tracking number:", "Before: Not available", "After: 38429466112", "Type: New value · Applied: TIME", "Source: Simpli Soda — \"Shipped\""), lines)
  assertEquals("Source: Source not identified", change(source = null).detailLines { "T" }.last())
 }

 @Test fun legacyPlacedDateMillisAreFormatted() {
  assertEquals("DATE", displayHistoryValue("Placed date", "1700000000000") { "DATE" })
  assertEquals("1700000000000", displayHistoryValue("Notes", "1700000000000") { "DATE" })
  assertEquals("Not available", displayHistoryValue("Placed date", "") { "DATE" })
 }

 @Test fun orderStatusOnlyReachesShippedAndNeverBecomesADeliveryStatus() {
  assertEquals("Shipped", derivedOrderStatus("Order Placed", listOf("In Transit")))
  assertEquals("Shipped", derivedOrderStatus("Processing", listOf("Delivered", "Delivered")))
  assertEquals("Shipped", derivedOrderStatus("Shipped", listOf("Delivered", "In Transit")))
  assertEquals("Delivered", derivedOrderStatus("Delivered", listOf("In Transit")))
  assertEquals("Partially Shipped", derivedOrderStatus("Partially Shipped", listOf("Delivered")))
  assertEquals("Cancelled", derivedOrderStatus("Cancelled", listOf("Delivered")))
  assertEquals("Order Placed", derivedOrderStatus("Order Placed", listOf("Awaiting Tracking")))
  assertEquals("Preparing for Shipment", derivedOrderStatus("Order Placed", listOf("Label Created")))
  assertEquals("Custom", derivedOrderStatus("Custom", listOf("Delivered")))
 }

 @Test fun aLabelOrTrackingNumberMeansPreparingForShipmentNeverShipped() {
  assertEquals("Preparing for Shipment", derivedOrderStatus("Processing", listOf("Awaiting Tracking"), hasTracking = true))
  assertEquals("Preparing for Shipment", derivedOrderStatus("Order Placed", listOf("Awaiting Tracking"), hasTracking = true))
  assertEquals("Preparing for Shipment", derivedOrderStatus("Preparing for Shipment", listOf("Label Created"), hasTracking = true))
  assertEquals("Order Placed", derivedOrderStatus("Order Placed", emptyList(), hasTracking = false))
  assertEquals("Shipped", derivedOrderStatus("Preparing for Shipment", listOf("Label Created", "In Transit"), hasTracking = true))
  assertEquals("Cancelled", derivedOrderStatus("Cancelled", listOf("Label Created"), hasTracking = true))
  assertEquals("Shipped", derivedOrderStatus("Shipped", listOf("Label Created"), hasTracking = true))
 }

 @Test fun summaryCountsEachOutcomeOnceAndOnlyCountsCommittedChanges() {
  val summary = ScanUpdateSummary(listOf(
   OrderScanResult(1, "A", "1", ScanOutcome.UPDATED, listOf(change(), change("Shipment status", "Awaiting Tracking", "In Transit"))),
   OrderScanResult(2, "B", "2", ScanOutcome.UNCHANGED),
   OrderScanResult(3, "C", "3", ScanOutcome.REVIEW_REQUIRED, notes = listOf("needs review")),
   OrderScanResult(4, "D", "4", ScanOutcome.FAILED, notes = listOf("rolled back"))
  ))
  assertEquals(1, summary.updated); assertEquals(1, summary.unchanged); assertEquals(1, summary.review); assertEquals(1, summary.failed)
  assertEquals(2, summary.fieldChanges)
  assertEquals("1 updated · 1 unchanged · 1 need review · 1 failed", summary.headline())
 }
}
