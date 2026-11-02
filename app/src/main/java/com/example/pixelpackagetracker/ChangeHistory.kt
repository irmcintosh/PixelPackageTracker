package com.example.pixelpackagetracker

// Provenance is stored in the existing OrderChangeHistory.sourceMessageId column: a Gmail message ID,
// or one of these markers for changes that did not come from a single email.
internal const val SOURCE_MANUAL = "manual"
internal const val SOURCE_DERIVED = "derived"
internal const val SOURCE_IMPORT = "inbox-import"

// A purchase date inferred from an email timestamp is stored normally; this suffix on its history row marks it as approximate.
internal const val APPROXIMATE_DATE_SUFFIX = " (approximate)"

fun describeHistorySource(sourceMessageId: String, sourceDate: String): String = when {
 sourceMessageId == SOURCE_MANUAL -> "Manual edit"
 sourceMessageId == SOURCE_DERIVED -> "Derived from shipment status"
 sourceMessageId == SOURCE_IMPORT -> "Inbox import"
 sourceMessageId.isBlank() && sourceDate.isBlank() -> "Source not recorded"
 sourceDate.isBlank() -> "Gmail message"
 else -> "Gmail message dated $sourceDate"
}

// A deliberately chosen order status is never advanced by Gmail; derived changes are not deliberate choices.
internal fun mayDeriveOrderStatus(latestStatusChange: OrderChangeHistory?): Boolean = latestStatusChange?.sourceMessageId != SOURCE_MANUAL

internal fun shipmentFieldLabel(base: String, position: Int, total: Int): String = if(total > 1) "$base (shipment ${position + 1})" else base

/** Persists a history row only for a meaningful value change; returns the committed row or null. */
internal suspend fun OrderDao.recordChange(orderId: Long, field: String, before: String, after: String, source: String = "", sourceDate: String = "", appliedAt: Long = System.currentTimeMillis()): OrderChangeHistory? {
 if(!isMeaningfulChange(before, after)) return null
 val row = OrderChangeHistory(orderId = orderId, field = field, oldValue = before, newValue = after, sourceMessageId = source, sourceDate = sourceDate, appliedAt = appliedAt)
 return row.copy(id = logChange(row))
}
