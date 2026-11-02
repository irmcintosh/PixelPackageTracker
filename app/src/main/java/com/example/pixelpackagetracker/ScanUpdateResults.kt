package com.example.pixelpackagetracker

import java.util.Locale

// Structured, ephemeral outcome of a Gmail update scan. Field changes are built only from
// history rows whose transaction committed; nothing here is persisted.
enum class ScanOutcome { UPDATED, UNCHANGED, REVIEW_REQUIRED, FAILED }

enum class ChangeKind(val label: String) { NEW_VALUE("New value"), CORRECTION("Correction"), BACKFILL("Backfill") }

data class ScanFieldChange(
 val historyId: Long,
 val field: String,
 val before: String,
 val after: String,
 val kind: ChangeKind,
 val source: String?,
 val sourceDate: String,
 val appliedAt: Long,
 val sourceMessageId: String = ""
)

data class ScanEmailDiagnostic(
 val messageId: String,
 val subject: String,
 val retailer: String,
 val orderNumbers: List<String>,
 val trackingNumbers: List<String>,
 val carrier: String?,
 val matchingEvidence: String,
 val result: String,
 val changesApplied: List<String>,
 val candidateSavedOrderIds: List<Long> = emptyList(),
 val rejectedCandidates: List<String> = emptyList(),
 val databaseCommitted: Boolean = false
)

data class OrderScanResult(
 val orderId: Long,
 val retailer: String,
 val orderNumber: String,
 val outcome: ScanOutcome,
 val changes: List<ScanFieldChange> = emptyList(),
 val notes: List<String> = emptyList(),
 val diagnostics: List<ScanEmailDiagnostic> = emptyList()
)

// Items the scan could not settle on its own; the user resolves each one once and the choice is remembered.
sealed interface ReviewItem {
 data class EmailNeedsReview(val email: GmailPreview, val reason: String, val candidateOrderIds: List<Long>, val trackingCandidates: List<String> = emptyList()) : ReviewItem
 data class PossibleDuplicate(val firstId: Long, val secondId: Long, val sameRetailer: Boolean) : ReviewItem
}

data class ScanUpdateSummary(val results: List<OrderScanResult>, val reviews: List<ReviewItem> = emptyList()) {
 val updated: Int get() = results.count { it.outcome == ScanOutcome.UPDATED }
 val unchanged: Int get() = results.count { it.outcome == ScanOutcome.UNCHANGED }
 val review: Int get() = results.count { it.outcome == ScanOutcome.REVIEW_REQUIRED }
 val failed: Int get() = results.count { it.outcome == ScanOutcome.FAILED }
 val fieldChanges: Int get() = results.sumOf { it.changes.size }
 fun headline(): String = "$updated updated · $unchanged unchanged · $review need review" + (if(failed > 0) " · $failed failed" else "") +
  (if(reviews.isNotEmpty()) " · ${reviews.size} item${if(reviews.size == 1) "" else "s"} to review below" else "")
}

/** Outcome of one automatic Inbox scan: records created or changed, items left for review, and messages with nothing useful. */
data class ScanRunSummary(
 val ordersCreated: Int, val ordersUpdated: Int, val shipmentsCreated: Int, val shipmentsUpdated: Int,
 val needsReview: Int, val ignored: Int, val outcomes: List<String> = emptyList(), val update: ScanUpdateSummary? = null
) {
 fun headline(): String = "Orders: $ordersCreated created · $ordersUpdated updated · Shipments: $shipmentsCreated created · $shipmentsUpdated updated · $needsReview need review · $ignored ignored"
}

/** Outcome of a manual Shipments-tab refresh; `updated` counts shipments whose saved data actually changed. */
data class ShipmentUpdateSummary(
 val checked: Int, val updated: Int, val unchanged: Int, val withoutTracking: Int, val needsReview: Int, val lines: List<String>
) {
 fun headline(): String = "$checked checked · $updated updated · $unchanged unchanged" +
  (if(withoutTracking > 0) " · $withoutTracking skipped (no saved tracking number)" else "") + (if(needsReview > 0) " · $needsReview need review" else "")
}

private val absentMarkers = setOf("", "not present", "not available")

fun isAbsentValue(value: String?): Boolean = (value ?: "").trim().lowercase(Locale.US) in absentMarkers

// A change is meaningful only when the normalized value differs; timestamps are never compared here.
fun isMeaningfulChange(before: String?, after: String?): Boolean {
 fun normalized(v: String?) = (v ?: "").trim().replace(Regex("\\s+"), " ")
 if(isAbsentValue(before) && isAbsentValue(after)) return false
 return normalized(before) != normalized(after)
}

fun classifyChange(before: String?, backfillEvidence: Boolean, forwardProgress: Boolean): ChangeKind = when {
 isAbsentValue(before) -> if(backfillEvidence) ChangeKind.BACKFILL else ChangeKind.NEW_VALUE
 forwardProgress -> ChangeKind.NEW_VALUE
 else -> ChangeKind.CORRECTION
}

// Legacy history rows stored the placed date as epoch milliseconds.
fun displayHistoryValue(field: String, raw: String?, formatDate: (Long) -> String): String {
 val value = (raw ?: "").trim()
 if(isAbsentValue(value)) return "Not available"
 val legacyMillis = field.equals("Placed date", ignoreCase = true) && value.length >= 10 && value.all(Char::isDigit)
 return if(legacyMillis) formatDate(value.toLong()) else value
}

// Returns null when neither sender nor subject is known so no source is invented.
fun describeSource(sender: String, subject: String): String? {
 val name = sender.substringBefore('<').trim().trim('"')
 val who = name.ifBlank { sender.trim().trim('<', '>') }
 val subj = subject.trim()
 return when {
  who.isBlank() && subj.isBlank() -> null
  subj.isBlank() -> who
  who.isBlank() -> "\"$subj\""
  else -> "$who — \"$subj\""
 }
}

// Order status moves forward only: a created label or tracking number means Preparing for Shipment; Shipped needs shipment movement.
fun derivedOrderStatus(current: String, shipmentStatuses: List<String>, hasTracking: Boolean = false): String {
 if(current != "Order Placed" && current != "Processing" && current != PREPARING_FOR_SHIPMENT) return current
 return when {
  shipmentStatuses.any { it != "Awaiting Tracking" && it != "Label Created" } -> "Shipped"
  current != PREPARING_FOR_SHIPMENT && (hasTracking || "Label Created" in shipmentStatuses) -> PREPARING_FOR_SHIPMENT
  else -> current
 }
}

fun ScanFieldChange.detailLines(formatTime: (Long) -> String): List<String> = listOf(
 "$field:",
 "Before: ${displayHistoryValue(field, before) { it.toString() }}",
 "After: ${displayHistoryValue(field, after) { it.toString() }}",
 "Type: ${kind.label} · Applied: ${formatTime(appliedAt)}",
 "Source: ${source ?: "Source not identified"}"
)
