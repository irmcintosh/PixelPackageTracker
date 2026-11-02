package com.example.pixelpackagetracker

internal data class PurchaseProgress(
 val stage: Int,
 val total: Int,
 val label: String,
 val description: String,
 val attention: Boolean = false,
 val manualNote: String? = null
) {
 val summary: String get() = "$stage of $total · $label"
}

private val orderStageNames = listOf("Placed", "Processing", "Preparing for Shipment", "Shipped")
private val knownShipmentStatuses = listOf("Awaiting Tracking", "Label Created", "Shipped", "In Transit", "Customs", "Out for Delivery", "Exception", "Delivered")

internal fun canonicalShipmentStatus(status: String): String =
 knownShipmentStatuses.firstOrNull { it.equals(status.trim(), ignoreCase = true) } ?: status.trim()

// Unknown statuses are not assumed to have shipped.
internal fun shipmentStage(status: String): Int = when(canonicalShipmentStatus(status)) {
 "Label Created", "Shipped" -> 3
 "In Transit", "Customs", "Out for Delivery", "Exception" -> 4
 "Delivered" -> 5
 else -> 2
}

/** Orders stop at Shipped; delivery belongs to shipments. Older delivery-like order statuses are shown as Shipped. */
private fun orderStatusStage(status: String): Int = when(status.trim().lowercase(java.util.Locale.US)) {
 "shipped", "partially shipped", "in transit", "out for delivery", "delivered" -> 4
 PREPARING_FOR_SHIPMENT.lowercase(java.util.Locale.US) -> 3
 "processing", "confirmed", "preparing" -> 2
 else -> 1
}

internal fun shipmentStatusDescription(status: String): String = when(val s = canonicalShipmentStatus(status)) {
 "Awaiting Tracking" -> "Awaiting tracking information"
 "Label Created" -> "Label created · Awaiting carrier pickup"
 "Shipped" -> "Shipped · Awaiting confirmed carrier movement"
 "Customs" -> "Customs · Held for clearance"
 "Exception" -> "Exception · Check carrier tracking"
 else -> s
}

private fun orderStageDescription(stage: Int): String = when(stage) {
 1 -> "Order placed · Waiting for preparation"
 2 -> "Order processing · Waiting for a shipping label"
 3 -> "Label or tracking number created · Waiting for the carrier to receive the package"
 else -> "Shipped · Follow delivery in Shipments"
}

/** Order card progress comes only from the order status; shipment delivery progress lives in the Shipments tab. */
internal fun purchaseProgress(orderStatus: String, manualOrderStatus: Boolean = false): PurchaseProgress {
 if(orderStatus.trim().equals("Cancelled", ignoreCase = true))
  return PurchaseProgress(0, 4, "Cancelled", "Order cancelled", manualNote = if(manualOrderStatus) "Order marked Cancelled manually" else null)
 val stage = orderStatusStage(orderStatus)
 val label = if(orderStatus.trim().equals("Delivered", ignoreCase = true)) "Delivered" else orderStageNames[stage - 1]
 return PurchaseProgress(stage, 4, label, orderStageDescription(stage))
}

/** Standalone shipments have no order lifecycle: Shipped → In Transit / Out for Delivery → Delivered. */
internal fun standaloneShipmentProgress(status: String): PurchaseProgress {
 val s = canonicalShipmentStatus(status)
 val stage = when(shipmentStage(s)) { 3 -> 1; 4 -> 2; 5 -> 3; else -> 0 }
 return PurchaseProgress(stage, 3, s, shipmentStatusDescription(s), attention = s == "Exception")
}

/** A shipment carries usable information once it has a tracking number or a known carrier. */
internal fun hasShipmentInformation(shipment: ShipmentRecord, numbers: List<ShipmentTrackingNumberRecord>): Boolean =
 shipment.carrier.isNotBlank() || numbers.any { it.shipmentId == shipment.id && it.number.isNotBlank() }

/** A Shipped order with no usable shipment stays visible and asks for shipment details instead of archiving. */
internal fun needsShipmentInformation(order: OrderRecord, shipments: List<ShipmentRecord>, numbers: List<ShipmentTrackingNumberRecord>): Boolean =
 order.status == "Shipped" && !order.archived && shipments.none { hasShipmentInformation(it, numbers) }

/** Order statuses a user may choose; delivery belongs to shipments. */
internal const val PREPARING_FOR_SHIPMENT = "Preparing for Shipment"
internal val selectableOrderStatuses = listOf("Order Placed", "Processing", PREPARING_FOR_SHIPMENT, "Shipped", "Cancelled")

private fun gmailStatusRank(status: String): Int = when(status) {
 "Label Created", "Shipped" -> 1
 "In Transit", "Customs", "Exception" -> 2
 "Out for Delivery" -> 3
 else -> 0
}

/**
 * Whether a Gmail-derived shipment status may replace the persisted one. Mail can be processed out of order, so moves that
 * enter or leave Exception, or leave Customs for In Transit, require evidence newer than the current status.
 */
internal fun gmailShipmentStatusApplies(current: String, incoming: String, incomingAt: Long?, currentSince: Long): Boolean {
 val from = canonicalShipmentStatus(current)
 val to = canonicalShipmentStatus(incoming)
 val newer = incomingAt != null && incomingAt > currentSince
 return when {
  from == to || from == "Delivered" -> false
  to == "Delivered" -> true
  from == "Exception" -> newer && gmailStatusRank(to) >= 2
  to == "Exception" -> gmailStatusRank(from) <= 1 || newer
  from == "Customs" && to == "In Transit" -> newer
  else -> gmailStatusRank(to) > gmailStatusRank(from)
 }
}
