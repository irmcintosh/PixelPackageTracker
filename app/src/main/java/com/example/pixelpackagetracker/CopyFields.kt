package com.example.pixelpackagetracker

import java.text.DateFormat
import java.util.Date

/** A UI-only snapshot. Identifiers and URLs are never normalized. */
internal data class CopyField(val id: String, val label: String, val value: String)

private fun MutableList<CopyField>.field(id: String, label: String, value: String?) {
 if(!value.isNullOrBlank()) add(CopyField(id, label, value))
}
private fun copyDate(value: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(value))
private fun copyTime(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

/** One selection is raw; multiple selections retain field/carrier labels in display order. */
internal fun formatCopySelection(fields: List<CopyField>, selected: Set<String>): String {
 val chosen = fields.filter { it.id in selected && it.value.isNotBlank() }
 return if(chosen.size == 1) chosen.single().value else chosen.joinToString("\n") { "${it.label}: ${it.value}" }
}

internal fun shipmentCopyFields(
 shipment: ShipmentRecord, tracking: List<ShipmentTrackingNumberRecord>, order: OrderRecord?, lastChecked: Long? = null,
 formatDate: (Long) -> String = ::copyDate, formatTime: (Long) -> String = ::copyTime
): List<CopyField> = buildList {
 val identifiers = tracking.filter { it.shipmentId == shipment.id }
 field("title", "Shipment", shipment.title)
 field("carrier", "Carrier", identifiers.firstOrNull()?.carrier?.ifBlank { shipment.carrier } ?: shipment.carrier)
 field("status", "Shipment status", shipment.status)
 shipment.shippedAt?.let { field("shipped", "Shipped date", formatDate(it)) }
 shipment.estimatedDeliveryAt?.let { field("estimated", "Estimated delivery date",
  formatDeliveryEstimate(shipment, if(shipment.estimatedDeliverySourceMessageId.isBlank()) formatDate else ::formatDeliveryDate)) }
 shipment.deliveredAt?.let { field("delivered", "Delivered date", formatDate(it)) }
 field("statusUpdated", "Last status update", formatTime(shipment.statusUpdatedAt))
 lastChecked?.let { field("lastChecked", "Last checked date", formatTime(it)) }
 field("statusSource", "Status source", shipment.statusSource)
 order?.takeIf { it.id == shipment.orderId }?.let {
  field("retailer", "Retailer", it.marketplace)
  field("orderNumber", "Order number", it.orderNumber)
  field("orderTitle", "Order", it.name)
  field("orderUrl", "Order URL", it.orderUrl)
 }
 identifiers.forEachIndexed { index, number ->
  val role = when(number.role.lowercase()) { "primary" -> "Primary"; "handoff" -> "Handoff"; else -> "Additional" }
  val duplicateRole = identifiers.count { it.role.equals(number.role, ignoreCase = true) } > 1
  val qualifier = if(duplicateRole || role == "Additional") " ${index + 1}" else ""
  val carrier = if(number.carrier.isNotBlank()) " (${number.carrier})" else ""
  val id = "tracking.$index.${number.id}"
  field("$id.number", "$role tracking number$qualifier$carrier", number.number)
  field("$id.carrier", "$role tracking carrier$qualifier", number.carrier)
  field("$id.url", "$role tracking URL$qualifier$carrier", number.trackingUrl)
 }
}

internal fun orderCopyFields(
 order: OrderRecord, shipments: List<ShipmentRecord>, tracking: List<ShipmentTrackingNumberRecord>, products: List<OrderItemRecord>,
 formatDate: (Long) -> String = ::copyDate, formatTime: (Long) -> String = ::copyTime, orderDateSuffix: String = ""
): List<CopyField> = buildList {
 field("title", "Order", order.name)
 field("retailer", "Retailer", order.marketplace)
 field("orderNumber", "Order number", order.orderNumber)
 field("status", "Order status", order.status)
 order.placedAt?.let { field("placed", "Order date", formatDate(it) + orderDateSuffix) }
 field("statusUpdated", "Last status update", formatTime(order.statusUpdatedAt))
 order.lastSyncedAt?.let { field("lastSynced", "Last synced", formatTime(it)) }
 field("notes", "Notes", order.notes)
 field("orderUrl", "Order URL", order.orderUrl)
 products.filter { it.orderId == order.id }.forEachIndexed { i, item ->
  field("product.$i.${item.id}", "Product ${i + 1}", "${item.name} \u00d7${item.quantity}")
  item.priceMinor?.let { price ->
   field("product.$i.${item.id}.price", "Product ${i + 1} unit price", (item.currency.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()) + java.math.BigDecimal.valueOf(price, 2).toPlainString())
  }
 }
 val associated = shipments.filter { it.orderId == order.id }
 associated.forEachIndexed { index, shipment ->
  shipmentCopyFields(shipment, tracking, null, formatDate = formatDate, formatTime = formatTime).forEach { detail ->
   val prefix = if(associated.size > 1) "Shipment ${index + 1}" else "Shipment"
   val label = when(detail.id) { "title" -> prefix; "status" -> "$prefix status"; else -> "$prefix \u00b7 ${detail.label}" }
   add(detail.copy(id = "shipment.$index.${shipment.id}.${detail.id}", label = label))
  }
 }
}

/** Earlier preserved cards use their real saved fields too, without migration. */
internal fun legacyCopyFields(record: TrackedPackage): List<CopyField> = buildList {
 field("title", "Order", record.name)
 field("retailer", "Retailer", record.marketplace)
 field("orderNumber", "Order number", record.orderId)
 field("orderStatus", "Order status", record.orderStatus)
 field("shipmentStatus", "Shipment status", record.status)
 field("carrier", "Primary tracking carrier", record.carrier)
 field("tracking", "Primary tracking number" + record.carrier.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(), record.trackingNumber)
 field("handoffCarrier", "Handoff tracking carrier", record.lastMileCarrier)
 field("handoff", "Handoff tracking number" + record.lastMileCarrier.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(), record.lastMileTrackingNumber)
 field("orderUrl", "Order URL", record.orderUrl)
 field("trackingUrl", "Tracking URL", record.trackingUrl)
 field("statusUpdated", "Last status update", copyTime(record.statusUpdatedAt))
 record.deliveredAt?.let { field("delivered", "Delivered date", copyDate(it)) }
}
