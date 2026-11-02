package com.example.pixelpackagetracker

import org.junit.Assert.*
import org.junit.Test

class CopyDetailsTest {
 private val order = OrderRecord(id = 1, marketplace = "Simpli Soda", orderNumber = "00123-AB#/9", name = "Soda", status = "Shipped", placedAt = 10,
  notes = "Gift for Sam", orderUrl = "https://shop.example/orders/00123?a=1&b=2", statusUpdatedAt = 20, lastSyncedAt = 30)
 private val ship = ShipmentRecord(id = 4, orderId = 1, status = "In Transit", title = "Parcel", carrier = "FedEx", shippedAt = 40, estimatedDeliveryAt = 50, statusUpdatedAt = 60)
 private val primary = ShipmentTrackingNumberRecord(id = 7, shipmentId = 4, number = "0012345678901234", carrier = "FedEx", trackingUrl = "https://www.fedex.com/fedextrack/?trknbr=0012345678901234")
 private val handoff = ShipmentTrackingNumberRecord(id = 8, shipmentId = 4, number = "9400-1118/99", carrier = "USPS", role = "handoff", trackingUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=9400-1118/99")
 private fun fields() = shipmentCopyFields(ship, listOf(primary, handoff), order, 70, { "D$it" }, { "T$it" })
 private fun selectedValue(fields: List<CopyField>, value: String) = formatCopySelection(fields, setOf(fields.single { it.value == value }.id))

 @Test fun singleOrderNumberIsTheExactRawValue() {
  val fields = orderCopyFields(order, emptyList(), emptyList(), emptyList())
  assertEquals("00123-AB#/9", selectedValue(fields, order.orderNumber))
 }
 @Test fun primaryAndHandoffNumbersAreIndependentlySelectableWithoutCarrierInTheRawValue() {
  assertEquals(primary.number, selectedValue(fields(), primary.number))
  assertEquals(handoff.number, selectedValue(fields(), handoff.number))
  val both = fields().filter { it.value == primary.number || it.value == handoff.number }
  assertEquals("Primary tracking number (FedEx): ${primary.number}\nHandoff tracking number (USPS): ${handoff.number}", formatCopySelection(fields(), both.map { it.id }.toSet()))
 }
 @Test fun multipleFieldsUseDisplayOrderAndOnlySelectedValues() {
  val fields = listOf(CopyField("retailer", "Retailer", "namokiMODS"), CopyField("status", "Shipment status", "In Transit"), CopyField("tracking", "Primary tracking number", "0000123"))
  assertEquals("Retailer: namokiMODS\nPrimary tracking number: 0000123", formatCopySelection(fields, linkedSetOf("tracking", "retailer")))
 }
 @Test fun noSelectionAndUnknownIdsProduceNothing() {
  assertEquals("", formatCopySelection(fields(), emptySet()))
  assertEquals("", formatCopySelection(fields(), setOf("not-a-field")))
 }
 @Test fun blankValuesAreNeverCopiedEvenIfSelected() {
  assertEquals("", formatCopySelection(listOf(CopyField("blank", "Notes", "  ")), setOf("blank")))
 }
 @Test fun missingDatesAndOtherUnavailableFieldsAreAbsent() {
  val blank = ShipmentRecord(id = 1, carrier = "", title = "", status = "Label Created", statusUpdatedAt = 5)
  val fields = shipmentCopyFields(blank, emptyList(), null)
  assertFalse(fields.any { it.id in setOf("title", "carrier", "shipped", "estimated", "delivered", "lastChecked", "retailer", "orderNumber") })
  assertFalse(fields.any { it.value == "Not available" || it.value == "Not checked yet" })
  val orderFields = orderCopyFields(order.copy(placedAt = null, lastSyncedAt = null, notes = "", orderUrl = ""), emptyList(), emptyList(), emptyList())
  assertFalse(orderFields.any { it.id in setOf("placed", "lastSynced", "notes", "orderUrl") })
 }
 @Test fun everyAvailableShipmentDateUsesTheActualValue() {
  val fields = shipmentCopyFields(ship.copy(deliveredAt = 80), listOf(primary), order, 70, { "D$it" }, { "T$it" }).associate { it.id to it.value }
  assertEquals("D40", fields["shipped"]); assertEquals("D50", fields["estimated"]); assertEquals("D80", fields["delivered"])
  assertEquals("T60", fields["statusUpdated"]); assertEquals("T70", fields["lastChecked"])
 }
 @Test fun allTrackingUrlsAreAvailableAndLongUrlsAreNeverTruncated() {
  val url = "https://tracking.example/path?token=" + "a0-_/+%3D&part=".repeat(800) + "end=0012345"
  val fields = shipmentCopyFields(ship, listOf(primary.copy(trackingUrl = url), handoff), order)
  assertEquals(url, selectedValue(fields, url))
  assertEquals(handoff.trackingUrl, selectedValue(fields, handoff.trackingUrl))
  val urls = fields.filter { it.label.contains("tracking URL") }
  assertEquals(2, urls.size)
  assertTrue(formatCopySelection(fields, urls.map { it.id }.toSet()).contains(url))
 }
 @Test fun repeatedRolesAndUnsavedIdsStillHaveUniqueSelectableRows() {
  val tracks = listOf(handoff.copy(id = 0), handoff.copy(id = 0, number = "ESUS366961581", carrier = "eBay"))
  val fields = shipmentCopyFields(ship, tracks, order)
  assertEquals(fields.size, fields.map { it.id }.toSet().size)
  val numbers = fields.filter { it.id.endsWith(".number") }
  assertEquals(2, numbers.map { it.label }.toSet().size)
  assertEquals("ESUS366961581", selectedValue(fields, "ESUS366961581"))
 }
 @Test fun orderDialogIncludesAssociatedShipmentsAndTheirIndependentTrackingFields() {
  val fields = orderCopyFields(order, listOf(ship), listOf(primary, handoff), listOf(OrderItemRecord(orderId = 1, name = "Root Beer", quantity = 2)), { "D$it" }, { "T$it" }, " (approximate)")
  assertEquals("D10 (approximate)", fields.single { it.id == "placed" }.value)
  assertEquals("T20", fields.single { it.id == "statusUpdated" }.value)
  assertEquals("T30", fields.single { it.id == "lastSynced" }.value)
  assertTrue(fields.any { it.label.endsWith("Shipped date") && it.value == "D40" })
  assertEquals(handoff.number, selectedValue(fields, handoff.number))
  assertTrue(fields.any { it.value == "Root Beer \u00d72" })
 }
 @Test fun multipleShipmentsHaveUnambiguousLabelsAndIds() {
  val second = ship.copy(id = 5, title = "Second")
  val fields = orderCopyFields(order, listOf(ship, second), listOf(primary, handoff.copy(shipmentId = 5)), emptyList())
  assertTrue(fields.any { it.label.startsWith("Shipment 1") && it.value == primary.number })
  assertTrue(fields.any { it.label.startsWith("Shipment 2") && it.value == handoff.number })
  assertEquals(fields.size, fields.map { it.id }.toSet().size)
 }
 @Test fun unrelatedShipmentsIdentifiersItemsAndOrderAreExcluded() {
  val fields = orderCopyFields(order, listOf(ship.copy(orderId = 999)), listOf(primary), listOf(OrderItemRecord(orderId = 999, name = "Unrelated")))
  assertFalse(fields.any { it.value == primary.number || it.value.contains("Unrelated") })
  val standalone = shipmentCopyFields(ship.copy(orderId = null), listOf(primary.copy(shipmentId = 999)), order)
  assertFalse(standalone.any { it.id == "orderNumber" || it.value == primary.number })
 }
 @Test fun archivedRecordsHaveExactlyTheSameCopyableValues() {
  assertEquals(fields(), shipmentCopyFields(ship.copy(archived = true), listOf(primary, handoff), order, 70, { "D$it" }, { "T$it" }))
  assertEquals(orderCopyFields(order, listOf(ship), listOf(primary), emptyList()), orderCopyFields(order.copy(archived = true), listOf(ship), listOf(primary), emptyList()))
 }
 @Test fun generatingAndFormattingFieldsNeverMutatesSavedModelsOrInputLists() {
  val ships = listOf(ship); val tracking = listOf(primary, handoff); val products = listOf(OrderItemRecord(orderId = 1, name = "Item"))
  val before = listOf(order, ships.toList(), tracking.toList(), products.toList())
  val fields = orderCopyFields(order, ships, tracking, products)
  formatCopySelection(fields, fields.map { it.id }.toSet())
  assertEquals(before, listOf(order, ships, tracking, products))
 }
 @Test fun whitespaceAndUnicodeInIdentifiersAndUrlsArePreserved() {
  val value = " 00-AB#/9\u00e9  "
  assertEquals(value, formatCopySelection(listOf(CopyField("id", "Order number", value)), setOf("id")))
 }
 @Test fun legacyPreservedCardsExposeBothOriginalTrackingNumbers() {
  val fields = legacyCopyFields(TrackedPackage(name = "Old parcel", carrier = "FedEx", trackingNumber = "000123", lastMileCarrier = "USPS", lastMileTrackingNumber = "ESUS366961581", orderId = "00123-AB"))
  assertEquals("000123", selectedValue(fields, "000123"))
  assertEquals("ESUS366961581", selectedValue(fields, "ESUS366961581"))
  assertEquals("00123-AB", selectedValue(fields, "00123-AB"))
 }
 @Test fun orderAndAssociatedShipmentStatusDatesAreClearlyDistinguishedAndKnownPricesAreAvailable() {
  val fields = orderCopyFields(order, listOf(ship), emptyList(), listOf(OrderItemRecord(orderId = 1, name = "Item", priceMinor = 1234, currency = "GBP")))
  val updates = fields.filter { it.label.endsWith("Last status update") }
  assertEquals(2, updates.map { it.label }.toSet().size)
  assertTrue(fields.any { it.label == "Product 1 unit price" && it.value == "GBP 12.34" })
 }
}
