package com.example.pixelpackagetracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import org.json.JSONArray
import android.util.Base64


/** Silent authorization outcome: a user prompt is needed (no retry will help) versus a transient failure (retry may help). */
internal sealed interface GmailTokenResult {
 data class Token(val value: String): GmailTokenResult
 object NeedsAuthorization: GmailTokenResult
 object Failed: GmailTokenResult
}

internal suspend fun requestGmailToken(context: android.content.Context): GmailTokenResult = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
 val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/gmail.readonly"))).build()
 Identity.getAuthorizationClient(context).authorize(request)
  .addOnSuccessListener { result ->
   val token = result.accessToken
   if(continuation.isActive) continuation.resume(if(result.hasResolution() || token.isNullOrBlank()) GmailTokenResult.NeedsAuthorization else GmailTokenResult.Token(token), null)
  }
  .addOnFailureListener { if(continuation.isActive) continuation.resume(GmailTokenResult.Failed, null) }
}

internal suspend fun obtainGmailToken(context: android.content.Context): String? = (requestGmailToken(context) as? GmailTokenResult.Token)?.value

// Shared, in-memory Gmail connection status for Settings and Orders. Tokens are never persisted.
internal object GmailConnectionState {
 var token by mutableStateOf<String?>(null)
 var status by mutableStateOf("Checking Google authorization…")
 var checked by mutableStateOf(false)
 var lastScan by mutableStateOf("Not yet scanned in this app session")
 var disconnectedByUser by mutableStateOf(false)
}

@Composable private fun rememberGmailAuthorization(): Pair<() -> Unit, Boolean> {
 val context = LocalContext.current
 val authClient = remember(context) { Identity.getAuthorizationClient(context) }
 val scope = rememberCoroutineScope()
 var busy by remember { mutableStateOf(false) }
 val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
  busy = false
  if (result.resultCode == android.app.Activity.RESULT_OK) {
   runCatching { authClient.getAuthorizationResultFromIntent(result.data).accessToken }
    .onSuccess { token ->
     GmailConnectionState.token = token
     GmailConnectionState.checked = true
     GmailConnectionState.disconnectedByUser = false
     GmailConnectionState.status = if(token.isNullOrBlank()) "Reconnection required" else "Connected · Gmail read-only"
    }
    .onFailure { GmailConnectionState.status = "Authorization failed: ${it.localizedMessage ?: "Unknown error"}" }
  } else GmailConnectionState.status = "Authorization canceled"
 }
 LaunchedEffect(context) {
  if (!GmailConnectionState.checked && !GmailConnectionState.disconnectedByUser) {
   GmailConnectionState.checked = true
   val token = obtainGmailToken(context)
   GmailConnectionState.token = token
   GmailConnectionState.status = if(token.isNullOrBlank()) "Disconnected or reconnection required" else "Connected · Gmail read-only"
  }
 }
 val connect: () -> Unit = {
  busy = true
  GmailConnectionState.status = "Requesting Google authorization…"
  val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/gmail.readonly"))).build()
  authClient.authorize(request).addOnSuccessListener { result ->
   if (result.hasResolution()) {
    val sender = result.pendingIntent?.intentSender
    if (sender != null) launcher.launch(IntentSenderRequest.Builder(sender).build())
    else { busy = false; GmailConnectionState.status = "Google did not provide an authorization dialog" }
   } else {
    busy = false
    GmailConnectionState.token = result.accessToken
    GmailConnectionState.checked = true
    GmailConnectionState.disconnectedByUser = false
    GmailConnectionState.status = if(result.accessToken.isNullOrBlank()) "Reconnection required" else "Connected · Gmail read-only"
   }
  }.addOnFailureListener {
   busy = false
   GmailConnectionState.status = "Connection failed: ${it.localizedMessage ?: "Unknown error"}"
  }
 }
 return connect to busy
}

data class GmailPreview(val sender: String, val subject: String, val date: String, val snippet: String, val threadId: String = "", val body: String = "", val messageId: String = "")

/** Inbox messages the last scan could not fetch; one failed message never aborts the scan. */
internal var lastScanSkippedMessages: Int = 0

// Read-only Gmail scan: fetch full MIME content for more reliable relationship evidence. Never persists email.
internal suspend fun scanGmailPreviews(token: String, progress: (Int) -> Unit): List<GmailPreview> = withContext(Dispatchers.IO) {
 fun request(path: String): JSONObject {
  val connection = (URL("https://gmail.googleapis.com/gmail/v1/users/me/" + path).openConnection() as HttpURLConnection)
  try {
   connection.requestMethod = "GET"
   connection.connectTimeout = 12000
   connection.readTimeout = 12000
   connection.setRequestProperty("Authorization", "Bearer $token")
   val status = connection.responseCode
   if (status !in 200..299) throw IllegalStateException("Gmail HTTP $status. Reconnect Gmail if authorization expired.")
   return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
  } finally { connection.disconnect() }
 }
 val results = mutableListOf<GmailPreview>()
 lastScanSkippedMessages = 0
 val seen = mutableSetOf<String>()
 var pageToken: String? = null
 do {
  val listing = request(gmailInboxMessagesListPath(pageToken))
  val messages = listing.optJSONArray("messages") ?: JSONArray()
  for (i in 0 until messages.length()) {
   val id = messages.getJSONObject(i).optString("id")
   if (id.isBlank() || !seen.add(id)) continue
   val parsed = runCatching {
    val message = request("messages/${java.net.URLEncoder.encode(id, "UTF-8")}?format=full")
    // MIME decoding, HTML-to-text (keeping link targets), charsets and large attachment-backed parts live in parseGmailMessage.
    parseGmailMessage(message) { attachmentId ->
     runCatching { request("messages/${java.net.URLEncoder.encode(id, "UTF-8")}/attachments/${java.net.URLEncoder.encode(attachmentId, "UTF-8")}").optString("data") }.getOrNull()
    }
   }
   parsed.getOrNull()?.let { results += it } ?: run { lastScanSkippedMessages++ }
   progress(results.size)
  }
  pageToken = listing.optString("nextPageToken").takeIf { it.isNotBlank() }
 } while (pageToken != null)
 results
}

// Order metadata is extracted only from explicit email labels. The email Date header
// is NOT the purchase date; it can describe a shipping or delivery notification.
internal data class ParsedItem(val name: String, val quantity: Int = 1, val priceMinor: Long? = null)
internal data class EmailOrderDetails(val placedAt: Long? = null, val notes: String = "", val orderUrl: String = "", val items: List<ParsedItem> = emptyList(), val placedApproximate: Boolean = false, val placedFromConfirmation: Boolean = false)
internal fun parseMailDate(value: String): Long? {
 val cleaned = value.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
 val patterns = listOf("EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z", "EEE, dd MMM yyyy HH:mm Z")
 return patterns.firstNotNullOfOrNull { pattern -> runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(cleaned)?.time }.getOrNull() }?.takeIf { it <= System.currentTimeMillis() }
}
internal fun extractOrderDetails(email: GmailPreview): EmailOrderDetails {
 val content = "${email.subject}\n${email.body}\n${email.snippet}".replace(Regex("(?i)<[^>]+>"), " ")
 val dateMatch = Regex("(?im)\\b(?:order date|date ordered|ordered on|purchase date|purchased on|order placed on)\\s*[:#-]?\\s*([A-Za-z]+\\s+\\d{1,2},?\\s+\\d{4}|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})").find(content)
 val formats = listOf("MMMM d, yyyy", "MMM d, yyyy", "MMMM d yyyy", "MMM d yyyy", "yyyy-MM-dd", "yyyy/MM/dd", "MM/dd/yyyy", "M/d/yyyy", "MM-dd-yyyy")
 val explicitPlaced = dateMatch?.groupValues?.get(1)?.let { raw ->
  formats.firstNotNullOfOrNull { pattern -> runCatching {
   SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(raw.trim())?.time
  }.getOrNull() }
 }?.takeIf { it <= System.currentTimeMillis() }
 val isConfirmation = isConfirmationSubject(email.subject)
 // An order confirmation's own message date is the order date; a labeled date in other order mail is used only when no confirmation is available.
 val confirmedAt = if(isConfirmation || hasConfirmationHeader(email)) parseMailDate(email.date) else null
 val placed = confirmedAt ?: explicitPlaced
 val detailLines = mutableListOf<String>()
 fun capture(label: String, regex: String, max: Int = 100) {
  Regex(regex, RegexOption.IGNORE_CASE).find(content)?.groupValues?.getOrNull(1)
   ?.trim()?.replace(Regex("\\s+"), " ")?.take(max)?.takeIf { it.isNotBlank() }
   ?.takeIf { candidate -> !Regex("(?i)^(?:id|sku|order|number|item|product|qty|quantity|#)\\s*[:#-]?\\s*$").matches(candidate) && (candidate.any(Char::isLetter) && candidate.length >= 5 || label == "Order total" && Regex("[0-9]").containsMatchIn(candidate)) }
   ?.let { detailLines.add("$label: $it") }
 }
 capture("Items", "(?m)(?:items? ordered|items? purchased|product(?:s)?|item(?:s)?|you ordered|order summary)\\s*[:#-]?\\s*([^\\r\\n]{3,100})")
 capture("Order total", "(?m)(?:order total|grand total|total paid|total charged|total amount|amount paid)\\s*[:#-]?\\s*((?:USD|EUR|GBP|CAD|AUD|\\$|€|£)\\s*[0-9][0-9,.]*)")
 capture("Shipping", "(?m)(?:shipping method|shipping service|delivery method)\\s*[:#-]?\\s*([^\\r\\n]{3,70})", 70)
 val url = pickOrderUrl(email, content)
 return EmailOrderDetails(placed, detailLines.distinct().joinToString(" · ").take(450), url, if(isConfirmation) extractItems(content) else emptyList(), placedApproximate = false, placedFromConfirmation = confirmedAt != null)
}

// Completed delivery must be explicit and cannot appear in a forecast or ETA.
internal fun confirmedDelivery(email: GmailPreview): Boolean {
 val subject = email.subject.trim()
 val body = (email.subject + "\n" + email.snippet + "\n" + email.body).take(12000)
 val forecast = Regex("(?i)\\b(?:will be|should be|expected to be|estimated to be|scheduled to be|due to be|may be|arriving|delivery (?:expected|estimated|scheduled))\\s+delivered\\b|\\bdelivered\\s+(?:tomorrow|on|by|between|in the next)\\b")
 val confirmation = Regex("(?i)\\b(?:has been delivered|was delivered|package delivered|shipment delivered|successfully delivered|delivered successfully|delivered at|delivered to)\\b")
 // Prefer the subject as a strong signal; snippets often mix past and future events.
 return (confirmation.containsMatchIn(subject) && !forecast.containsMatchIn(subject)) ||
  (confirmation.containsMatchIn(body) && !forecast.containsMatchIn(body) && !Regex("(?i)\\b(?:estimated|expected|scheduled|forecast|tomorrow|will arrive|arriving)\\b").containsMatchIn(body))
}

// Conservative extraction from Gmail full message text and headers.
// All findings are suggestions only; ambiguous matches are not silently assigned.
internal data class EmailFinding(val tracking: String?, val carrier: String?, val status: String?, val orderNumber: String?, val matchedShipment: String?, val note: String)
internal fun extractEmailFinding(email: GmailPreview, saved: List<ShipmentTrackingNumberRecord>, shipments: List<ShipmentRecord>): EmailFinding {
 val content = "${email.subject} ${email.snippet} ${email.body}"
 // Whole-token comparison: a saved number never matches inside a longer identifier.
 val tokens = if(saved.isEmpty()) emptySet() else identifierTokens(content)
 val known = saved.filter { normalizeId(it.number).isNotEmpty() && normalizeId(it.number) in tokens }
 val matched = known.map { it.shipmentId }.distinct().singleOrNull()?.let { id -> shipments.firstOrNull { it.id == id } }
 val matchedNumber = if(known.map { it.shipmentId }.distinct().size == 1) known.firstOrNull() else null
 val tracking = matchedNumber?.number ?: extractTrackingCandidates(email).firstOrNull()
 val carrier = matchedNumber?.carrier ?: detectCarrier(email, tracking)
 val status = when(classifyEmail(email, tracking != null)) {
  EmailKind.DELIVERED -> "Delivered"
  EmailKind.OUT_FOR_DELIVERY -> "Out for Delivery"
  EmailKind.EXCEPTION -> "Exception"
  EmailKind.SHIPPED -> "In Transit (review)"
  else -> null
 }
 val orderNumber = extractOrderNumbers(content).firstOrNull()
 val matchName = matched?.let { it.title.ifBlank { "Shipment #${it.id}" } }
 val note = when { known.map { it.shipmentId }.distinct().size > 1 -> "Multiple saved shipments match; review manually"
  matchedNumber != null -> "Matched saved tracking number"
  tracking != null -> "Unverified tracking candidate; no saved shipment match"
  else -> "No matching saved tracking number found" }
 return EmailFinding(tracking, carrier, status, orderNumber, matchName, note)
}

// Preview-only association: only a shared, saved tracking number is strong enough
// to link retailer and carrier messages. No database changes are made here.
private data class EmailMergePreview(val tracking: String, val shipment: String, val orderNumber: String?, val evidence: List<String>, val recommendation: String)
private fun mergeEmailPreviews(messages: List<GmailPreview>, numbers: List<ShipmentTrackingNumberRecord>, shipments: List<ShipmentRecord>, orders: List<OrderRecord>): List<EmailMergePreview> {
 val result = mutableListOf<EmailMergePreview>()
 for (number in numbers.filter { it.number.isNotBlank() }.distinctBy { it.shipmentId to it.number }) {
  val normalized = number.number.filter { it.isLetterOrDigit() }.uppercase(Locale.US)
  if (normalized.length < 8) continue
  val related = messages.filter { msg -> (msg.subject + " " + msg.snippet).filter { it.isLetterOrDigit() }.uppercase(Locale.US).contains(normalized) }
  if (related.size < 2) continue
  val shipment = shipments.firstOrNull { it.id == number.shipmentId } ?: continue
  val foundOrders = related.mapNotNull { extractEmailFinding(it, numbers, shipments).orderNumber }.distinct()
  val linkedOrder = orders.firstOrNull { it.id == shipment.orderId }
  val exactOrder = if (foundOrders.size == 1) orders.filter { it.orderNumber.equals(foundOrders.single(), ignoreCase = true) }.singleOrNull() else null
  val recommendation = when {
   foundOrders.size > 1 -> "Conflicting order numbers — review required"
   linkedOrder != null && foundOrders.isNotEmpty() && foundOrders.none { it.equals(linkedOrder.orderNumber, true) } -> "Order number conflicts with linked order — review required"
   linkedOrder != null -> "Already linked to saved order #${linkedOrder.orderNumber}; no new shipment needed"
   exactOrder != null -> "Propose linking existing shipment to saved order #${exactOrder.orderNumber} — review only"
   foundOrders.size == 1 -> "Order #${foundOrders.single()} found in email, but no saved order matches; review only"
   else -> "Related messages found; no confirmed order number"
  }
  result += EmailMergePreview(number.number, shipment.title.ifBlank { "Shipment #${shipment.id}" }, foundOrders.singleOrNull(), related.map { it.sender + " — " + it.subject }, recommendation)
 }
 return result
}


// Inbox preview groups: the order (verified retailer + exact order number) is the parent. Saved orders resolve through assignEmails,
// the same resolver Update and import use; a confirmation or a retailer notice with enough order-specific evidence (orderEligibility) can seed a new order group.
// "shipment:new:<tracking>" / "shipment:saved:<id>" groups are standalone shipments with no order.
internal data class ScanGroup(
 val key: String,
 val title: String,
 val orderNumber: String?,
 val tracking: String?,
 val shipment: String?,
 val emails: List<GmailPreview>,
 val review: String?,
 val shippedWithoutTracking: Boolean = false,
 val establishesOrder: Boolean = false,
 val candidateOrderIds: List<Long> = emptyList(),
 val basis: String = ""
)

internal fun groupedScanResults(rawMessages: List<GmailPreview>, numbers: List<ShipmentTrackingNumberRecord>, shipments: List<ShipmentRecord>, orders: List<OrderRecord>,
 aliases: AliasBook = AliasBook.default, manual: Map<String, Long> = emptyMap(), persisted: Map<String, Set<Long>> = emptyMap()): List<ScanGroup> {
 val messages = rawMessages.distinctBy { emailKey(it) }
 val facts = messages.map(::buildEvidence).filter(::isRelevantEmail)
 val assignments = assignEmails(facts, orders, shipments, numbers, aliases, manual, persisted)
 fun normalized(value: String?) = normalizeId(value).takeIf { it.isNotEmpty() }
 fun displayRetailer(email: GmailPreview, saved: OrderRecord? = null): String {
  saved?.marketplace?.takeIf { it.isNotBlank() }?.let { return it }
  return retailerNameFromSender(email.sender).ifBlank { "Order correspondence" }
 }
 data class OrderGroupBuilder(
  val key: String,
  val retailerKey: String,
  val title: String,
  val orderNumber: String,
  val savedOrder: OrderRecord?,
  var confirmed: Boolean,
  var basis: String = "",
  val emails: LinkedHashMap<String, GmailPreview> = linkedMapOf()
 )
 fun verifiedRetailerMatch(builder: OrderGroupBuilder, email: GmailPreview): Boolean {
  if(builder.retailerKey == senderRetailerKey(email.sender, aliases)) return true
  val identityMessage = builder.emails.values.firstOrNull { !isCarrierSender(it.sender) } ?: return false
  val identity = OrderRecord(marketplace = builder.title, orderNumber = builder.orderNumber, name = builder.title)
  return retailerEvidence(identity, email, aliases) == RetailerEvidence.STRONG ||
   retailerEvidence(identity, identityMessage, aliases) == RetailerEvidence.STRONG &&
   domainBrand(senderDomain(identityMessage.sender)) == domainBrand(senderDomain(email.sender))
 }
 val builders = linkedMapOf<String, OrderGroupBuilder>()
 val unresolved = linkedMapOf<String, ScanGroup>()
 fun review(email: GmailPreview, reason: String, orderNumber: String? = null, tracking: String? = null, candidates: List<Long> = emptyList()) {
  val key = "review:${emailKey(email)}"
  unresolved.putIfAbsent(key, ScanGroup(key, displayRetailer(email), orderNumber, tracking, null, listOf(email), reason, candidateOrderIds = candidates))
 }
 fun addOrderEmail(email: GmailPreview, number: String, saved: OrderRecord?, confirmed: Boolean, fallbackRetailerKey: String, basis: String = "") {
  val key = if(saved != null) "room:${saved.id}" else "$fallbackRetailerKey:${normalizeId(number)}"
  val retailerKey = saved?.let { "saved:${it.id}" } ?: fallbackRetailerKey
  val builder = builders.getOrPut(key) {
   OrderGroupBuilder(key, retailerKey, displayRetailer(email, saved), saved?.orderNumber ?: number, saved, confirmed, basis)
  }
  builder.confirmed = builder.confirmed || confirmed
  if(basis.isNotEmpty() && (builder.basis.isEmpty() || basis == "Order confirmation")) builder.basis = basis
  builder.emails[emailKey(email)] = email
 }
 fun uniqueOrderNumber(ev: EmailEvidence): String? = ev.orderNumbers.distinctBy(::normalizeId).singleOrNull()

 val unresolvedEvidence = mutableListOf<EmailEvidence>()
 for(ev in facts) {
  when(val a = assignments[ev.key]) {
   is EmailAssignment.Matched -> {
    val saved = orders.firstOrNull { it.id == a.orderId }
    if(saved != null) addOrderEmail(ev.email, saved.orderNumber, saved, false, senderRetailerKey(ev.email.sender, aliases)) else unresolvedEvidence += ev
   }
   is EmailAssignment.Ambiguous -> review(ev.email, a.reason.replaceFirstChar { it.uppercase(Locale.US) }, ev.orderNumbers.distinctBy(::normalizeId).singleOrNull(),
    ev.trackingCandidates.firstOrNull(), a.orderIds.toList())
   else -> unresolvedEvidence += ev
  }
 }

 // A confirmation, or a retailer notice with enough order-specific evidence, establishes a new order (see orderEligibility).
 val establishing = unresolvedEvidence.filter { isOrderConfirmation(it) || orderEligibility(it).eligible }
 val establishingKeys = establishing.map { it.key }.toSet()
 val standaloneCandidates = mutableListOf<EmailEvidence>()
 for(ev in establishing) {
  val number = uniqueOrderNumber(ev)
  if(number == null) {
   review(ev.email, "Order confirmation does not contain one unambiguous order number")
   continue
  }
  val basis = orderEligibility(ev).basis.ifBlank { "Order confirmation" }
  val sameOrder = builders.values.filter { it.savedOrder == null && normalized(it.orderNumber) == normalized(number) && verifiedRetailerMatch(it, ev.email) }
  when {
   sameOrder.size > 1 -> review(ev.email, "Several confirmation groups share this retailer and exact order number", number)
   sameOrder.size == 1 -> {
    val target = sameOrder.single()
    target.emails[emailKey(ev.email)] = ev.email; target.confirmed = true
    if(target.basis.isEmpty() || basis == "Order confirmation") target.basis = basis
   }
   else -> addOrderEmail(ev.email, number, null, true, senderRetailerKey(ev.email.sender, aliases), basis)
  }
 }

 // Follow-ups join by the exact order number and a verified retailer, never by sender address or Gmail thread.
 for(ev in unresolvedEvidence) {
  if(ev.key in establishingKeys || ev.orderNumbers.isEmpty()) continue
  if(ev.orderNumbers.distinctBy(::normalizeId).size > 1) {
   review(ev.email, "This email names several order numbers", null, ev.trackingCandidates.firstOrNull())
   continue
  }
  val number = ev.orderNumbers.first()
  val matches = builders.values.filter { it.savedOrder == null && normalized(it.orderNumber) == normalized(number) && verifiedRetailerMatch(it, ev.email) }
  when {
   matches.size == 1 -> matches.single().emails[emailKey(ev.email)] = ev.email
   matches.size > 1 -> review(ev.email, "Several confirmation groups share this retailer and exact order number", number)
   standaloneShipmentEvidence(ev) != null -> standaloneCandidates += ev
   else -> review(ev.email, "Order number is present, but ${orderEligibility(ev).reason}, and no saved order or established order matches", number, ev.trackingCandidates.firstOrNull())
  }
 }

 // An email that needs review is held out of its order's group, so importing the order can never apply it before the user decides.
 for(builder in builders.values) for(email in builder.emails.values.toList()) {
  val found = dedupeTracking(extractTrackingCandidates(email))
  val saved = found.any { f -> numbers.any { sameTrackingNumber(it.number, f) } }
  if(found.size > 1 && !saved && !hasSeparatePackageEvidence(email) && !hasCarrierHandoffEvidence(email)) {
   builder.emails.remove(emailKey(email))
   review(email, "Lists several tracking numbers without wording that shows separate packages or a carrier handoff", builder.orderNumber, found.first(), listOfNotNull(builder.savedOrder?.id))
  }
 }
 builders.values.removeAll { it.emails.isEmpty() }

 // Tracking is secondary: an order-verified email in this scan can claim a number for carrier mail that has no order number.
 val trackingOwners = mutableMapOf<String, MutableSet<String>>()
 for(builder in builders.values) for(email in builder.emails.values) {
  extractTrackingCandidates(email).map(::normalizeId).filter { it.isNotEmpty() }.forEach { trackingOwners.getOrPut(it) { linkedSetOf() } += builder.key }
 }
 for(ev in unresolvedEvidence) {
  if(ev.orderNumbers.isNotEmpty()) continue
  val owners = ev.trackingCandidates.map(::normalizeId).flatMap { trackingOwners[it].orEmpty() }.toSet()
  when {
   owners.size == 1 -> builders[owners.single()]?.emails?.set(emailKey(ev.email), ev.email)
   owners.size > 1 -> review(ev.email, "Tracking number is associated with several candidate orders", tracking = ev.trackingCandidates.firstOrNull())
   standaloneShipmentEvidence(ev) != null -> standaloneCandidates += ev
   isShippingKind(ev.kind) || isCarrierSender(ev.email.sender) -> review(ev.email,
    "Relevant shipping email has no order number or tracking number already linked to a known order", tracking = ev.trackingCandidates.firstOrNull())
  }
 }

 val orderGroups = builders.values.map { builder ->
  val emails = builder.emails.values.toList()
  val tracks = dedupeTracking(emails.flatMap(::extractTrackingCandidates))
  val savedShipmentIds = tracks.flatMap { track -> numbers.filter { normalizeId(it.number) == normalizeId(track) } }.map { it.shipmentId }.distinct()
  val savedShipment = savedShipmentIds.singleOrNull()?.let { id -> shipments.firstOrNull { it.id == id } }
  val shippedWithoutTracking = tracks.isEmpty() && emails.any { statusForKind(classifyEmail(it, false)) == "In Transit" }
  val reviewText = when {
   savedShipmentIds.size > 1 -> "Tracking identifiers reference several saved shipments; verify each shipment"
   tracks.size > 1 -> "Several tracking numbers found; they stay unapplied unless the emails show separate packages or a carrier handoff"
   builder.savedOrder != null -> "Existing order, verified by retailer and exact order number; applying updates never creates a new order"
   shippedWithoutTracking -> "${builder.basis.ifBlank { "Order confirmation" }} found; shipped email has no tracking number"
   builder.basis == "Retailer shipping notice" -> "Retailer shipping notice with a verified sender, one order number and enough order details establishes this order without a confirmation"
   else -> "Order confirmation establishes this order identity"
  }
  ScanGroup(builder.key, builder.title, builder.orderNumber, tracks.singleOrNull(), savedShipment?.title,
   emails, reviewText, shippedWithoutTracking, builder.confirmed, basis = builder.basis)
 }
 // Genuine shipments with no establishable order: one standalone shipment per tracking number (saved ones are updated, never duplicated).
 val standaloneGroups = standaloneCandidates.groupBy { normalizeId(standaloneShipmentEvidence(it)!!.tracking) }.mapNotNull { (key, evs) ->
  val evidence = standaloneShipmentEvidence(evs.first())!!
  val savedShipment = numbers.firstOrNull { normalizeId(it.number) == key }?.let { n -> shipments.firstOrNull { it.id == n.shipmentId } }
  if(savedShipment?.orderId != null) {
   evs.forEach { review(it.email, "Tracking number belongs to a saved order but the email names a different order", null, evidence.tracking) }
   return@mapNotNull null
  }
  val retailer = evs.firstOrNull { !it.carrierSender }?.let { retailerNameFromSender(it.email.sender) }.orEmpty()
  ScanGroup(if(savedShipment != null) "shipment:saved:${savedShipment.id}" else "shipment:new:$key", retailer.ifBlank { "${evidence.carrier} shipment" }, null, evidence.tracking, savedShipment?.title,
   evs.map { it.email }, if(savedShipment != null) "Saved standalone shipment; applying updates never creates a duplicate" else "Standalone shipment: verified ${evidence.carrier} tracking number and ${evidence.status} status; no order is created")
 }
 val allGroups = orderGroups + standaloneGroups + unresolved.values
 return allGroups.map { group ->
  val retailerKey = group.emails.firstOrNull { !isCarrierSender(it.sender) }?.let { senderRetailerKey(it.sender, aliases) }
  val variant = allGroups.firstOrNull { other -> other.key != group.key && group.orderNumber != null && other.orderNumber != null &&
   retailerKey != null && other.emails.any { !isCarrierSender(it.sender) && senderRetailerKey(it.sender, aliases) == retailerKey } &&
   possibleOrderNumberVariant(group.orderNumber, other.orderNumber) }
  if(variant == null) group else group.copy(review = "Conflicting identifiers \u2014 possible order-number variant (${group.orderNumber} / ${variant.orderNumber}); review before importing")
 }
}

/** Groups an Inbox scan can import on its own: verified new orders (archived saved orders count as saved) and standalone shipments. */
internal fun importableGroups(groups: List<ScanGroup>, savedOrders: List<OrderRecord>, aliases: AliasBook): List<ScanGroup> = groups.filter { candidate ->
 candidate.key.startsWith("shipment:new:") ||
 (candidate.orderNumber != null && candidate.establishesOrder && candidate.review?.startsWith("Conflicting") != true &&
  savedOrders.none { order -> order.orderNumber.equals(candidate.orderNumber, ignoreCase = true) && aliases.same(order.marketplace, candidate.title) })
}

private val builtInMarkets = listOf("Amazon", "eBay", "AliExpress", "Walmart", "Target", "Best Buy", "Costco", "Home Depot", "Lowe's", "Etsy", "Newegg", "Macy's", "Kohl's", "Sam's Club", "Wayfair", "Temu", "SHEIN", "Google Store", "Apple", "Other")
private val carriers = listOf("USPS", "UPS", "FedEx", "DHL", "DHL eCommerce", "Cainiao", "China Post", "OnTrac", "Amazon Logistics", "Other")
private val orderStatuses = selectableOrderStatuses
private val shipmentStatuses = listOf("Awaiting Tracking", "Label Created", "In Transit", "Customs", "Out for Delivery", "Delivered", "Exception")
private fun dateTimeText(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
private fun dateTimeText(timestamp: Long?): String = timestamp?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Not available"
private fun dateText(timestamp: Long?): String = timestamp?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "Not available"
private fun parseDate(input: String): Long? = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(input)?.time }.getOrNull()
// Local format matching is a suggestion, not carrier verification. Overlapping formats
// intentionally produce multiple candidates; the user must explicitly confirm one.
private fun carrierCandidates(number: String): List<String> {
 val n = number.trim().replace(" ", "").uppercase(Locale.US)
 if(n.isBlank()) return emptyList()
 val candidates = mutableListOf<String>()
 if(n.matches(Regex("1Z[A-Z0-9]{16}"))) candidates += "UPS"
 if(n.matches(Regex("(94|93|92|95)[0-9]{18,24}")) || n.matches(Regex("[A-Z]{2}[0-9]{9}US"))) candidates += "USPS"
 if(n.matches(Regex("[0-9]{12}")) || n.matches(Regex("[0-9]{15}")) || n.matches(Regex("[0-9]{20}")) || n.matches(Regex("[0-9]{22}"))) candidates += "FedEx"
 if(n.matches(Regex("[0-9]{20,22}"))) candidates += "USPS"
 if(n.matches(Regex("[0-9]{10}")) || n.matches(Regex("JD[0-9]{18}"))) candidates += "DHL"
 if(n.matches(Regex("LP[A-Z0-9]{8,}")) || n.matches(Regex("[A-Z]{2}[0-9]{9}CN"))) candidates += "Cainiao"
 if(n.matches(Regex("[A-Z]{2}[0-9]{9}CN"))) candidates += "China Post"
 return candidates.distinct()
}
private fun trackingLink(carrier: String, number: String): String { val n = Uri.encode(number.trim()); return when(carrier) {
 "USPS" -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=$n"
 "UPS" -> "https://www.ups.com/track?tracknum=$n"
 "FedEx" -> "https://www.fedex.com/fedextrack/?trknbr=$n"
 "DHL" -> "https://www.dhl.com/global-en/home/tracking.html?tracking-id=$n"
 "DHL eCommerce" -> "https://webtrack.dhlglobalmail.com/?trackingnumber=$n"
 "Cainiao" -> "https://global.cainiao.com/detail.htm?mailNoList=$n"
 else -> ""
} }
private fun normalizeOrderUrl(raw: String): String {
 val input = raw.trim()
 if(input.isBlank()) return ""
 val normalized = if(input.startsWith("https://", true) || input.startsWith("http://", true)) input else "https://$input"
 return runCatching {
  val uri = Uri.parse(normalized)
  val redirected = if(uri.host?.lowercase(Locale.US) in listOf("google.com", "www.google.com") && uri.path == "/url") uri.getQueryParameter("q") ?: uri.getQueryParameter("url") else null
  val result = redirected ?: normalized
  if(Uri.parse(result).scheme == "https" && !Uri.parse(result).host.isNullOrBlank()) result else ""
 }.getOrDefault("")
}
private fun launchUrl(context: android.content.Context, url: String) {
 val u = Uri.parse(url)
 if (u.scheme == "https" && !u.host.isNullOrBlank()) runCatching {
  context.startActivity(Intent(Intent.ACTION_VIEW, u).apply { setPackage("com.android.chrome") })
 }.onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u)) } }
}

// Committed output of one order's update transaction; surfaced to the UI only after the commit succeeds.
private class OrderTx(val changes: List<ScanFieldChange>, val review: List<String>, val reviewEmails: List<ReviewItem.EmailNeedsReview> = emptyList())
// Thrown inside an import transaction to roll it back when the group needs review.
private class ReviewAbort(message: String): RuntimeException(message)
internal sealed interface SaveOutcome {
 data object Saved : SaveOutcome
 data object Duplicate : SaveOutcome
 data class Blocked(val message: String) : SaveOutcome
}
// What confirming a manual association would do, shown before anything is written.
internal data class AssociationPreview(val trackingNumbers: List<String>, val carrier: String?, val status: String?, val conflict: String?)
internal data class ArchiveSelection(val orderIds: List<Long>, val shipmentIds: List<Long>, val legacyIds: List<Long> = emptyList())

class TrackerVm(private val db: PackageDatabase, private val preferences: SettingsRepository): ViewModel() {
 private companion object { val updateMutex = Mutex() }
 private val dao = db.orderDao()
 val orders = dao.observeOrders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 val items = dao.observeAllItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 val standaloneShipments = dao.observeStandaloneShipments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 val shipments = dao.observeAllShipments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 val numbers = dao.observeAllNumbers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 val events = dao.observeAllEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 val markets = dao.observeMarketplaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 val legacy = db.dao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 val settings = preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrackerSettings())
 val shipmentChecks = preferences.shipmentChecks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
 fun text(name: String, value: String) = viewModelScope.launch { preferences.setText(name, value) }
 fun toggle(name: String, value: Boolean) = viewModelScope.launch { preferences.setBoolean(name, value) }
 fun addMarket(name: String, website: String) = viewModelScope.launch { dao.insertMarketplace(CustomMarketplaceRecord(name = name.trim(), website = website.trim())) }
 fun removeMarket(market: CustomMarketplaceRecord) = viewModelScope.launch { dao.deleteMarketplace(market) }
 fun editMarket(market: CustomMarketplaceRecord, name: String, website: String) = viewModelScope.launch { dao.updateMarketplace(market.copy(name = name.trim(), website = website.trim())) }
 /** Message for the last manual action that was refused (for example a duplicate tracking number). */
 val notice = MutableStateFlow("")
 fun clearNotice() { notice.value = "" }
 fun save(draft: OrderDraft, duplicateOverride: Boolean = false, onDone: () -> Unit, onDuplicate: () -> Unit) = viewModelScope.launch {
  when(val outcome = saveOrder(draft, duplicateOverride)) {
   SaveOutcome.Saved -> { notice.value = ""; onDone() }
   SaveOutcome.Duplicate -> onDuplicate()
   is SaveOutcome.Blocked -> notice.value = outcome.message
  }
 }
 /** Manual create/edit in one transaction. Every meaningful edit of an existing order is recorded with manual provenance. */
 internal suspend fun saveOrder(draft: OrderDraft, duplicateOverride: Boolean = false): SaveOutcome = db.withTransaction {
  val market = draft.market.trim(); val number = draft.number.trim()
  if (!duplicateOverride && aliasBook().namesFor(market).flatMap { dao.findDuplicates(it, number) }.any { it.id != draft.editId }) return@withTransaction SaveOutcome.Duplicate
  val old = if(draft.editId > 0) dao.getOrder(draft.editId) else null
  val previous = if(old != null) dao.getShipments(old.id) else emptyList()
  // Refuse a tracking number that belongs to another shipment or repeats within the form, before any write.
  val savedNumbers = dao.allTrackingNumbers()
  val formKeys = mutableSetOf<String>()
  draft.shipments.forEachIndexed { index, shipment ->
   val key = normalizeId(shipment.number)
   if(key.isEmpty()) return@forEachIndexed
   val ownShipment = previous.getOrNull(index)?.id
   if(!formKeys.add(key) || savedNumbers.any { normalizeId(it.number) == key && it.shipmentId != ownShipment })
    return@withTransaction SaveOutcome.Blocked("Tracking number ${shipment.number.trim()} already belongs to another shipment, so nothing was saved.")
  }
  val now = System.currentTimeMillis()
  val revised = OrderRecord(id = old?.id ?: 0, marketplace = market, orderNumber = number, name = draft.name.trim(), status = draft.status,
   placedAt = parseDate(draft.date), orderUrl = normalizeOrderUrl(draft.url), notes = draft.notes.trim(), archived = old?.archived ?: false,
   createdAt = old?.createdAt ?: now, statusUpdatedAt = if(old == null || old.status != draft.status) now else old.statusUpdatedAt,
   lastSyncedAt = old?.lastSyncedAt, archivedAt = old?.archivedAt)
  val id = if(old == null) dao.insertOrder(revised) else { dao.updateOrder(revised); old.id }
  suspend fun record(field: String, before: String, after: String) { if(old != null) dao.recordChange(id, field, before, after, SOURCE_MANUAL, "", now) }
  val oldItems = if(old != null) dao.getItems(id) else emptyList()
  if(old != null) dao.clearItems(id)
  val newItems = draft.products.filter { it.name.isNotBlank() }.map { OrderItemRecord(orderId = id, name = it.name.trim(), quantity = it.qty.toIntOrNull()?.coerceAtLeast(1) ?: 1, priceMinor = it.price.toDoubleOrNull()?.let { p -> (p * 100).toLong() }) }
  newItems.forEach { dao.insertItem(it) }
  if(old != null) {
   record("Marketplace", old.marketplace, revised.marketplace)
   record("Title", old.name, revised.name)
   record("Order number", old.orderNumber, revised.orderNumber)
   record("Placed date", dateText(old.placedAt), dateText(revised.placedAt))
   record("Order status", old.status, revised.status)
   record("Order URL", old.orderUrl, revised.orderUrl)
   record("Notes", old.notes, revised.notes)
   fun summary(list: List<OrderItemRecord>) = list.joinToString("; ") { "${it.name} \u00d7${it.quantity}" + (it.priceMinor?.let { p -> " @ $${"%.2f".format(Locale.US, p / 100.0)}" } ?: "") }
   record("Products", summary(oldItems), summary(newItems))
  }
  val total = maxOf(previous.size, draft.shipments.size)
  draft.shipments.forEachIndexed { index, shipment ->
   val title = shipment.title.trim()
   if(index < previous.size) {
    val prior = previous[index]
    val statusChanged = prior.status != shipment.status
    val updated = prior.copy(title = title, status = shipment.status, shippedAt = parseDate(shipment.shippedDate),
     statusUpdatedAt = if(statusChanged) now else prior.statusUpdatedAt, statusSource = if(statusChanged) "manual" else prior.statusSource,
     deliveredAt = if(!statusChanged) prior.deliveredAt else if(shipment.status == "Delivered") now else null)
    if(updated != prior) dao.updateShipment(updated)
    record(shipmentFieldLabel("Shipment title", index, total), prior.title, title)
    record(shipmentFieldLabel("Shipment status", index, total), prior.status, shipment.status)
    record(shipmentFieldLabel("Shipped date", index, total), dateText(prior.shippedAt), dateText(updated.shippedAt))
    val existingTrack = dao.getTrackingNumbersForShipment(prior.id).firstOrNull()
    if(shipment.number.isNotBlank() && shipment.carrier.isNotBlank()) {
     val newNumber = shipment.number.trim()
     if(existingTrack == null) {
      dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = prior.id, number = newNumber, carrier = shipment.carrier, trackingUrl = trackingLink(shipment.carrier, newNumber)))
      record(shipmentFieldLabel("Tracking number", index, total), "Not available", newNumber)
     } else {
      val revised = existingTrack.copy(number = newNumber, carrier = shipment.carrier, trackingUrl = trackingLink(shipment.carrier, newNumber))
      if(revised != existingTrack) dao.updateTrackingNumber(revised)
      if(normalizeId(existingTrack.number) != normalizeId(newNumber)) record(shipmentFieldLabel("Tracking number", index, total), existingTrack.number, newNumber)
      record(shipmentFieldLabel("Tracking carrier", index, total), existingTrack.carrier, shipment.carrier)
     }
    }
   } else {
    val sid = dao.insertShipment(ShipmentRecord(orderId = id, title = title, status = shipment.status, deliveredAt = if(shipment.status == "Delivered") now else null, shippedAt = parseDate(shipment.shippedDate)))
    record("Shipment", "Not available", "Shipment added" + if(title.isNotBlank()) ": $title" else "")
    if(shipment.number.isNotBlank()) {
     dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = shipment.number.trim(), carrier = shipment.carrier, trackingUrl = trackingLink(shipment.carrier, shipment.number)))
     record(shipmentFieldLabel("Tracking number", index, total), "Not available", shipment.number.trim())
    }
   }
  }
  settleOrder(id, now, respectManual = true)
  SaveOutcome.Saved
 }
 internal suspend fun importScanGroup(group: ScanGroup): String = withContext(Dispatchers.IO) {
  if(group.key.startsWith("shipment:")) return@withContext importStandaloneGroup(group)
  val orderNumber = group.orderNumber?.trim().orEmpty()
  if (orderNumber.isBlank()) return@withContext "Skipped: no confirmed order number"
  val retailer = group.title.trim().ifBlank { "Other" }
  try {
   // Same lock as scans; the whole import is one transaction, so a review or failure leaves nothing behind.
   updateMutex.withLock { withContext(NonCancellable) { db.withTransaction { importInTransaction(group, retailer, orderNumber) } } }
  } catch(e: ReviewAbort) { "Review required: ${e.message}" }
  catch(e: kotlinx.coroutines.CancellationException) { throw e }
  catch(e: Exception) { "Failed: import was rolled back: ${e.localizedMessage ?: "Unknown error"}" }
 }
 private suspend fun importInTransaction(group: ScanGroup, retailer: String, orderNumber: String): String {
  val aliases = aliasBook()
  val retailerNames = aliases.namesFor(retailer)
  val dismissed = preferences.dismissedGmailMessageIds()
  val emails = group.emails.distinctBy { emailKey(it) }.filterNot { it.messageId in dismissed }
  if(emails.isEmpty()) return "Skipped: all emails in this group were removed from review"
  val savedOrders = dao.allOrders()
  val savedShipments = dao.allShipments()
  val savedNumbers = dao.allTrackingNumbers()
  val markers = dao.allProcessed()
  val assignments = assignEmails(emails.map(::buildEvidence), savedOrders, savedShipments, savedNumbers, aliases,
   manualAssociationOwners(markers), persistedAssociationOwners(markers))
  val ambiguous = assignments.values.filterIsInstance<EmailAssignment.Ambiguous>()
  if(ambiguous.isNotEmpty()) throw ReviewAbort(ambiguous.map { it.reason }.distinct().joinToString("; "))
  val associatedIds = assignments.values.filterIsInstance<EmailAssignment.Matched>().map { it.orderId }.distinct()
  if(associatedIds.size > 1) throw ReviewAbort("correspondence in this group is already associated with different orders")
  val identityCandidates = exactSavedOrderCandidates(orderNumber, emails, savedOrders, aliases)
  val associatedId = associatedIds.singleOrNull()
  if(associatedId != null && identityCandidates.isNotEmpty() && identityCandidates.none { it.id == associatedId })
   throw ReviewAbort("saved message or shipment association conflicts with the retailer/order identity")
  val existingCandidates = when {
   associatedId != null -> savedOrders.filter { it.id == associatedId }
   else -> identityCandidates
  }
  if(existingCandidates.size > 1) throw ReviewAbort("multiple saved orders match this retailer and order number")
  val existing = existingCandidates.singleOrNull()
  if(existing == null && emails.none { orderEligibility(buildEvidence(it)).eligible })
   throw ReviewAbort("a new order needs an order confirmation or a verified retailer notice with enough order details")
  if(existing == null && retailerNames.any { dao.wasDeleted(it, orderNumber) > 0 }) return "Skipped: this order was previously deleted"
  val candidateKeys = (emails.flatMap { extractTrackingCandidates(it) } + listOfNotNull(group.tracking)).map(::normalizeId).filter { it.isNotEmpty() }.toSet()
  val owners = savedNumbers.filter { normalizeId(it.number) in candidateKeys }.map { it.shipmentId }.distinct()
   .mapNotNull { id -> savedShipments.firstOrNull { it.id == id } }
  // Every conflict is resolved before the first write; any later review outcome aborts and rolls back the transaction.
  if(owners.any { it.orderId != null && it.orderId != existing?.id }) throw ReviewAbort("tracking already belongs to another order")
  val isNew = existing == null
  val base = existing ?: OrderRecord(marketplace = retailer, orderNumber = orderNumber, name = orderTitleFromEmails(emails) ?: retailer, status = "Order Placed")
   .let { it.copy(id = dao.insertOrder(it)) }
  owners.filter { it.orderId == null }.forEach { standalone ->
   dao.updateShipment(standalone.copy(orderId = base.id))
   if(!isNew) dao.recordChange(base.id, "Shipment linked", "Not available", "Standalone shipment ${standalone.title.ifBlank { "#" + standalone.id }}", SOURCE_IMPORT)
  }
  // A brand-new order has no prior state, so its initial values are not recorded as updates.
  val result = applyAssignedEmails(base, emails, reanalyze = true, recordHistory = !isNew)
  if(owners.any { it.orderId == null }) settleOrder(base.id, respectManual = true)
  // Ambiguous emails stay unprocessed for Needs Review; they never roll back the verified order.
  val pending = if(result.review.isEmpty()) "" else " \u00b7 ${result.review.size} ${if(result.review.size == 1) "email needs" else "emails need"} review"
  val detail = if(result.changes.isEmpty()) "" else " \u00b7 ${result.changes.size} ${if(result.changes.size == 1) "change" else "changes"} recorded"
  return if(isNew) "Imported order #$orderNumber$pending" else "Existing order #$orderNumber checked; no duplicate created$detail$pending"
 }
 private suspend fun importStandaloneGroup(group: ScanGroup): String = try {
  updateMutex.withLock { withContext(NonCancellable) { db.withTransaction {
   applyStandaloneEmails(group.emails, create = true).joinToString(" \u00b7 ").ifBlank { "Skipped: no verified shipment evidence" }
  } } }
 } catch(e: kotlinx.coroutines.CancellationException) { throw e }
 catch(e: Exception) { "Failed: import was rolled back: ${e.localizedMessage ?: "Unknown error"}" }

 private fun standaloneTitle(email: GmailPreview, carrier: String, tracking: String): String =
  subjectItemName(email.subject) ?: retailerNameFromSender(email.sender).takeIf { !isCarrierSender(email.sender) && it.isNotBlank() }
   ?: "$carrier ${normalizeId(tracking).takeLast(4)}"

 /**
  * Standalone shipments (no order): one per tracking number. A saved shipment with the number is updated in place, a new one is created only when `create`.
  * Older mail never lowers a newer status; each Gmail message is recorded once as a tracking event, so repeated scans add nothing.
  */
 private suspend fun applyStandaloneEmails(emails: List<GmailPreview>, create: Boolean): List<String> {
  val dismissed = preferences.dismissedGmailMessageIds()
  val groups = emails.distinctBy { emailKey(it) }.filterNot { it.messageId in dismissed }.map(::buildEvidence)
   .mapNotNull { ev -> standaloneShipmentEvidence(ev)?.let { ev to it } }.groupBy { normalizeId(it.second.tracking) }
  val outcomes = mutableListOf<String>()
  for((key, group) in groups) {
   val sorted = group.sortedBy { parseMailDate(it.first.email.date) ?: Long.MAX_VALUE }
   val first = sorted.first().second
   val number = dao.allTrackingNumbers().firstOrNull { normalizeId(it.number) == key }
   var ship = number?.let { dao.getShipment(it.shipmentId) }
   if(ship?.orderId != null) { outcomes += "Tracking ${first.tracking} already belongs to an order"; continue }
   var created = false
   val shippedMailAt = sorted.firstOrNull { it.first.dispatch }?.let { parseMailDate(it.first.email.date) }
   if(ship == null) {
    if(!create) continue
    val id = dao.insertShipment(ShipmentRecord(orderId = null, title = standaloneTitle(sorted.first().first.email, first.carrier, first.tracking), carrier = first.carrier, status = "Awaiting Tracking", statusSource = "gmail-inbox", shippedAt = shippedMailAt))
    dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = id, number = first.tracking, carrier = first.carrier, trackingUrl = trackingLink(first.carrier, first.tracking)))
    ship = dao.getShipment(id)!!
    created = true
   } else if(ship.carrier.isBlank() && number != null) { ship = ship.copy(carrier = number.carrier); dao.updateShipment(ship) }
   var changed = created
   if(ship!!.shippedAt == null && shippedMailAt != null && !created) { ship = ship.copy(shippedAt = shippedMailAt); dao.updateShipment(ship); changed = true }
   var since = if(created) 0L else ship.statusUpdatedAt
   val seen = dao.eventMessageIds(ship.id).toMutableSet()
   for((ev, st) in sorted) {
    val at = parseMailDate(ev.email.date)
    if(gmailShipmentStatusApplies(ship!!.status, st.status, at, since)) {
     val now = System.currentTimeMillis()
     ship = ship.copy(status = st.status, statusSource = "gmail-inbox", statusUpdatedAt = now, deliveredAt = if(st.status == "Delivered") now else ship.deliveredAt)
     dao.updateShipment(ship)
     if(at != null) since = maxOf(since, at)
     changed = true
    }
    if(ev.email.messageId.isNotBlank() && seen.add(ev.email.messageId)) {
     dao.insertEvent(ShipmentTrackingEventRecord(shipmentId = ship!!.id, description = "${st.status} \u00b7 ${ev.email.subject.ifBlank { "(No subject)" }.take(100)}", carrier = st.carrier,
      status = st.status, source = "gmail-inbox", eventAt = at ?: System.currentTimeMillis(), sourceMessageId = ev.email.messageId))
     changed = true
    }
   }
   val label = "${first.carrier} ${first.tracking}"
   outcomes += when {
    created -> "Imported standalone shipment $label \u00b7 ${ship!!.status}"
    changed -> "Existing shipment $label updated \u00b7 ${ship!!.status}"
    else -> "Existing shipment $label checked; no duplicate created"
   }
  }
  return outcomes
 }
 private class RecordSnapshot(val orders: Set<Long>, val history: Map<Long, Int>, val shipments: Map<Long, String>)
 private suspend fun recordSnapshot(): RecordSnapshot {
  val numbers = dao.allTrackingNumbers().groupingBy { it.shipmentId }.eachCount()
  val events = dao.eventShipmentIds().groupingBy { it }.eachCount()
  return RecordSnapshot(dao.allOrders().map { it.id }.toSet(), dao.historyOrderIds().groupingBy { it }.eachCount(),
   dao.allShipments().associate { it.id to "${it.orderId}|${it.status}|${it.statusUpdatedAt}|${it.carrier}|${it.shippedAt}|${numbers[it.id]}|${events[it.id]}" })
 }
 /**
  * Shared by manual Inbox scans and background sync: verified orders and standalone shipments are imported without confirmation,
  * saved records are updated, and anything ambiguous is left for Needs Review. Counts come from what actually changed in Room.
  */
 internal suspend fun autoImportFromMessages(rawMessages: List<GmailPreview>, reanalyze: Boolean = true): ScanRunSummary = withContext(Dispatchers.IO) {
  val messages = filterDismissedEmails(rawMessages).distinctBy { emailKey(it) }
  val aliases = aliasBook()
  val before = recordSnapshot()
  val markers = dao.allProcessed()
  val groups = groupedScanResults(messages, dao.allTrackingNumbers(), dao.allShipments(), dao.allOrders(), aliases, manualAssociationOwners(markers), persistedAssociationOwners(markers))
  val outcomes = mutableListOf<String>()
  val attention = mutableSetOf<String>()
  for(group in importableGroups(groups, dao.allOrders(), aliases)) {
   val outcome = importScanGroup(group)
   outcomes += "${group.title}${group.orderNumber?.let { " #$it" }.orEmpty()}: $outcome"
   if(outcome.startsWith("Review required") || outcome.startsWith("Failed")) attention += group.key
  }
  val update = updateOrdersFromEmails(messages, dao.allOrders(), reanalyze = reanalyze)
  val after = recordSnapshot()
  val freshOrders = dao.allOrders()
  val freshMarkers = dao.allProcessed()
  val regrouped = groupedScanResults(messages, dao.allTrackingNumbers(), dao.allShipments(), freshOrders, aliases, manualAssociationOwners(freshMarkers), persistedAssociationOwners(freshMarkers))
  val stillImportable = importableGroups(regrouped, freshOrders, aliases)
  val reviewEmails = (regrouped.filter { g -> g.key.startsWith("review:") || (g.review != null && !g.key.startsWith("room:") && !g.key.startsWith("shipment:saved:") && stillImportable.none { it.key == g.key }) }.flatMap { it.emails } +
   stillImportable.filter { it.key in attention }.flatMap { it.emails } +
   update.reviews.filterIsInstance<ReviewItem.EmailNeedsReview>().map { it.email }).map { emailKey(it) }.toSet()
  ScanRunSummary(
   ordersCreated = (after.orders - before.orders).size,
   ordersUpdated = after.orders.count { id -> id in before.orders && (after.history[id] ?: 0) > (before.history[id] ?: 0) },
   shipmentsCreated = (after.shipments.keys - before.shipments.keys).size,
   shipmentsUpdated = after.shipments.count { (id, signature) -> before.shipments[id]?.let { it != signature } == true },
   needsReview = reviewEmails.size,
   ignored = messages.count { !isRelevantEmail(buildEvidence(it)) },
   outcomes = outcomes, update = update)
 }
 /** Match only explicit order IDs or existing tracking numbers. Never create new orders here. */
 suspend fun updateOrdersFromEmails(messages: List<GmailPreview>, targets: List<OrderRecord>, reanalyze: Boolean = true, orderSpecific: Boolean = false): ScanUpdateSummary = withContext(Dispatchers.IO) {
  val dismissed = preferences.dismissedGmailMessageIds()
  val evidence = messages.distinctBy { emailKey(it) }.filterNot { it.messageId in dismissed }.map(::buildEvidence).filter(::isRelevantEmail)
  val aliases = aliasBook()
  // Scans are serialized (manual and background) and database work already started is allowed to finish,
  // so leaving the scan screen can never leave an order half-applied.
  updateMutex.withLock { withContext(NonCancellable) {
   val outcomes = targets.distinctBy { it.id }.map { applyEmailsToOrder(evidence, it, reanalyze, aliases) }
   val reviews = outcomes.flatMap { it.second }.groupBy { emailKey(it.email) }.values.map { group ->
    group.first().copy(candidateOrderIds = group.flatMap { it.candidateOrderIds }.distinct(), reason = group.map { it.reason }.distinct().joinToString("; "))
   }
   // Previously, unmatched shipping correspondence vanished from the results entirely.
   // Keep it visible for explicit association rather than silently dropping it.
  val markers = dao.allProcessed()
  val assignments = assignEmails(evidence, dao.allOrders(), dao.allShipments(), dao.allTrackingNumbers(), aliases,
   manualAssociationOwners(markers), persistedAssociationOwners(markers))
  val unmatched = if(orderSpecific) emptyList() else evidence.filter { ev ->
    assignments[ev.key] == EmailAssignment.Unrelated && isShippingKind(ev.kind) && standaloneShipmentEvidence(ev) == null
   }.map { ev -> ReviewItem.EmailNeedsReview(ev.email,
    "Shipping correspondence could not be matched confidently to a saved order", emptyList(), ev.trackingCandidates) }
  // Unmatched mail that is a verified shipment updates a saved standalone shipment; new ones are created from the Inbox scan, never here.
  if(!orderSpecific) db.withTransaction { applyStandaloneEmails(evidence.filter { assignments[it.key] == EmailAssignment.Unrelated }.map { it.email }, create = false) }
   val allReviews = (reviews + unmatched).groupBy { emailKey(it.email) }.values.map { group ->
    group.first().copy(candidateOrderIds = group.flatMap { it.candidateOrderIds }.distinct(),
     reason = group.map { it.reason }.distinct().joinToString("; "))
   }
  val allOrders = dao.allOrders()
  val processed = dao.allProcessed()
  val diagnosed = outcomes.map { (result, _) ->
   val diagnostics = evidence.mapNotNull { ev ->
    val assignment = assignments[ev.key] ?: EmailAssignment.Unrelated
    val match = assignment as? EmailAssignment.Matched
    val ambiguousMatch = assignment as? EmailAssignment.Ambiguous
    if(match?.orderId != result.orderId && result.orderId !in ambiguousMatch?.orderIds.orEmpty()) return@mapNotNull null
    val changes = result.changes.filter { it.sourceMessageId == ev.email.messageId }.map { it.field }.distinct()
    val assignmentCandidates = when {
     match != null -> listOf(match.orderId)
     ambiguousMatch != null -> ambiguousMatch.orderIds.toList()
     else -> emptyList()
    }
    val explicitCandidates = allOrders.filter { normalizeId(it.orderNumber) in ev.orderNumberKeys }
    val candidateIds = (assignmentCandidates + explicitCandidates.map { it.id }).distinct().sorted()
    val rejectedCandidates = explicitCandidates.filter { it.id !in assignmentCandidates }.map {
     "Order ${it.id}: retailer identity or conflicting identifiers did not support this match"
    }
    val committed = result.outcome != ScanOutcome.FAILED &&
     (processed.any { it.messageId == ev.email.messageId && it.orderId == result.orderId } || changes.isNotEmpty())
    val matchingEvidence = when {
    match?.manual == true -> "Previously confirmed manual association"
    match?.viaPersisted == true -> "Previously saved message-to-order association"
    match?.viaOrderNumber == true && match.viaTracking -> "Exact retailer/order number and unique tracking number"
    match?.viaOrderNumber == true -> "Verified retailer identity and exact order number"
    match?.viaTracking == true -> "Unique tracking-number association"
    match?.viaThread == true -> "Gmail thread with no conflicting identifiers"
    match?.viaContext == true -> "Verified retailer identity and unique eligible order"
    ambiguousMatch != null -> ambiguousMatch.reason
    else -> "No confident match"
    }
    val outcomeText = when {
    ambiguousMatch != null -> "Needs review: ${ambiguousMatch.reason}"
    changes.isNotEmpty() -> "Applied changes: ${changes.joinToString() }"
    result.outcome == ScanOutcome.FAILED -> "Failed; transaction rolled back"
    processed.any { it.messageId == ev.email.messageId && it.orderId == result.orderId } -> "Associated; no changes needed"
    result.notes.any { it.contains(ev.email.subject.take(60)) } -> "Needs review: ${result.notes.first { it.contains(ev.email.subject.take(60)) }}"
    else -> "Associated; no fields changed"
    }
    ScanEmailDiagnostic(
    messageId = ev.email.messageId,
    subject = ev.email.subject.ifBlank { "(No subject)" },
    retailer = match?.let { allOrders.firstOrNull { order -> order.id == it.orderId }?.marketplace }
     ?: ambiguousMatch?.orderIds?.mapNotNull { id -> allOrders.firstOrNull { it.id == id }?.marketplace }?.distinct()?.joinToString(" / ")
     ?: ev.email.sender.substringBefore('<').trim().ifBlank { senderDomain(ev.email.sender) },
    orderNumbers = ev.orderNumbers,
    trackingNumbers = ev.trackingCandidates,
    carrier = detectCarrier(ev.email, ev.trackingCandidates.firstOrNull()),
    matchingEvidence = matchingEvidence,
    result = outcomeText,
    changesApplied = changes,
    candidateSavedOrderIds = candidateIds,
    rejectedCandidates = rejectedCandidates,
    databaseCommitted = committed
    )
   }
   result.copy(diagnostics = diagnostics)
  }
  reconcileArchives()
  ScanUpdateSummary(diagnosed, allReviews)
  } }
 }
 /**
  * Manual Shipments-tab refresh over mail that was already fetched. Only Inbox mail naming one of a target's saved tracking numbers
  * (with a compatible carrier) is used. Standalone shipments take the standalone update path; order-linked ones go through the same
  * per-order update as Orders -> Update, so order rules, history and archiving behave exactly as for any other scan.
  * Nothing is created here, and the background schedule is never touched.
  */
 internal suspend fun updateShipmentsFromEmails(messages: List<GmailPreview>, targets: List<ShipmentRecord>): ShipmentUpdateSummary = withContext(Dispatchers.IO) {
  val dismissed = preferences.dismissedGmailMessageIds()
  val evidence = messages.distinctBy { emailKey(it) }.filterNot { it.messageId in dismissed }.map(::buildEvidence)
  val saved = targets.distinctBy { it.id }.mapNotNull { dao.getShipment(it.id) }
  val numbers = saved.associate { ship -> ship.id to dao.getTrackingNumbersForShipment(ship.id).filter { it.number.isNotBlank() } }
  val checkable = saved.filter { numbers[it.id].orEmpty().isNotEmpty() }
  fun relevant(ship: ShipmentRecord): List<GmailPreview> = evidence.filter { ev ->
   numbers[ship.id].orEmpty().any { n -> (normalizeId(n.number) in ev.tokens || ev.trackingCandidates.any { sameTrackingNumber(n.number, it) }) && carrierMatches(n.carrier, ev.email, n.number) }
  }.map { it.email }
  suspend fun signature(id: Long): String {
   val ship = dao.getShipment(id) ?: return "deleted"
   return "${ship.status}|${ship.statusUpdatedAt}|${ship.carrier}|${ship.shippedAt}|${ship.orderId}|${dao.getTrackingNumbersForShipment(id).size}|${dao.eventMessageIds(id).size}"
  }
  val before = checkable.associate { it.id to signature(it.id) }
  var reviews = 0
  val standaloneMail = checkable.filter { it.orderId == null }.flatMap(::relevant).distinctBy { emailKey(it) }
  if(standaloneMail.isNotEmpty()) updateMutex.withLock { withContext(NonCancellable) { db.withTransaction { applyStandaloneEmails(standaloneMail, create = false) } } }
  for((orderId, ships) in checkable.filter { it.orderId != null }.groupBy { it.orderId!! }) {
   val order = dao.getOrder(orderId) ?: continue
   val mail = ships.flatMap(::relevant).distinctBy { emailKey(it) }
   if(mail.isEmpty()) continue
   reviews += updateOrdersFromEmails(mail, listOf(order), reanalyze = true, orderSpecific = true).reviews.size
  }
  reconcileArchives()
  preferences.markShipmentsChecked(checkable.map { it.id }, System.currentTimeMillis())
  val changed = checkable.filter { signature(it.id) != before[it.id] }.map { it.id }.toSet()
  val lines = saved.map { ship ->
   val now = dao.getShipment(ship.id)
   val label = ship.title.ifBlank { numbers[ship.id].orEmpty().firstOrNull()?.number ?: "Shipment #${ship.id}" }
   when {
    numbers[ship.id].isNullOrEmpty() -> "$label: skipped, no saved tracking number"
    ship.id in changed -> "$label: updated \u00b7 ${canonicalShipmentStatus(now?.status ?: ship.status)}"
    else -> "$label: checked, no new information \u00b7 ${canonicalShipmentStatus(now?.status ?: ship.status)}"
   }
  }
  ShipmentUpdateSummary(checkable.size, changed.size, checkable.size - changed.size, saved.size - checkable.size, reviews, lines)
 }
 private fun carrierMatches(saved: String, email: GmailPreview, number: String): Boolean {
  val detected = detectCarrier(email, number, strict = true) ?: return true
  return saved.isBlank() || saved.equals("Other", ignoreCase = true) || detected.startsWith(saved, ignoreCase = true) || saved.startsWith(detected, ignoreCase = true)
 }
 private suspend fun aliasBook(): AliasBook = AliasBook.of(preferences.aliasGroups())
 /** One order per transaction: either every change and its history row commit, or none do. */
 private suspend fun applyEmailsToOrder(evidence: List<EmailEvidence>, target: OrderRecord, reanalyze: Boolean, aliases: AliasBook): Pair<OrderScanResult, List<ReviewItem.EmailNeedsReview>> {
  fun result(outcome: ScanOutcome, changes: List<ScanFieldChange> = emptyList(), notes: List<String> = emptyList()) =
   OrderScanResult(target.id, target.marketplace, target.orderNumber, outcome, changes, notes)
  return try {
   val tx = db.withTransaction<OrderTx?> {
   // Re-read persisted state: the caller's snapshot may predate edits or an earlier scan.
   val order = dao.getOrder(target.id) ?: return@withTransaction null
   // Ownership is decided against every saved order so equal order numbers at other retailers can never be mixed up.
  val markers = dao.allProcessed()
  val assignments = assignEmails(evidence, dao.allOrders(), dao.allShipments(), dao.allTrackingNumbers(), aliases,
   manualAssociationOwners(markers), persistedAssociationOwners(markers))
   val mine = evidence.filter { (assignments[it.key] as? EmailAssignment.Matched)?.orderId == order.id }.map { it.email }
   val ambiguousEmails = evidence.mapNotNull { ev ->
    (assignments[ev.key] as? EmailAssignment.Ambiguous)?.takeIf { order.id in it.orderIds }?.let { ev to it }
   }
   val ambiguous = ambiguousEmails.map { (ev, a) -> "Email \"${ev.email.subject.take(60)}\" could belong to more than one order (${a.reason}); not applied" }.distinct()
   val ambiguousItems = ambiguousEmails.map { (ev, a) -> ReviewItem.EmailNeedsReview(ev.email, a.reason, a.orderIds.toList(), ev.trackingCandidates) }
   // lastSyncedAt is bookkeeping, not a data update; it never produces a history row or an "updated" result.
   if(mine.isEmpty()) { dao.updateOrder(order.copy(lastSyncedAt = System.currentTimeMillis())); return@withTransaction OrderTx(emptyList(), ambiguous, ambiguousItems) }
   val applied = applyAssignedEmails(order, mine, reanalyze, recordHistory = true)
   OrderTx(applied.changes, applied.review + ambiguous, applied.reviewEmails + ambiguousItems)
   }
   when {
    tx == null -> result(ScanOutcome.FAILED, notes = listOf("Order no longer exists; nothing was changed")) to emptyList()
    tx.changes.isNotEmpty() -> result(ScanOutcome.UPDATED, tx.changes, tx.review) to tx.reviewEmails
    tx.review.isNotEmpty() -> result(ScanOutcome.REVIEW_REQUIRED, notes = tx.review) to tx.reviewEmails
    else -> result(ScanOutcome.UNCHANGED) to tx.reviewEmails
   }
  } catch(e: kotlinx.coroutines.CancellationException) { throw e }
  catch(e: Exception) { result(ScanOutcome.FAILED, notes = listOf("Update failed; changes for this order were rolled back: ${e.localizedMessage ?: "Unknown error"}")) to emptyList() }
 }
 /**
  * Applies emails already attributed to one order. Must run inside a Room transaction: field changes, history rows
  * and processed-message markers commit together or not at all. Bookkeeping (lastSyncedAt) is never reported as a change.
  */
 private suspend fun applyAssignedEmails(start: OrderRecord, emails: List<GmailPreview>, reanalyze: Boolean, recordHistory: Boolean,
  preferredShipmentId: Long? = null, markerVersion: Int = GMAIL_PARSER_VERSION): OrderTx {
  val now = System.currentTimeMillis()
  var order = start
  val review = mutableListOf<String>()
  val reviewEmails = mutableListOf<ReviewItem.EmailNeedsReview>()
  val applied = mutableListOf<ScanFieldChange>()
  val markers = dao.processedForOrder(order.id).associate { it.messageId to it.extractionVersion }
  val seen = mutableSetOf<String>()
  // A message processed by the current parser is a deduplicated no-op; an older marker allows controlled reanalysis.
  val pending = emails.filter { seen.add(emailKey(it)) && shouldProcessMessage(markers[it.messageId], reanalyze) }
  if(pending.isEmpty()) { dao.updateOrder(order.copy(lastSyncedAt = now)); return OrderTx(applied, review, reviewEmails) }
  val lastSync = start.lastSyncedAt
  suspend fun audit(field: String, before: String, after: String, email: GmailPreview?, forwardProgress: Boolean = false, force: Boolean = false) {
   if(!recordHistory && !force) return
   val row = dao.recordChange(order.id, field, before, after, email?.messageId.orEmpty(), email?.date.orEmpty(), now) ?: return
   if(!recordHistory) return
   val emailTime = email?.date?.let(::parseMailDate)
   val olderMail = email != null && ((email.messageId.isNotBlank() && markers.containsKey(email.messageId)) || (emailTime != null && lastSync != null && emailTime <= lastSync))
  applied += ScanFieldChange(row.id, field, before, after, classifyChange(before, olderMail, forwardProgress),
   email?.let { describeSource(it.sender, it.subject) }, email?.date.orEmpty(), now, row.sourceMessageId)
  }
  val details = pending.map(::extractOrderDetails)
  val explicitDate = details.filter { it.placedFromConfirmation }.mapNotNull { it.placedAt }.minOrNull()
   ?: details.filter { !it.placedApproximate }.mapNotNull { it.placedAt }.minOrNull()
  val currentApproximate = order.placedAt != null && dao.latestChangeForField(order.id, "Placed date")?.newValue?.endsWith(APPROXIMATE_DATE_SUFFIX) == true
  val newPlaced: Pair<Long, Boolean>? = when {
   order.placedAt == null || currentApproximate -> explicitDate?.let { it to false }
   else -> null
  }
  val extractedNotes = details.map { it.notes }.filter { it.isNotBlank() }.distinct().joinToString(" \u00b7 ").take(600)
  val extractedUrl = details.firstOrNull { it.orderUrl.isNotBlank() }?.orderUrl.orEmpty()
  // Populated values are never overwritten, so a manual edit always survives a Gmail backfill.
  val filled = order.copy(
   placedAt = newPlaced?.first ?: order.placedAt,
   notes = if(order.notes.isBlank() || order.notes.startsWith("Discovered from Gmail Inbox")) extractedNotes.ifBlank { order.notes } else order.notes,
   orderUrl = order.orderUrl.ifBlank { extractedUrl }
  )
  if(newPlaced != null) {
   val dateEmail = pending.firstOrNull { if(newPlaced.second) parseMailDate(it.date) == newPlaced.first else extractOrderDetails(it).let { d -> !d.placedApproximate && d.placedAt == newPlaced.first } }
   audit("Placed date", dateText(order.placedAt) + if(currentApproximate) APPROXIMATE_DATE_SUFFIX else "", dateText(newPlaced.first) + if(newPlaced.second) APPROXIMATE_DATE_SUFFIX else "", dateEmail, force = newPlaced.second)
  }
  audit("Notes", order.notes, filled.notes, pending.firstOrNull { extractOrderDetails(it).notes.isNotBlank() })
  audit("Order URL", order.orderUrl, filled.orderUrl, pending.firstOrNull { extractOrderDetails(it).orderUrl.isNotBlank() })
  order = filled
  // Products come only from explicit Item/Product lines and only fill an order that has none.
  val parsedItems = details.flatMap { it.items }.distinctBy { it.name.lowercase(Locale.US) }
  if(parsedItems.isNotEmpty() && dao.getItems(order.id).isEmpty()) {
   parsedItems.forEach { dao.insertItem(OrderItemRecord(orderId = order.id, name = it.name, quantity = it.quantity, priceMinor = it.priceMinor)) }
   audit("Products", "Not available", parsedItems.joinToString("; ") { "${it.name} \u00d7${it.quantity}" }, pending.firstOrNull { extractOrderDetails(it).items.isNotEmpty() })
  }
  var statusAdvanced = false
  var statusEmail: GmailPreview? = null
  // Email date of a status applied in this pass; persisted statuses fall back to statusUpdatedAt.
  val statusEvidenceAt = mutableMapOf<Long, Long>()
  val handled = mutableListOf<GmailPreview>()
  // Only a dispatch or carrier-acceptance notice dates a shipment, and only the notice that touched it; label, tracking and later movement mail never do.
  val gmailShippedAt = mutableMapOf<Long, Long>()
  for(email in pending) {
   val ev = buildEvidence(email)
   // A number saved on a standalone shipment is adopted by this order, unless the email also carries a number owned by another order.
   val numberOwners = ev.trackingCandidates.flatMap { c -> dao.allTrackingNumbers().filter { sameTrackingNumber(it.number, c) } }.mapNotNull { dao.getShipment(it.shipmentId) }.distinctBy { it.id }
   if(numberOwners.none { it.orderId != null && it.orderId != order.id }) numberOwners.filter { it.orderId == null }.forEach { standalone ->
    dao.updateShipment(standalone.copy(orderId = order.id))
    statusAdvanced = true
    statusEmail = email
    audit("Shipment linked", "Not available", "Standalone shipment ${standalone.title.ifBlank { "#" + standalone.id }}", email)
   }
   // Re-read persisted state before each message to prevent duplicate inserts and stale status comparisons.
   val ships = dao.getShipments(order.id)
   val nums = ships.flatMap { dao.getTrackingNumbersForShipment(it.id) }
   val global = dao.allTrackingNumbers()
   val label = email.subject.take(60)
   fun flag(reason: String) { review += reason; reviewEmails += ReviewItem.EmailNeedsReview(email, reason, listOf(order.id), ev.trackingCandidates) }
   val savedHere = nums.filter { n -> normalizeId(n.number) in ev.tokens || ev.trackingCandidates.any { sameTrackingNumber(n.number, it) } }
   val savedShipIds = savedHere.map { it.shipmentId }.distinct()
   val unsaved = ev.trackingCandidates.filter { c -> nums.none { sameTrackingNumber(it.number, c) } }
   val foreign = unsaved.firstOrNull { c -> global.any { sameTrackingNumber(it.number, c) } }
   if(foreign != null) { flag("Tracking number $foreign from \"$label\" belongs to another shipment; not applied"); continue }
   if(savedShipIds.size > 1) { flag("\"$label\" mentions tracking numbers of several shipments; not applied"); continue }
   val newNumbers = unsaved
   val preferred = preferredShipmentId?.let { id -> ships.firstOrNull { it.id == id } }
   val savedShip = savedShipIds.singleOrNull()?.let { id -> ships.firstOrNull { it.id == id } }
   val handoffWording = hasCarrierHandoffEvidence(email)
   // null shipment = create a child shipment; each entry lists the new numbers stored on that shipment.
   val plan = mutableListOf<Pair<ShipmentRecord?, List<String>>>()
   when {
    newNumbers.isEmpty() -> {}
    preferred != null -> plan += preferred to newNumbers
    newNumbers.isNotEmpty() && hasSeparatePackageEvidence(email) && savedShip == null && (newNumbers.size > 1 || nums.isNotEmpty()) -> newNumbers.forEach { plan += null to listOf(it) }
    savedShip != null -> plan += savedShip to newNumbers
    ships.isEmpty() && newNumbers.size == 1 -> plan += null to newNumbers
    ships.isEmpty() && handoffWording -> plan += null to newNumbers
    ships.size == 1 && dao.getTrackingNumbersForShipment(ships.single().id).isEmpty() && newNumbers.size == 1 -> plan += ships.single() to newNumbers
    ships.size == 1 && handoffWording -> plan += ships.single() to newNumbers
    newNumbers.size > 1 -> { flag("\"$label\" lists several tracking numbers without wording that shows separate packages or a carrier handoff; not applied"); continue }
    else -> { flag("Tracking number ${newNumbers.first()} from \"$label\" is not tied to an existing shipment by any evidence; not applied"); continue }
   }
   val finding = extractEmailFinding(email, nums, ships)
   val evidenceStatus = ev.shipmentStatus
   val originals = extractTrackingDetails(email).associate { normalizeId(it.first) to it.second }
   val touched = mutableListOf<ShipmentRecord>()
   for((target, numbersToAdd) in plan) {
    var shipment: ShipmentRecord
    if(target == null) {
     val id = dao.insertShipment(ShipmentRecord(orderId = order.id, title = order.marketplace, status = "Awaiting Tracking", statusSource = "gmail-inbox"))
     shipment = dao.getShipments(order.id).first { it.id == id }
     audit("Shipment", "Not available", "Shipment created", email)
    } else shipment = target
    for(number in numbersToAdd) {
     val carrier = detectCarrier(email, number) ?: finding.carrier ?: "Other"
     val hasNumber = dao.getTrackingNumbersForShipment(shipment.id).isNotEmpty()
     dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = shipment.id, number = number, carrier = carrier, role = if(hasNumber) "handoff" else "primary", trackingUrl = trackingLink(carrier, number)))
     if(shipment.carrier.isBlank()) { shipment = shipment.copy(carrier = carrier); dao.updateShipment(shipment) }
     val all = dao.getShipments(order.id)
     val written = originals[normalizeId(number)]?.takeIf { normalizeId(it) != normalizeId(number) || it != number }
     audit(shipmentFieldLabel(if(hasNumber) "Additional tracking number" else "Tracking number", all.indexOfFirst { it.id == shipment.id }, all.size), "Not available",
      if(written != null && written != number) "$number (as written: $written)" else number, email)
    }
    touched += shipment
   }
   if(touched.isEmpty()) {
    val existing = savedShip ?: if(ships.size == 1) ships.single() else null
    if(existing != null) touched += existing
    // A shipped, out-for-delivery or delivered notice can establish a shipment without a visible tracking number; none is invented, and the carrier is stored only when the email states it.
    else if(ships.isEmpty() && evidenceStatus != null && evidenceStatus != "Exception") {
     val id = dao.insertShipment(ShipmentRecord(orderId = order.id, title = order.marketplace, carrier = carrierForTracklessShipment(email).orEmpty(), status = "Awaiting Tracking", statusSource = "gmail-inbox"))
     touched += dao.getShipments(order.id).first { it.id == id }
     audit("Shipment", "Not available", "Shipment created", email)
     }
   }
   val dispatchAt = if(ev.dispatch) parseMailDate(email.date) else null
   if(dispatchAt != null) for(shipment in touched) {
    val current = dao.getShipment(shipment.id) ?: continue
    // An existing or manual date stays; within this pass an earlier dispatch notice may still lower a date this pass set.
    if(current.shippedAt != null && !(gmailShippedAt[current.id] == current.shippedAt && dispatchAt < current.shippedAt!!)) continue
    dao.updateShipment(current.copy(shippedAt = dispatchAt))
    gmailShippedAt[current.id] = dispatchAt
    val all = dao.getShipments(order.id)
    audit(shipmentFieldLabel("Shipped date", all.indexOfFirst { it.id == current.id }, all.size), dateText(current.shippedAt), dateText(dispatchAt), email)
   }
   // A tracking number with no movement evidence is a created label, not a shipped parcel.
   val effectiveStatus = evidenceStatus ?: if(newNumbers.isNotEmpty() && ev.kind != EmailKind.CONFIRMATION) "Label Created" else null
   if(effectiveStatus != null) for(shipment in touched) {
    var active = dao.getShipment(shipment.id) ?: continue
    // A known carrier is never replaced; a shipment with no carrier and no tracking number learns it from the notice.
    if(active.carrier.isBlank() && dao.getTrackingNumbersForShipment(active.id).isEmpty()) carrierForTracklessShipment(email)?.let { named ->
     active = active.copy(carrier = named); dao.updateShipment(active)
     val all = dao.getShipments(order.id)
     audit(shipmentFieldLabel("Shipment carrier", all.indexOfFirst { it.id == active.id }, all.size), "Not available", named, email)
    }
    val emailAt = parseMailDate(email.date)
    if(gmailShipmentStatusApplies(active.status, effectiveStatus, emailAt, statusEvidenceAt[active.id] ?: active.statusUpdatedAt)) {
     if(emailAt != null) statusEvidenceAt[active.id] = maxOf(emailAt, statusEvidenceAt[active.id] ?: emailAt)
     dao.updateShipment(active.copy(status = effectiveStatus, statusSource = "gmail-inbox", statusUpdatedAt = now, deliveredAt = if(effectiveStatus == "Delivered") now else active.deliveredAt))
     val all = dao.getShipments(order.id)
     audit(shipmentFieldLabel("Shipment status", all.indexOfFirst { it.id == active.id }, all.size), active.status, effectiveStatus, email, forwardProgress = true)
     statusAdvanced = true
     statusEmail = email
    }
   }
   // Shipping mail that reached no shipment stays unprocessed so a later scan, fix or manual association can use it.
   if(touched.isEmpty() && isShippingKind(ev.kind)) { flag("\"$label\" reports shipping progress but no single shipment on this order could be identified; not applied"); continue }
   handled += email
  }
  // Without an explicit purchase date, the earliest email actually applied to this order approximates it and is labeled approximate.
  val fallbackDate = handled.mapNotNull { parseMailDate(it.date) }.minOrNull()
  if(fallbackDate != null) {
   val placed = order.placedAt
   val approximateNow = placed != null && dao.latestChangeForField(order.id, "Placed date")?.newValue?.endsWith(APPROXIMATE_DATE_SUFFIX) == true
   if(placed == null || (approximateNow && fallbackDate < placed)) {
    audit("Placed date", dateText(placed) + if(approximateNow) APPROXIMATE_DATE_SUFFIX else "", dateText(fallbackDate) + APPROXIMATE_DATE_SUFFIX,
     handled.firstOrNull { parseMailDate(it.date) == fallbackDate }, force = true)
    order = order.copy(placedAt = fallbackDate)
   }
  }
  // Retailer notices move Placed to Processing, or Placed/Processing to Cancelled while no shipment has moved; a manual status is never replaced.
  val orderNotices = handled.mapNotNull { e -> orderStatusNotice(e, buildEvidence(e).kind)?.let { it to e } }
  if(!order.archived && orderNotices.isNotEmpty() && mayDeriveOrderStatus(dao.latestChangeForField(order.id, "Order status"))) {
   val moved = dao.getShipments(order.id).any { it.status != "Awaiting Tracking" && it.status != "Label Created" }
   val cancel = orderNotices.firstOrNull { it.first == "Cancelled" }
   val processing = orderNotices.firstOrNull { it.first == "Processing" }
   val notice = when {
    cancel != null && !moved && (order.status == "Order Placed" || order.status == "Processing" || order.status == PREPARING_FOR_SHIPMENT) -> cancel
    processing != null && order.status == "Order Placed" -> processing
    else -> null
   }
   if(notice != null) {
    audit("Order status", order.status, notice.first, notice.second, forwardProgress = true)
    order = order.copy(status = notice.first, statusUpdatedAt = now)
   }
  }
  // Order status follows persisted shipment status only when this scan advanced a shipment, and never
  // replaces a status the user chose deliberately (latest "Order status" history row has manual provenance).
  if(!order.archived && statusAdvanced && mayDeriveOrderStatus(dao.latestChangeForField(order.id, "Order status"))) {
   val next = derivedStatusFor(order.id, order.status)
   if(next != order.status) {
    audit("Order status", order.status, next, statusEmail, forwardProgress = true)
    order = order.copy(status = next, statusUpdatedAt = now)
   }
  }
  dao.updateOrder(order.copy(lastSyncedAt = now))
  handoffIfReady(order.id, now, record = recordHistory)?.let { row ->
   applied += ScanFieldChange(row.id, row.field, row.oldValue, row.newValue, ChangeKind.NEW_VALUE, "Shipment handoff", "", now, row.sourceMessageId)
  }
  // Markers are written in the same transaction as the data they describe; messages left for review are not marked.
  handled.filter { it.messageId.isNotBlank() }.forEach { email ->
   dao.markProcessed(ProcessedGmailMessage(messageId = email.messageId, orderId = order.id, processedAt = now, extractionVersion = markerVersion))
   dao.upgradeProcessedVersion(email.messageId, order.id, markerVersion, now)
  }
  return OrderTx(applied, review, reviewEmails)
 }
 // --- Needs Review: confirmed associations (remembered), duplicate merge, retailer aliases ---
 internal suspend fun previewAssociation(email: GmailPreview, orderId: Long, shipmentId: Long? = null): AssociationPreview = withContext(Dispatchers.IO) {
  val ev = buildEvidence(email)
  val order = dao.getOrder(orderId)
  val ownIds = dao.getShipments(orderId).map { it.id }.toSet()
  val saved = dao.allTrackingNumbers()
  val foreign = ev.trackingCandidates.firstOrNull { c -> saved.any { normalizeId(it.number) == normalizeId(c) && it.shipmentId !in ownIds && dao.getShipment(it.shipmentId)?.orderId != null } }
  val conflict = when {
   order == null -> "This order no longer exists."
   email.messageId.isBlank() -> "This email has no Gmail message ID, so an association could not be remembered."
   dao.markersForMessage(email.messageId).isNotEmpty() -> "This email is already associated with an order."
   shipmentId != null && shipmentId !in ownIds -> "That shipment does not belong to this order."
   foreign != null -> "Tracking number $foreign already belongs to a shipment of another order."
   else -> null
  }
  AssociationPreview(ev.trackingCandidates, detectCarrier(email, ev.trackingCandidates.firstOrNull()), statusForEmail(email, ev.kind), conflict)
 }
 internal fun associateEmail(email: GmailPreview, orderId: Long, shipmentId: Long?, onDone: (SaveOutcome) -> Unit = {}) = viewModelScope.launch {
  val outcome = associateEmailWithOrder(email, orderId, shipmentId)
  report(outcome)
  onDone(outcome)
 }
 /** Applies one email to an order the user chose and remembers the choice (marker with MANUAL_ASSOCIATION_VERSION). All or nothing. */
 internal suspend fun associateEmailWithOrder(email: GmailPreview, orderId: Long, shipmentId: Long? = null): SaveOutcome = withContext(Dispatchers.IO) {
  try {
   updateMutex.withLock { withContext(NonCancellable) { db.withTransaction {
    val order = dao.getOrder(orderId) ?: return@withTransaction SaveOutcome.Blocked("This order no longer exists.")
    if(email.messageId.isBlank()) return@withTransaction SaveOutcome.Blocked("This email has no Gmail message ID, so the association could not be remembered.")
    if(dao.markersForMessage(email.messageId).isNotEmpty()) return@withTransaction SaveOutcome.Blocked("This email is already associated with an order.")
    if(shipmentId != null && dao.getShipment(shipmentId)?.orderId != orderId) return@withTransaction SaveOutcome.Blocked("That shipment does not belong to this order.")
    dao.recordChange(order.id, "Email associated", "Not available", email.subject.take(80).ifBlank { "(No subject)" }, email.messageId, email.date)
    val tx = applyAssignedEmails(order, listOf(email), reanalyze = true, recordHistory = true, preferredShipmentId = shipmentId, markerVersion = MANUAL_ASSOCIATION_VERSION)
    tx.review.firstOrNull()?.let { throw ReviewAbort(it) }
    SaveOutcome.Saved
   } } }
  } catch(e: ReviewAbort) { SaveOutcome.Blocked("Nothing was changed: ${e.message}. The email was not used and stays in Needs Review.") }
  catch(e: kotlinx.coroutines.CancellationException) { throw e }
  catch(e: Exception) { SaveOutcome.Blocked("The association failed and was rolled back: ${e.localizedMessage ?: "Unknown error"}") }
 }
 internal fun merge(primaryId: Long, secondaryId: Long, rememberAlias: Boolean, onDone: (SaveOutcome) -> Unit = {}) = viewModelScope.launch {
  val outcome = mergeOrders(primaryId, secondaryId, rememberAlias)
  report(outcome)
  onDone(outcome)
 }
 internal fun createOrderForEmail(email: GmailPreview, retailer: String, orderNumber: String, title: String, onDone: (SaveOutcome) -> Unit = {}) = viewModelScope.launch {
  val outcome = createOrderFromEmail(email, retailer, orderNumber, title)
  report(outcome)
  onDone(outcome)
 }
 /** Manual fallback when no saved order exists: the user confirms retailer, title and order number, then the email is applied and remembered. */
 internal suspend fun createOrderFromEmail(email: GmailPreview, retailer: String, orderNumber: String, title: String): SaveOutcome = withContext(Dispatchers.IO) {
  val shop = retailer.trim(); val number = orderNumber.trim()
  if(shop.isEmpty() || number.isEmpty()) return@withContext SaveOutcome.Blocked("Enter a retailer and an order number.")
  if(email.messageId.isBlank()) return@withContext SaveOutcome.Blocked("This email has no Gmail message ID, so the association could not be remembered.")
  val aliases = aliasBook()
  try {
   updateMutex.withLock { withContext(NonCancellable) { db.withTransaction {
    if(dao.markersForMessage(email.messageId).isNotEmpty()) return@withTransaction SaveOutcome.Blocked("This email is already associated with an order.")
    if(dao.allOrders().any { aliases.same(it.marketplace, shop) && normalizeId(it.orderNumber) == normalizeId(number) })
     return@withTransaction SaveOutcome.Blocked("An order for this retailer and order number already exists; choose it from the list instead.")
    val base = OrderRecord(marketplace = shop, orderNumber = number, name = title.trim().ifBlank { shop }, status = "Order Placed")
     .let { it.copy(id = dao.insertOrder(it)) }
    val tx = applyAssignedEmails(base, listOf(email), reanalyze = true, recordHistory = false, markerVersion = MANUAL_ASSOCIATION_VERSION)
    tx.review.firstOrNull()?.let { throw ReviewAbort(it) }
    SaveOutcome.Saved
   } } }
  } catch(e: ReviewAbort) { SaveOutcome.Blocked("Nothing was created: ${e.message}. The email was not used and stays in Needs Review.") }
  catch(e: kotlinx.coroutines.CancellationException) { throw e }
  catch(e: Exception) { SaveOutcome.Blocked("Creating the order failed and was rolled back: ${e.localizedMessage ?: "Unknown error"}") }
 }
 /**
  * Merge is not association: it combines two records of the same purchase. Everything related (items, shipments, tracking numbers,
  * events, history, processed markers) moves to the surviving order; the surviving order's own values are never overwritten.
  */
 internal suspend fun mergeOrders(primaryId: Long, secondaryId: Long, rememberAlias: Boolean = false): SaveOutcome = withContext(Dispatchers.IO) {
  var aliasPair: Set<String>? = null
  val aliases = aliasBook()
  val outcome = try {
   updateMutex.withLock { withContext(NonCancellable) { db.withTransaction {
    if(primaryId == secondaryId) return@withTransaction SaveOutcome.Blocked("Choose two different orders.")
    val p = dao.getOrder(primaryId) ?: return@withTransaction SaveOutcome.Blocked("An order no longer exists.")
    val s = dao.getOrder(secondaryId) ?: return@withTransaction SaveOutcome.Blocked("An order no longer exists.")
    if(normalizeId(p.orderNumber) != normalizeId(s.orderNumber)) return@withTransaction SaveOutcome.Blocked("Only orders with the same order number can be merged.")
    val now = System.currentTimeMillis()
    val primaryItems = dao.getItems(p.id)
    dao.getItems(s.id).filter { item -> primaryItems.any { it.name.equals(item.name, true) && it.quantity == item.quantity && it.priceMinor == item.priceMinor } }.forEach { dao.deleteItem(it.id) }
    dao.moveItems(s.id, p.id)
    val primaryShips = dao.getShipments(p.id)
    val primaryNumbers = primaryShips.associate { it.id to dao.getTrackingNumbersForShipment(it.id) }
    dao.getShipments(s.id).forEach { ss ->
     val nums = dao.getTrackingNumbersForShipment(ss.id)
     val twin = primaryShips.firstOrNull { ps -> primaryNumbers[ps.id].orEmpty().any { pn -> nums.any { normalizeId(it.number) == normalizeId(pn.number) } } }
     if(twin == null) dao.updateShipment(ss.copy(orderId = p.id))
     else {
      // The same parcel is recorded twice: keep one shipment and carry over any number or event only the other had.
      nums.filter { n -> primaryNumbers[twin.id].orEmpty().none { normalizeId(it.number) == normalizeId(n.number) } }.forEach { dao.updateTrackingNumber(it.copy(shipmentId = twin.id, role = "handoff")) }
      dao.moveEvents(ss.id, twin.id)
      dao.deleteShipment(ss.id)
     }
    }
    dao.moveHistory(s.id, p.id)
    dao.moveMarkers(s.id, p.id)
    var merged = p.copy(
     placedAt = p.placedAt ?: s.placedAt, orderUrl = p.orderUrl.ifBlank { s.orderUrl },
     notes = when { p.notes.isBlank() -> s.notes; s.notes.isBlank() || s.notes in p.notes -> p.notes; else -> p.notes + " \u00b7 " + s.notes.take(300) },
     archived = p.archived && s.archived, archivedAt = if(p.archived && s.archived) p.archivedAt ?: s.archivedAt else null,
     lastSyncedAt = listOfNotNull(p.lastSyncedAt, s.lastSyncedAt).maxOrNull())
    dao.recordChange(p.id, "Merged duplicate order", "Not available", "${s.marketplace} #${s.orderNumber}", SOURCE_MANUAL, "", now)
    dao.recordChange(p.id, "Placed date", dateText(p.placedAt), dateText(merged.placedAt), SOURCE_MANUAL, "", now)
    dao.recordChange(p.id, "Order URL", p.orderUrl, merged.orderUrl, SOURCE_MANUAL, "", now)
    dao.recordChange(p.id, "Notes", p.notes, merged.notes, SOURCE_MANUAL, "", now)
    // A deliberately chosen order status is kept; otherwise the roll-up may advance with the moved shipments.
    if(mayDeriveOrderStatus(dao.latestChangeForField(p.id, "Order status"))) {
     val next = derivedStatusFor(p.id, merged.status)
     if(next != merged.status) {
      dao.recordChange(p.id, "Order status", merged.status, next, SOURCE_DERIVED, "", now)
      merged = merged.copy(status = next, statusUpdatedAt = now)
     }
    }
    dao.updateOrder(merged)
    // Different retailer names that are not a known alias: remember the removal so Gmail does not recreate the merged-away order.
    if(!aliases.same(p.marketplace, s.marketplace)) dao.markDeleted(DeletedOrderMarker(marketplace = s.marketplace, orderNumber = s.orderNumber))
    dao.deleteOrder(s.id)
    if(rememberAlias && !aliases.same(p.marketplace, s.marketplace)) aliasPair = setOf(p.marketplace, s.marketplace)
    SaveOutcome.Saved
   } } }
  } catch(e: kotlinx.coroutines.CancellationException) { throw e }
  catch(e: Exception) { SaveOutcome.Blocked("The merge failed and was rolled back: ${e.localizedMessage ?: "Unknown error"}") }
  aliasPair?.let { preferences.addAliasGroup(it) }
  outcome
 }
 internal suspend fun declareRetailerAlias(first: String, second: String) { if(first.isNotBlank() && second.isNotBlank()) preferences.addAliasGroup(setOf(first.trim(), second.trim())) }
 internal suspend fun retailerAliasGroups(): List<Set<String>> = aliasBook().groups
 internal suspend fun associationMarkers(): List<ProcessedGmailMessage> = withContext(Dispatchers.IO) { dao.allProcessed() }
 internal suspend fun dismissedEmailIds(): Set<String> = preferences.dismissedGmailMessageIds()
 internal suspend fun filterDismissedEmails(emails: List<GmailPreview>): List<GmailPreview> {
  val dismissed = preferences.dismissedGmailMessageIds()
  return emails.filterNot { it.messageId in dismissed }
 }
 internal suspend fun dismissEmailFromReview(email: GmailPreview): SaveOutcome = withContext(Dispatchers.IO) {
  if(email.messageId.isBlank()) return@withContext SaveOutcome.Blocked("This email has no Gmail message ID, so it cannot be excluded from later scans.")
  try {
   updateMutex.withLock {
    if(dao.markersForMessage(email.messageId).isNotEmpty()) return@withLock SaveOutcome.Blocked("This email is already associated with an order.")
    preferences.dismissGmailMessage(email.messageId)
    SaveOutcome.Saved
   }
  } catch(e: kotlinx.coroutines.CancellationException) { throw e }
  catch(e: Exception) { SaveOutcome.Blocked("The email could not be removed from review: ${e.localizedMessage ?: "Unknown error"}") }
 }
 private fun report(outcome: SaveOutcome) { notice.value = (outcome as? SaveOutcome.Blocked)?.message.orEmpty() }
 private fun duplicateNumberMessage(number: String) = "Tracking number ${number.trim()} already belongs to another shipment, so nothing was saved."
 // Manual edits read fresh rows inside one transaction (never UI snapshots) and record only meaningful changes.
 private suspend fun trackingElsewhere(number: String, ownShipmentId: Long?): Boolean =
  dao.allTrackingNumbers().any { normalizeId(it.number) == normalizeId(number) && it.shipmentId != ownShipmentId }
 fun saveStandalone(draft: ShipmentDraft, onDone: () -> Unit) = viewModelScope.launch {
  val outcome = createStandalone(draft)
  report(outcome)
  if(outcome == SaveOutcome.Saved) onDone()
 }
 internal suspend fun createStandalone(draft: ShipmentDraft): SaveOutcome {
  if(draft.title.isBlank() || draft.number.isBlank() || draft.carrier.isBlank() || !draft.confirmed) return SaveOutcome.Blocked("Enter a title and tracking number, then confirm the carrier.")
  return db.withTransaction {
   if(trackingElsewhere(draft.number, null)) return@withTransaction SaveOutcome.Blocked(duplicateNumberMessage(draft.number))
   val id = dao.insertShipment(ShipmentRecord(orderId = null, status = draft.status, title = draft.title.trim(), carrier = draft.carrier, shippedAt = parseDate(draft.shippedDate)))
   dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = id, number = draft.number.trim(), carrier = draft.carrier, trackingUrl = trackingLink(draft.carrier, draft.number)))
   SaveOutcome.Saved
  }
 }
 fun updateTracking(shipment: ShipmentRecord, tracking: ShipmentTrackingNumberRecord?, number: String, carrier: String, estimated: Long?, shippedAt: Long?) = viewModelScope.launch {
  report(editShipment(shipment.id, tracking?.id, number, carrier, estimated, shippedAt, changeShippedAt = true))
 }
 internal suspend fun editShipment(shipmentId: Long, trackingId: Long?, number: String, carrier: String, estimated: Long?, shippedAt: Long? = null, changeShippedAt: Boolean = false): SaveOutcome = db.withTransaction {
  val ship = dao.getShipment(shipmentId) ?: return@withTransaction SaveOutcome.Blocked("This shipment no longer exists.")
  val newNumber = number.trim()
  if(carrier.isBlank()) return@withTransaction SaveOutcome.Blocked("Enter a tracking number and carrier.")
  // A carrier alone is saved only for a shipment that has no tracking number yet.
  if(newNumber.isEmpty() && dao.getTrackingNumbersForShipment(shipmentId).isNotEmpty()) return@withTransaction SaveOutcome.Blocked("Enter a tracking number and carrier.")
  if(newNumber.isNotEmpty() && trackingElsewhere(newNumber, shipmentId)) return@withTransaction SaveOutcome.Blocked(duplicateNumberMessage(newNumber))
  val now = System.currentTimeMillis()
  val siblings = ship.orderId?.let { dao.getShipments(it) }.orEmpty()
  val position = siblings.indexOfFirst { it.id == ship.id }
  suspend fun log(base: String, before: String, after: String) { ship.orderId?.let { dao.recordChange(it, shipmentFieldLabel(base, position, siblings.size), before, after, SOURCE_MANUAL, "", now) } }
  if(ship.estimatedDeliveryAt != estimated) {
   dao.updateShipment(ship.copy(estimatedDeliveryAt = estimated))
   log("Estimated delivery", dateText(ship.estimatedDeliveryAt), dateText(estimated))
  }
  if(changeShippedAt && ship.shippedAt != shippedAt) {
   dao.getShipment(shipmentId)?.let { dao.updateShipment(it.copy(shippedAt = shippedAt)) }
   log("Shipped date", dateText(ship.shippedAt), dateText(shippedAt))
  }
  val current = trackingId?.let { id -> dao.getTrackingNumbersForShipment(shipmentId).firstOrNull { it.id == id } }
  if(newNumber.isEmpty()) {
   if(ship.carrier != carrier) {
    dao.getShipment(shipmentId)?.let { dao.updateShipment(it.copy(carrier = carrier)) }
    log("Shipment carrier", ship.carrier.ifBlank { "Not available" }, carrier)
   }
  } else if(current == null) {
   dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = shipmentId, number = newNumber, carrier = carrier, trackingUrl = trackingLink(carrier, newNumber)))
   log("Tracking number", "Not available", newNumber)
  } else {
   val revised = current.copy(number = newNumber, carrier = carrier, trackingUrl = trackingLink(carrier, newNumber))
   if(revised != current) dao.updateTrackingNumber(revised)
   if(normalizeId(current.number) != normalizeId(newNumber)) log("Tracking number", current.number, newNumber)
   log("Tracking carrier", current.carrier, carrier)
  }
  dao.getShipment(shipmentId)?.let { current ->
   if(current.carrier != carrier) dao.updateShipment(current.copy(carrier = carrier))
   current.orderId?.let { handoffIfReady(it) }
  }
  SaveOutcome.Saved
 }
 fun addHandoff(shipmentId: Long, number: String, carrier: String) = viewModelScope.launch { report(addTrackingNumber(shipmentId, number, carrier)) }
 internal suspend fun addTrackingNumber(shipmentId: Long, number: String, carrier: String): SaveOutcome = db.withTransaction {
  val newNumber = number.trim()
  if(newNumber.isEmpty() || carrier.isBlank()) return@withTransaction SaveOutcome.Blocked("Enter a tracking number and carrier.")
  val ship = dao.getShipment(shipmentId) ?: return@withTransaction SaveOutcome.Blocked("This shipment no longer exists.")
  val all = dao.allTrackingNumbers()
  val owner = all.firstOrNull { normalizeId(it.number) == normalizeId(newNumber) }
  if(owner != null) return@withTransaction if(owner.shipmentId == shipmentId) SaveOutcome.Saved else SaveOutcome.Blocked(duplicateNumberMessage(newNumber))
  val hasNumber = all.any { it.shipmentId == shipmentId }
  dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = shipmentId, number = newNumber, carrier = carrier, role = if(hasNumber) "handoff" else "primary", trackingUrl = trackingLink(carrier, newNumber)))
  ship.orderId?.let { orderId ->
   val siblings = dao.getShipments(orderId)
   dao.recordChange(orderId, shipmentFieldLabel(if(hasNumber) "Additional tracking number" else "Tracking number", siblings.indexOfFirst { it.id == ship.id }, siblings.size), "Not available", newNumber, SOURCE_MANUAL)
   handoffIfReady(orderId)
  }
  SaveOutcome.Saved
 }
 fun removeTrackingNumber(tracking: ShipmentTrackingNumberRecord) = viewModelScope.launch { report(removeTracking(tracking)) }
 internal suspend fun removeTracking(tracking: ShipmentTrackingNumberRecord): SaveOutcome = db.withTransaction {
  val here = dao.getTrackingNumbersForShipment(tracking.shipmentId)
  val current = here.firstOrNull { it.id == tracking.id } ?: return@withTransaction SaveOutcome.Saved
  // Preserve the last identifier; removing it would make the shipment untrackable.
  if(here.size <= 1) return@withTransaction SaveOutcome.Blocked("A shipment must keep at least one tracking number.")
  dao.deleteTrackingNumber(current.id)
  dao.getShipment(current.shipmentId)?.orderId?.let { dao.recordChange(it, "Removed tracking number", current.number, "Not available", SOURCE_MANUAL) }
  SaveOutcome.Saved
 }
 fun linkShipment(shipment: ShipmentRecord, order: OrderRecord) = viewModelScope.launch { report(linkShipmentToOrder(shipment.id, order.id)) }
 internal suspend fun linkShipmentToOrder(shipmentId: Long, orderId: Long): SaveOutcome = db.withTransaction {
  val ship = dao.getShipment(shipmentId) ?: return@withTransaction SaveOutcome.Blocked("This shipment no longer exists.")
  val order = dao.getOrder(orderId) ?: return@withTransaction SaveOutcome.Blocked("This order no longer exists.")
  if(ship.orderId == order.id) return@withTransaction SaveOutcome.Saved
  if(ship.orderId != null) return@withTransaction SaveOutcome.Blocked("This shipment is already linked to another order.")
  val now = System.currentTimeMillis()
  dao.updateShipment(ship.copy(orderId = order.id))
  val label = ship.title.ifBlank { dao.getTrackingNumbersForShipment(ship.id).firstOrNull()?.number ?: "Shipment #${ship.id}" }
  dao.recordChange(order.id, "Shipment linked", "Not available", label, SOURCE_MANUAL, "", now)
  settleOrder(order.id, now)
  SaveOutcome.Saved
 }
 fun addManualEvent(shipmentId: Long, description: String, location: String) = viewModelScope.launch {
  if(description.isNotBlank()) dao.insertEvent(ShipmentTrackingEventRecord(shipmentId = shipmentId, description = description.trim(), location = location.trim(), source = "manual"))
 }
 /**
  * Runs inside the caller's transaction. Handoff needs BOTH an order at Shipped AND at least one saved, associated shipment that carries
  * a tracking number or carrier; otherwise the order stays active. Archiving never touches the shipments.
  */
 private suspend fun handoffIfReady(orderId: Long, now: Long = System.currentTimeMillis(), record: Boolean = true): OrderChangeHistory? {
  val order = dao.getOrder(orderId) ?: return null
  if(order.archived || order.status != "Shipped") return null
  val ships = dao.getShipments(orderId)
  val numbers = ships.flatMap { dao.getTrackingNumbersForShipment(it.id) }
  if(ships.none { hasShipmentInformation(it, numbers) }) return null
  dao.archiveOrder(orderId, true, now)
  return if(record) dao.recordChange(orderId, "Order archive", "Active", "Archived after shipment handoff", SOURCE_DERIVED, "", now) else null
 }
 /** Runs inside the caller's transaction: Preparing for Shipment follows a label or tracking number, Shipped follows shipment movement, then handoff is evaluated. */
 private suspend fun derivedStatusFor(orderId: Long, current: String): String {
  val ships = dao.getShipments(orderId)
  val hasTracking = ships.any { s -> dao.getTrackingNumbersForShipment(s.id).any { it.number.isNotBlank() } }
  return derivedOrderStatus(current, ships.map { it.status }, hasTracking)
 }
 private suspend fun settleOrder(orderId: Long, now: Long = System.currentTimeMillis(), respectManual: Boolean = false) {
  val order = dao.getOrder(orderId) ?: return
  if(order.archived) return
  if(!respectManual || mayDeriveOrderStatus(dao.latestChangeForField(orderId, "Order status"))) {
   val next = derivedStatusFor(orderId, order.status)
   if(next != order.status) {
    dao.updateOrder(order.copy(status = next, statusUpdatedAt = now))
    dao.recordChange(orderId, "Order status", order.status, next, SOURCE_DERIVED, "", now)
   }
  }
  handoffIfReady(orderId, now)
 }
 private fun purchaseDelivered(order: OrderRecord, ships: List<ShipmentRecord>) =
  order.status == "Delivered" || (ships.isNotEmpty() && ships.all { canonicalShipmentStatus(it.status) == "Delivered" })
 /** Repairs existing eligible records too; no status derivation or deletion happens here. */
 internal suspend fun reconcileArchives() = db.withTransaction {
  dao.allOrders().filter { !it.archived && it.status == "Shipped" }.forEach { handoffIfReady(it.id) }
  dao.archiveDeliveredShipments()
  Unit
 }
 fun archiveStandalone(shipment: ShipmentRecord, archived: Boolean) = viewModelScope.launch { dao.archiveShipment(shipment.id, archived) }
 fun archiveDue(days: Int) = viewModelScope.launch {
  val cutoff = System.currentTimeMillis() - days.coerceAtLeast(1).toLong() * 86_400_000L
  val allShipments = shipments.value
  orders.value.filter { !it.archived }.forEach { order ->
   val ships = allShipments.filter { it.orderId == order.id }
   val deliveredAt = if(order.status == "Delivered") order.statusUpdatedAt else ships.maxOfOrNull { it.deliveredAt ?: it.statusUpdatedAt }
   if(purchaseDelivered(order, ships) && deliveredAt != null && deliveredAt <= cutoff) dao.archiveOrder(order.id, true)
  }
  allShipments.filter { !it.archived && canonicalShipmentStatus(it.status) == "Delivered" && (it.deliveredAt ?: it.statusUpdatedAt) <= cutoff }.forEach { dao.archiveShipment(it.id, true) }
 }
 // Messages that created or updated the shipment are excluded from later scans so a deleted shipment is not recreated.
 fun deleteStandalone(id: Long) = viewModelScope.launch {
  deleteShipmentRecord(id)
 }
 internal suspend fun deleteShipmentRecord(id: Long) {
  dao.eventMessageIds(id).distinct().forEach { preferences.dismissGmailMessage(it) }
  db.withTransaction { dao.deleteShipment(id) }
 }
 internal suspend fun deleteArchived(snapshot: ArchiveSelection) = db.withTransaction {
  // The confirmed IDs are a ceiling. Rows restored since confirmation are preserved.
  snapshot.shipmentIds.forEach { id -> if(dao.getShipment(id)?.archived == true) dao.deleteShipment(id) }
  snapshot.orderIds.forEach { id -> dao.getOrder(id)?.takeIf { it.archived }?.let { order ->
   dao.markDeleted(DeletedOrderMarker(marketplace = order.marketplace, orderNumber = order.orderNumber))
   // Every remaining child is outside the confirmed shipment deletion scope.
   dao.detachShipments(id)
   dao.deleteOrder(id)
  } }
  snapshot.legacyIds.forEach { id -> if(db.dao().get(id)?.archived == true) db.dao().delete(id) }
 }
 internal fun deleteArchiveSelection(snapshot: ArchiveSelection) = viewModelScope.launch {
  runCatching { deleteArchived(snapshot) }.onFailure { notice.value = "Archive deletion failed; no records were deleted." }
 }
 fun archive(order: OrderRecord, value: Boolean) = viewModelScope.launch { dao.archiveOrder(order.id, value) }
 fun archiveAll() = viewModelScope.launch { orders.value.filter { !it.archived && purchaseDelivered(it, shipments.value.filter { s -> s.orderId == it.id }) }.forEach { dao.archiveOrder(it.id, true) } }
 fun delete(order: OrderRecord) = viewModelScope.launch { db.withTransaction {
  val current = dao.getOrder(order.id) ?: return@withTransaction
  dao.markDeleted(DeletedOrderMarker(marketplace = current.marketplace, orderNumber = current.orderNumber))
  if(current.archived) dao.detachActiveShipments(current.id)
  dao.deleteOrder(current.id)
 } }
 /** A status the user picks is recorded with manual provenance; Gmail will not advance it afterwards. */
 fun updateStatus(order: OrderRecord, status: String) = viewModelScope.launch { report(setOrderStatus(order.id, status)) }
 internal suspend fun setOrderStatus(orderId: Long, status: String): SaveOutcome = db.withTransaction {
  val order = dao.getOrder(orderId) ?: return@withTransaction SaveOutcome.Blocked("This order no longer exists.")
  if(order.status == status) { handoffIfReady(orderId); return@withTransaction SaveOutcome.Saved }
  val now = System.currentTimeMillis()
  dao.updateOrder(order.copy(status = status, statusUpdatedAt = now))
  dao.recordChange(orderId, "Order status", order.status, status, SOURCE_MANUAL, "", now)
  handoffIfReady(orderId, now)
  SaveOutcome.Saved
 }
 fun updateShipment(shipment: ShipmentRecord, status: String) = viewModelScope.launch { report(setShipmentStatus(shipment.id, status)) }
 internal suspend fun setShipmentStatus(shipmentId: Long, status: String): SaveOutcome = db.withTransaction {
  val ship = dao.getShipment(shipmentId) ?: return@withTransaction SaveOutcome.Blocked("This shipment no longer exists.")
  if(ship.status == status) { dao.updateShipment(ship); ship.orderId?.let { handoffIfReady(it) }; return@withTransaction SaveOutcome.Saved }
  val now = System.currentTimeMillis()
  dao.updateShipment(ship.copy(status = status, statusSource = "manual", statusUpdatedAt = now, deliveredAt = if(status == "Delivered") now else null))
  dao.insertEvent(ShipmentTrackingEventRecord(shipmentId = ship.id, description = "Status manually set to $status", status = status, source = "manual"))
  val orderId = ship.orderId ?: return@withTransaction SaveOutcome.Saved
  val order = dao.getOrder(orderId) ?: return@withTransaction SaveOutcome.Saved
  val all = dao.getShipments(orderId)
  dao.recordChange(orderId, shipmentFieldLabel("Shipment status", all.indexOfFirst { it.id == ship.id }, all.size), ship.status, status, SOURCE_MANUAL, "", now)
  // Manual shipment edits move the order to Shipped; delivery stays a shipment status and a Cancelled order is untouched.
  settleOrder(orderId, now)
  SaveOutcome.Saved
 }
 fun addShipment(order: OrderRecord, draft: ShipmentDraft) = viewModelScope.launch { report(addShipmentToOrder(order.id, draft)) }
 internal suspend fun addShipmentToOrder(orderId: Long, draft: ShipmentDraft): SaveOutcome = db.withTransaction {
  val order = dao.getOrder(orderId) ?: return@withTransaction SaveOutcome.Blocked("This order no longer exists.")
  if(draft.number.isNotBlank() && trackingElsewhere(draft.number, null)) return@withTransaction SaveOutcome.Blocked(duplicateNumberMessage(draft.number))
  val now = System.currentTimeMillis()
  // A Shipped order whose only shipment is empty (no tracking number or carrier) receives the entered details instead of a second, empty record.
  val orderShipments = dao.getShipments(order.id)
  val orderNumbers = orderShipments.flatMap { dao.getTrackingNumbersForShipment(it.id) }
  val empty = orderShipments.singleOrNull()?.takeIf { !hasShipmentInformation(it, orderNumbers) }
  val shippedAt = parseDate(draft.shippedDate)
  val sid = if(empty != null) {
   dao.updateShipment(empty.copy(status = draft.status, title = draft.title.trim().ifBlank { empty.title }, carrier = draft.carrier.ifBlank { empty.carrier }, shippedAt = shippedAt ?: empty.shippedAt,
    statusSource = "manual", statusUpdatedAt = now, deliveredAt = if(draft.status == "Delivered") now else empty.deliveredAt))
   empty.id
  } else dao.insertShipment(ShipmentRecord(orderId = order.id, status = draft.status, title = draft.title.trim(), carrier = draft.carrier, shippedAt = shippedAt, deliveredAt = if(draft.status == "Delivered") now else null))
  val all = dao.getShipments(order.id)
  dao.recordChange(order.id, "Shipment", "Not available", "Shipment added", SOURCE_MANUAL, "", now)
  if(draft.number.isNotBlank()) {
   dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = sid, number = draft.number.trim(), carrier = draft.carrier, trackingUrl = trackingLink(draft.carrier, draft.number)))
   dao.recordChange(order.id, shipmentFieldLabel("Tracking number", all.indexOfFirst { it.id == sid }, all.size), "Not available", draft.number.trim(), SOURCE_MANUAL, "", now)
  }
  settleOrder(order.id, now)
  SaveOutcome.Saved
 }
 fun legacyArchive(pkg: TrackedPackage, value: Boolean) = viewModelScope.launch { db.dao().archive(pkg.id, value) }
}

class MainActivity: ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val db = PackageDatabase.get(applicationContext); val prefs = SettingsRepository(applicationContext)
  StorageMaintenance.schedule(applicationContext)
  lifecycleScope.launch { prefs.settings.collect { setting -> BackgroundSync.schedule(applicationContext, setting.refreshHours); StorageMaintenance.deleteExpired(applicationContext) } }
  setContent { val vm: TrackerVm = viewModel(factory = object: ViewModelProvider.Factory {
   @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T = TrackerVm(db, prefs) as T
  }); val s by vm.settings.collectAsState()
   val dark = when(s.appearance) { "Light" -> false; "System" -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES; else -> true }
   MaterialTheme(colorScheme = if(dark) darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFBBA3FF)) else lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF6844A4))) { AppScreen(vm, s) }
  }
 }
}

data class ProductDraft(val name: String = "", val qty: String = "1", val price: String = "")
data class ShipmentDraft(val title: String = "", val number: String = "", val carrier: String = "", val status: String = "Awaiting Tracking", val confirmed: Boolean = false, val shippedDate: String = "")
data class OrderDraft(val mode: String = "Order", val standalone: ShipmentDraft = ShipmentDraft(), val editId: Long = 0, val market: String = "", val name: String = "", val number: String = "", val date: String = "", val status: String = "Order Placed", val url: String = "", val notes: String = "", val products: List<ProductDraft> = emptyList(), val shipments: List<ShipmentDraft> = emptyList())


private fun encodeDraft(d: OrderDraft): String = JSONObject().apply {
 put("mode", d.mode); put("editId", d.editId); put("market", d.market); put("name", d.name); put("number", d.number)
 put("date", d.date); put("status", d.status); put("url", d.url); put("notes", d.notes)
 fun shipmentJson(v: ShipmentDraft) = JSONObject().apply { put("title", v.title); put("number", v.number); put("carrier", v.carrier); put("status", v.status); put("confirmed", v.confirmed); put("shippedDate", v.shippedDate) }
 put("standalone", shipmentJson(d.standalone))
 put("shipments", JSONArray().apply { d.shipments.forEach { put(shipmentJson(it)) } })
 put("products", JSONArray().apply { d.products.forEach { put(JSONObject().apply { put("name", it.name); put("qty", it.qty); put("price", it.price) }) } })
}.toString()
private fun decodeDraft(raw: String?): OrderDraft = runCatching {
 val j = JSONObject(raw ?: "{}")
 fun shipment(o: JSONObject) = ShipmentDraft(title = o.optString("title"), number = o.optString("number"), carrier = o.optString("carrier"), status = o.optString("status", "Awaiting Tracking"), confirmed = o.optBoolean("confirmed"), shippedDate = o.optString("shippedDate"))
 val ships = j.optJSONArray("shipments") ?: JSONArray()
 val products = j.optJSONArray("products") ?: JSONArray()
 OrderDraft(mode = j.optString("mode", "Order"), editId = j.optLong("editId"), market = j.optString("market"), name = j.optString("name"), number = j.optString("number"), date = j.optString("date"), status = j.optString("status", "Order Placed"), url = j.optString("url"), notes = j.optString("notes"), standalone = shipment(j.optJSONObject("standalone") ?: JSONObject()),
  shipments = (0 until ships.length()).map { shipment(ships.getJSONObject(it)) }, products = (0 until products.length()).map { val p = products.getJSONObject(it); ProductDraft(p.optString("name"), p.optString("qty", "1"), p.optString("price")) })
}.getOrDefault(OrderDraft())

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppScreen(vm: TrackerVm, settings: TrackerSettings) {
 val draftContext = LocalContext.current
 val draftPrefs = remember(draftContext) { draftContext.getSharedPreferences("order_draft", android.content.Context.MODE_PRIVATE) }
 var tab by remember { mutableIntStateOf(0) }; var draft by remember { mutableStateOf(decodeDraft(draftPrefs.getString("current", null))) }
 var editBaseline by remember { mutableStateOf<OrderDraft?>(null) }
 var confirmDiscardEdit by remember { mutableStateOf(false) }
 fun leaveEdit() {
  if (draft.editId != 0L && editBaseline != null && draft != editBaseline) confirmDiscardEdit = true
  else { draft = OrderDraft(); editBaseline = null; tab = 0 }
 }
 BackHandler(enabled = tab == 1 && draft.editId != 0L) { leaveEdit() }
 LaunchedEffect(draft) { draftPrefs.edit().putString("current", encodeDraft(draft)).apply() }
 val orders by vm.orders.collectAsState(); val legacy by vm.legacy.collectAsState(); val standalone by vm.standaloneShipments.collectAsState()
 val notice by vm.notice.collectAsState()
 val items by vm.items.collectAsState(); val shipments by vm.shipments.collectAsState(); val numbers by vm.numbers.collectAsState(); val events by vm.events.collectAsState(); val markets by vm.markets.collectAsState()
 LaunchedEffect(Unit) { vm.reconcileArchives() }
 LaunchedEffect(settings.autoArchive, settings.archiveDays, orders.sumOf { it.statusUpdatedAt }, shipments.sumOf { it.statusUpdatedAt }) {
  if(settings.autoArchive) vm.archiveDue(settings.archiveDays.toIntOrNull() ?: 14)
 }
 var deleteTarget by remember { mutableStateOf<OrderRecord?>(null) }; var archiveAllDialog by remember { mutableStateOf(false) }
 var archiveDeletion by remember { mutableStateOf<ArchiveSelection?>(null) }
 var duplicateWarning by remember { mutableStateOf(false) }; var saveAnotherRequested by remember { mutableStateOf(false) }
 var search by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("All") }; var refreshMessage by remember { mutableStateOf("") }
 var shipmentSearch by remember { mutableStateOf("") }; var shipmentFilter by remember { mutableStateOf("Active") }
 var inboxMode by remember { mutableStateOf("import") }; var selectedInboxOrder by remember { mutableStateOf<OrderRecord?>(null) }
 var selectedInboxShipment by remember { mutableStateOf<ShipmentRecord?>(null) }
 val shipmentChecks by vm.shipmentChecks.collectAsState()
 val context = LocalContext.current
 fun edit(order: OrderRecord) { draft = OrderDraft(editId = order.id, market = order.marketplace, name = order.name, number = order.orderNumber,
  date = order.placedAt?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) } ?: "", status = order.status, url = order.orderUrl, notes = order.notes,
  products = items.filter { it.orderId == order.id }.map { ProductDraft(it.name, it.quantity.toString(), it.priceMinor?.let { p -> "%.2f".format(Locale.US, p / 100.0) } ?: "") },
  shipments = shipments.filter { it.orderId == order.id }.map { ship -> val track = numbers.firstOrNull { it.shipmentId == ship.id }; ShipmentDraft(title = ship.title, number = track?.number ?: "", carrier = track?.carrier ?: "", status = ship.status, confirmed = track != null, shippedDate = ship.shippedAt?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) } ?: "") }); editBaseline = draft; tab = 1 }
 @Composable fun shipmentCard(shipment: ShipmentRecord) {
  ShipmentDetailCard(shipment, orders.firstOrNull { it.id == shipment.orderId }, numbers.filter { it.shipmentId == shipment.id }, events.filter { it.shipmentId == shipment.id }, orders,
   onStatus = { vm.updateShipment(shipment, it) }, onEdit = { number, carrier, eta, shipped -> vm.updateTracking(shipment, numbers.firstOrNull { it.shipmentId == shipment.id }, number, carrier, eta, shipped) },
   onEvent = { description, location -> vm.addManualEvent(shipment.id, description, location) },
   onLink = { order -> vm.linkShipment(shipment, order) }, onHandoff = { number, carrier -> vm.addHandoff(shipment.id, number, carrier) }, onArchive = { vm.archiveStandalone(shipment, !shipment.archived) },
   onDelete = { vm.deleteStandalone(shipment.id) }, open = { launchUrl(context, it) },
   lastChecked = shipmentChecks[shipment.id], onUpdate = if(shipment.archived) null else { { selectedInboxShipment = shipment; inboxMode = "shipment"; tab = 4 } })
 }
 Scaffold(topBar = { TopAppBar(title = { Text(listOf("Orders", if(draft.editId == 0L) "Add Order" else "Edit Order", "Archive", "Settings", "Inbox Import", "Shipments")[tab]) }) }, bottomBar = { NavigationBar { listOf("Orders" to 0, "Shipments" to 5, "Add" to 1, "Archive" to 2, "Settings" to 3).forEach { (label, target) -> NavigationBarItem(selected = tab == target, onClick = { if (tab == 1 && draft.editId != 0L && target != 1) leaveEdit() else tab = target }, icon = { Text(mapOf(0 to "▣", 5 to "⇄", 1 to "+", 2 to "▤", 3 to "⚙").getValue(target)) }, label = { Text(label) }) } } }) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 14.dp)) {
   when(tab) {
    0, 2 -> {
     val archived = tab == 2
     if(!archived) {
      OutlinedTextField(search, { search = it }, label = { Text("Search orders") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("All", "Active", "Shipped").forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) } }
      OutlinedButton(onClick = { inboxMode = "all"; tab = 4 }, modifier = Modifier.fillMaxWidth()) { Text("Update All via Inbox") }
      if(refreshMessage.isNotEmpty()) Text(refreshMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
      if(notice.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f)); TextButton(onClick = { vm.clearNotice() }) { Text("Dismiss") } }
     } else {
      OutlinedButton(onClick = { archiveAllDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Archive All Delivered") }
      ArchiveDeleteAllButton(ArchiveSelection(orders.filter { it.archived }.map { it.id }, shipments.filter { it.archived }.map { it.id }, legacy.filter { it.archived }.map { it.id })) { archiveDeletion = it }
      if(notice.isNotEmpty()) Text(notice, color = MaterialTheme.colorScheme.error)
     }
     val visible = orders.filter { it.archived == archived && (archived || filter == "All" || (filter == "Shipped" && it.status == "Shipped") || (filter == "Active" && it.status != "Delivered" && it.status != "Cancelled")) && (search.isBlank() || listOf(it.name, it.marketplace, it.orderNumber).any { field -> field.contains(search, true) }) }.sortedWith(compareByDescending<OrderRecord> { settings.deliveryFirst && shipments.any { sh -> sh.orderId == it.id && sh.status == "Out for Delivery" } }
      .thenBy { settings.missingLast && shipments.none { sh -> sh.orderId == it.id && numbers.any { n -> n.shipmentId == sh.id } } }
      .thenByDescending { if(settings.sortRecent) it.statusUpdatedAt else it.createdAt })
     LazyColumn(contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      if(visible.isEmpty() && legacy.none { it.archived == archived } && (!archived || shipments.none { it.archived })) item { Text(if(archived) "No archived records" else "No orders yet. Use Add to create one.") }
      items(visible, key = { "order${it.id}" }) { order -> OrderCard(order, shipments.filter { it.orderId == order.id }, numbers, events, items.filter { it.orderId == order.id },
       onCheck = { selectedInboxOrder = order; inboxMode = "single"; tab = 4 }, onEdit = { edit(order) }, onArchive = { vm.archive(order, !archived) }, onDelete = { deleteTarget = order }, onStatus = { vm.updateStatus(order, it) }, onShipmentStatus = { ship, status -> vm.updateShipment(ship, status) }, onAddShipment = { vm.addShipment(order, it) }, onEditShipment = { ship, number, carrier, eta, shipped -> vm.updateTracking(ship, numbers.firstOrNull { it.shipmentId == ship.id }, number, carrier, eta, shipped) }, onEvent = { sid, desc, loc -> vm.addManualEvent(sid, desc, loc) }, onHandoff = { sid, num, carrier -> vm.addHandoff(sid, num, carrier) }, open = { launchUrl(context, it) }) }
      if(archived && shipments.any { it.archived }) item { Text("Archived shipments", style = MaterialTheme.typography.titleMedium) }
      items(if(archived) shipments.filter { it.archived } else emptyList(), key = { "archivedShipment${it.id}" }) { shipment -> shipmentCard(shipment) }
      if(legacy.any { it.archived == archived }) item { Text("Earlier records (preserved)", style = MaterialTheme.typography.titleMedium) }
      items(legacy.filter { it.archived == archived }, key = { "legacy${it.id}" }) { pkg -> ElevatedCard { Column(Modifier.fillMaxWidth().padding(14.dp)) {
       Text(pkg.name, style = MaterialTheme.typography.titleMedium); Text("${pkg.marketplace} · ${pkg.orderStatus}"); Text(pkg.trackingNumber.ifBlank { "No tracking number" })
       Row { if(pkg.orderUrl.startsWith("https://")) TextButton(onClick = { launchUrl(context, pkg.orderUrl) }) { Text("View Order") }; if(pkg.trackingUrl.startsWith("https://")) TextButton(onClick = { launchUrl(context, pkg.trackingUrl) }) { Text("View Tracking") } }
       TextButton(onClick = { vm.legacyArchive(pkg, !archived) }) { Text(if(archived) "Restore" else "Archive") }
      } } }
     }
    }
    1 -> AddScreen(draft, { draft = it }, (builtInMarkets + markets.map { it.name }).distinct().sorted(), notice = notice, onMarket = { name, url -> vm.addMarket(name, url) }, onCancel = { leaveEdit() }, onSave = { another, override -> saveAnotherRequested = another; if(draft.mode == "Shipment" && draft.editId == 0L) vm.saveStandalone(draft.standalone, onDone = { draft = OrderDraft(mode = "Shipment"); if(!another) tab = 0 }) else vm.save(draft, override, onDone = { draft = OrderDraft(); if(!another) tab = 0 }, onDuplicate = { duplicateWarning = true }) })
    3 -> SettingsScreen(vm, settings, markets, onScanImport = { inboxMode = "import"; tab = 4 })
    5 -> {
     OutlinedTextField(shipmentSearch, { shipmentSearch = it }, label = { Text("Search shipments") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
     Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Active", "Delivered", "Archived").forEach { f -> FilterChip(selected = shipmentFilter == f, onClick = { shipmentFilter = f }, label = { Text(f) }) } }
     Button(onClick = { inboxMode = "shipments"; tab = 4 }, modifier = Modifier.fillMaxWidth()) { Text("Update All via Inbox") }
     val visibleShipments = shipments.filter { s ->
      val owner = orders.firstOrNull { it.id == s.orderId }
      when(shipmentFilter) { "Archived" -> s.archived; "Delivered" -> !s.archived && canonicalShipmentStatus(s.status) == "Delivered"; else -> !s.archived } &&
       (shipmentSearch.isBlank() || listOfNotNull(s.title, s.carrier, owner?.marketplace, owner?.orderNumber, owner?.name).plus(numbers.filter { it.shipmentId == s.id }.flatMap { listOf(it.number, it.carrier) }).any { it.contains(shipmentSearch, true) })
     }.sortedWith(compareByDescending<ShipmentRecord> { canonicalShipmentStatus(it.status) == "Out for Delivery" }.thenByDescending { it.statusUpdatedAt })
     LazyColumn(contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      if(visibleShipments.isEmpty()) item { Text(if(shipmentFilter == "Archived") "No archived shipments" else "No shipments yet. Shipments appear here after an order ships or a tracking number is found.") }
      items(visibleShipments, key = { "shipment${it.id}" }) { shipment -> shipmentCard(shipment) }
     }
    }
    4 -> InboxOrdersPanel(vm, inboxMode, selectedInboxOrder, selectedInboxShipment) { tab = when { inboxMode.startsWith("shipment") -> 5; inboxMode == "import" -> 3; else -> 0 } }
   }
  }
 }
 if(confirmDiscardEdit) AlertDialog(onDismissRequest = { confirmDiscardEdit = false }, title = { Text("Discard unsaved changes?") }, text = { Text("Your edits have not been saved. Leave without saving?") }, confirmButton = { TextButton(onClick = { confirmDiscardEdit = false; editBaseline = null; draft = OrderDraft(); tab = 0 }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { confirmDiscardEdit = false }) { Text("Keep Editing") } })
 if(duplicateWarning) AlertDialog(onDismissRequest = { duplicateWarning = false }, title = { Text("Possible duplicate order") }, text = { Text("This marketplace and order number already exist. You can view the existing order or save anyway.") }, confirmButton = { TextButton(onClick = { duplicateWarning = false; vm.save(draft, true, onDone = { draft = OrderDraft(); if(!saveAnotherRequested) tab = 0 }, onDuplicate = {}) }) { Text("Save Anyway") } }, dismissButton = { TextButton(onClick = { duplicateWarning = false; tab = 0 }) { Text("View Existing") } })
 if(deleteTarget != null) AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Delete order?") }, text = { Text(if(deleteTarget?.archived == true) "This permanently deletes the archived order and its archived shipments. Active shipments are preserved and detached. This cannot be undone." else "This permanently deletes the order and its shipments. This cannot be undone.") }, confirmButton = { TextButton(onClick = { deleteTarget?.let(vm::delete); deleteTarget = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } })
 archiveDeletion?.let { selection -> ArchiveDeletionDialog(selection, onConfirm = { vm.deleteArchiveSelection(selection); archiveDeletion = null }, onDismiss = { archiveDeletion = null }) }
 if(archiveAllDialog) AlertDialog(onDismissRequest = { archiveAllDialog = false }, title = { Text("Archive delivered orders?") }, text = { Text("All delivered orders will move to Archive.") }, confirmButton = { TextButton(onClick = { vm.archiveAll(); archiveAllDialog = false }) { Text("Archive") } }, dismissButton = { TextButton(onClick = { archiveAllDialog = false }) { Text("Cancel") } })
}

@Composable internal fun ArchiveDeleteAllButton(selection: ArchiveSelection, onClick: (ArchiveSelection) -> Unit) {
 OutlinedButton(enabled = selection.orderIds.isNotEmpty() || selection.shipmentIds.isNotEmpty() || selection.legacyIds.isNotEmpty(), onClick = { onClick(selection) }, modifier = Modifier.fillMaxWidth()) { Text("Delete All") }
}

@Composable internal fun ArchiveDeletionDialog(selection: ArchiveSelection, onConfirm: () -> Unit, onDismiss: () -> Unit) {
 AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete all archived records?") }, text = {
  Text("Permanently delete ${selection.orderIds.size} archived orders and ${selection.shipmentIds.size} archived shipments" +
   (if(selection.legacyIds.isNotEmpty()) " and ${selection.legacyIds.size} archived earlier records" else "") +
   ". Active orders and shipments will be preserved. Remaining shipments will be detached from deleted orders. This cannot be undone.")
 }, confirmButton = { TextButton(onClick = onConfirm) { Text("Delete All") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
 ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); content() }) }
}
@Composable private fun Field(label: String, value: String, change: (String) -> Unit, help: String = "", placeholder: String = "") {
 var info by remember { mutableStateOf(false) }
 OutlinedTextField(value, change, label = { Text(label) }, placeholder = if(placeholder.isBlank()) null else {{ Text(placeholder) }}, modifier = Modifier.fillMaxWidth(), trailingIcon = if(help.isBlank()) null else {{ IconButton(onClick = { info = true }) { Text("ⓘ") } }}, singleLine = label != "Notes")
 if(info) AlertDialog(onDismissRequest = { info = false }, title = { Text(label) }, text = { Text(help) }, confirmButton = { TextButton(onClick = { info = false }) { Text("OK") } })
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun OrderDatePicker(value: String, change: (String) -> Unit) {
 var show by remember { mutableStateOf(false) }
 val formatted = parseDate(value)?.let { DateFormat.getDateInstance(DateFormat.LONG).format(Date(it)) } ?: "Select date"
 OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) { Text("Date (optional): $formatted") }
 if(value.isNotBlank()) TextButton(onClick = { change("") }) { Text("Clear date") }
 if(show) {
  val initial = parseDate(value)?.let { timestamp ->
   val local = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
   java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    clear(); set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH), local.get(java.util.Calendar.DAY_OF_MONTH))
   }.timeInMillis
  }
  val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
  DatePickerDialog(onDismissRequest = { show = false }, confirmButton = {
   TextButton(onClick = {
    picker.selectedDateMillis?.let { millis ->
     change(SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis)))
    }
    show = false
   }) { Text("OK") }
  }, dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } }) { DatePicker(state = picker) }
 }
}
@Composable private fun OrderPicker(selected: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
 var open by remember { mutableStateOf(false) }
 Box(Modifier.fillMaxWidth()) {
  OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(selected, maxLines = 2) }
  DropdownMenu(open, { open = false }) {
   options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) }) }
  }
 }
}
@Composable private fun PickMenu(label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
 var open by remember { mutableStateOf(false) }
 if(options.isEmpty()) return
 Box {
  TextButton(onClick = { open = true }) { Text("$label\u2026") }
  DropdownMenu(open, { open = false }) {
   options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) }) }
  }
 }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Choice(label: String, selected: String, options: List<String>, change: (String) -> Unit) {
 var expanded by remember { mutableStateOf(false) }
 var query by remember { mutableStateOf("") }
 // Small fixed lists remain ordinary dropdowns; longer lists are searchable.
 if(options.size <= 5) {
  ExposedDropdownMenuBox(expanded, { expanded = it }) {
   OutlinedTextField(selected.ifBlank { "Select" }, {}, readOnly = true, label = { Text(label) },
    modifier = Modifier.fillMaxWidth().menuAnchor(),
    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
   ExposedDropdownMenu(expanded, { expanded = false }) {
    options.forEach { option ->
     DropdownMenuItem(text = { Text(option) }, onClick = { change(option); expanded = false })
    }
   }
  }
 } else {
  OutlinedButton(onClick = { query = ""; expanded = true }, modifier = Modifier.fillMaxWidth()) {
   Text("$label: ${selected.ifBlank { "Select" }}")
  }
  if(expanded) AlertDialog(
   onDismissRequest = { expanded = false },
   title = { Text("Select $label") },
   text = {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
     OutlinedTextField(value = query, onValueChange = { query = it },
      label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
     val matches = options.distinct().filter { it.contains(query.trim(), ignoreCase = true) }
     if(matches.isEmpty()) Text("No matches", style = MaterialTheme.typography.bodySmall)
     LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
      items(matches) { option ->
       TextButton(onClick = { change(option); expanded = false }, modifier = Modifier.fillMaxWidth()) {
        Text(if(option == selected) "✓ $option" else option)
       }
      }
     }
    }
   },
   confirmButton = { TextButton(onClick = { expanded = false }) { Text("Cancel") } }
  )
 }
}
// Tracking-number carrier first; the shipment's own carrier when it has no number.
private fun shipmentCarrier(shipment: ShipmentRecord, numbers: List<ShipmentTrackingNumberRecord>): String =
 numbers.firstOrNull { it.shipmentId == shipment.id && it.carrier.isNotBlank() }?.carrier ?: shipment.carrier

// Local branded carrier identifier. These are text badges, NOT licensed carrier logo artwork.
@Composable private fun CarrierBadge(carrier: String) {
 val label = when(carrier) { "FedEx" -> "FedEx"; "UPS" -> "UPS"; "USPS" -> "USPS"; "DHL" -> "DHL"; "DHL eCommerce" -> "DHL"; "Cainiao" -> "Cainiao"; "China Post" -> "China Post"; "OnTrac" -> "OnTrac"; "Amazon Logistics" -> "Amazon"; else -> carrier.ifBlank { "Carrier" } }
 Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
  Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
 }
}
@Composable private fun ShipmentFields(shipment: ShipmentDraft, change: (ShipmentDraft) -> Unit) {
 Field("Tracking number", shipment.number, { change(shipment.copy(number = it, carrier = "", confirmed = false)) }, "Paste the carrier's tracking number. The app suggests a carrier, which you must confirm.")
 val candidates = carrierCandidates(shipment.number)
 if(shipment.number.isNotBlank()) {
  Text(if(candidates.isEmpty()) "No reliable format match. Select the carrier below." else "Possible carriers (format only; not verified):", style = MaterialTheme.typography.bodySmall)
  candidates.forEach { candidate ->
   OutlinedButton(onClick = { change(shipment.copy(carrier = candidate, confirmed = false)) }, modifier = Modifier.fillMaxWidth()) { Text("Suggest $candidate") }
  }
 }
 Choice("Carrier", shipment.carrier, carriers) { change(shipment.copy(carrier = it, confirmed = false)) }
 if(shipment.carrier.isNotBlank() && shipment.number.isNotBlank()) {
  Button(onClick = { change(shipment.copy(confirmed = true)) }, enabled = !shipment.confirmed, modifier = Modifier.fillMaxWidth()) {
   Text(if(shipment.confirmed) "Carrier confirmed: ${shipment.carrier}" else "Confirm ${shipment.carrier}")
  }
  if(shipment.confirmed) Text("✓ Confirmed. You can save this shipment.", color = MaterialTheme.colorScheme.primary)
 }
 Choice("Shipment status", shipment.status, shipmentStatuses) { change(shipment.copy(status = it)) }
 Text("Shipped date (optional)", style = MaterialTheme.typography.labelMedium)
 OrderDatePicker(shipment.shippedDate) { change(shipment.copy(shippedDate = it)) }
 Text("Tracking link generated automatically for supported carriers.", style = MaterialTheme.typography.bodySmall)
}
@Composable private fun AddScreen(draft: OrderDraft, update: (OrderDraft) -> Unit, marketplaces: List<String>, onMarket: (String, String) -> Unit, onSave: (Boolean, Boolean) -> Unit, onCancel: () -> Unit, notice: String = "") {
 var newMarket by remember { mutableStateOf(false) }; var marketName by remember { mutableStateOf("") }; var marketUrl by remember { mutableStateOf("") }
 var clearConfirm by remember { mutableStateOf(false) }
 var moreOptions by rememberSaveable { mutableStateOf(false) }
 val validOrder = draft.market.isNotBlank() && draft.name.isNotBlank() && draft.number.isNotBlank() && draft.shipments.all { it.number.isBlank() || (it.carrier.isNotBlank() && it.confirmed) }
 val valid = if(draft.mode == "Shipment" && draft.editId == 0L) draft.standalone.title.isNotBlank() && draft.standalone.number.isNotBlank() && draft.standalone.carrier.isNotBlank() && draft.standalone.confirmed else validOrder
 Column(Modifier.fillMaxSize()) {
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   Spacer(Modifier.height(4.dp))
   if(draft.editId == 0L) {
    Text("What would you like to add?", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
     FilterChip(selected = draft.mode == "Order", onClick = { update(draft.copy(mode = "Order")) }, label = { Text("Order") })
     FilterChip(selected = draft.mode == "Shipment", onClick = { update(draft.copy(mode = "Shipment")) }, label = { Text("Shipment") })
    }
   }
   Text("Your unfinished form stays here when you switch tabs.", style = MaterialTheme.typography.bodySmall)
   if(draft.mode == "Shipment" && draft.editId == 0L) {
    Section("Standalone shipment") {
     Text("Title and tracking number are required. Select a suggested carrier, then confirm it.", style = MaterialTheme.typography.bodySmall)
     Field("Shipment Title *", draft.standalone.title, { update(draft.copy(standalone = draft.standalone.copy(title = it))) })
     ShipmentFields(draft.standalone) { update(draft.copy(standalone = it)) }
    }
   } else {
   Section("Marketplace") {
    Choice("Marketplace *", draft.market, marketplaces) { update(draft.copy(market = it)) }
    TextButton(onClick = { newMarket = true }) { Text("+ Add New Marketplace") }
   }
   Section("Order Details") {
    Field("Title *", draft.name, { update(draft.copy(name = it)) }, "A short recognizable description of your purchase.")
    Field("Order Number *", draft.number, { update(draft.copy(number = it)) }, "Required to identify the order in retailer emails and avoid duplicates.")
    OrderDatePicker(draft.date) { update(draft.copy(date = it)) }
    Choice("Order Status", draft.status, orderStatuses) { update(draft.copy(status = it)) }
    Field("Order URL (optional)", draft.url, { update(draft.copy(url = it)) }, "The retailer's HTTPS order page; View Order opens in Chrome.")
   }
   OutlinedButton(onClick = { moreOptions = !moreOptions }, modifier = Modifier.fillMaxWidth()) { Text(if(moreOptions) "Hide More Options" else "More Options · Products, shipments, notes") }
   if(moreOptions) {
   Section("Notes (optional)") { Field("Notes", draft.notes, { update(draft.copy(notes = it)) }) }
   Section("Products (optional)") {
    draft.products.forEachIndexed { i, product ->
     Text("Product ${i + 1}")
     Field("Product name", product.name, { value -> update(draft.copy(products = draft.products.toMutableList().also { it[i] = product.copy(name = value) })) })
     Field("Quantity", product.qty, { value -> update(draft.copy(products = draft.products.toMutableList().also { it[i] = product.copy(qty = value) })) })
     Field("Price (USD, optional)", product.price, { value -> update(draft.copy(products = draft.products.toMutableList().also { it[i] = product.copy(price = value) })) })
     TextButton(onClick = { update(draft.copy(products = draft.products.filterIndexed { index, _ -> index != i })) }) { Text("Remove product") }
     HorizontalDivider()
    }
    OutlinedButton(onClick = { update(draft.copy(products = draft.products + ProductDraft())) }) { Text("+ Add Product") }
   }
   Section("Shipments (optional)") {
    if(draft.status == "Order Placed" || draft.status == "Processing" || draft.status == "Cancelled") Text("Shipment details are optional at this stage.", style = MaterialTheme.typography.bodySmall)
    draft.shipments.forEachIndexed { i, shipment ->
     Text("Shipment ${i + 1}")
     Field("Shipment Title (optional)", shipment.title, { value -> update(draft.copy(shipments = draft.shipments.toMutableList().also { it[i] = shipment.copy(title = value) })) })
     ShipmentFields(shipment) { value -> update(draft.copy(shipments = draft.shipments.toMutableList().also { it[i] = value })) }
     TextButton(onClick = { update(draft.copy(shipments = draft.shipments.filterIndexed { index, _ -> index != i })) }) { Text("Remove shipment") }
     HorizontalDivider()
    }
    OutlinedButton(onClick = { update(draft.copy(shipments = draft.shipments + ShipmentDraft())) }) { Text("+ Add Shipment") }
   }
   }
   }
   TextButton(onClick = { clearConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear Form") }
   Spacer(Modifier.height(8.dp))
  }
  if(!valid) Text(if(draft.mode == "Shipment" && draft.editId == 0L) "Enter a shipment title and tracking number, choose a carrier, and confirm it." else "Required: marketplace, title, and order number. Confirm the carrier for any entered tracking numbers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
  if(notice.isNotEmpty()) Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
  if (draft.editId != 0L) OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel Editing") }
  Button(onClick = { onSave(false, false) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text(if(draft.editId != 0L) "Save Changes" else if(draft.mode == "Shipment") "Save Shipment" else "Save Order") }
  OutlinedButton(onClick = { onSave(true, false) }, enabled = valid && draft.editId == 0L, modifier = Modifier.fillMaxWidth()) { Text("Save & Add Another") }
 }
 if(newMarket) AlertDialog(onDismissRequest = { newMarket = false }, title = { Text("Add Marketplace") }, text = { Column { Field("Marketplace name", marketName, { marketName = it }); Field("Website (optional)", marketUrl, { marketUrl = it }) } }, confirmButton = { TextButton(enabled = marketName.isNotBlank(), onClick = { onMarket(marketName, marketUrl); update(draft.copy(market = marketName.trim())); marketName = ""; marketUrl = ""; newMarket = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { newMarket = false }) { Text("Cancel") } })
 if(clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("Discard draft?") }, text = { Text("All unsaved order information will be cleared.") }, confirmButton = { TextButton(onClick = { update(OrderDraft()); clearConfirm = false }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Cancel") } })
}

@Composable private fun ProgressBar(title: String, progress: PurchaseProgress) {
 val accent = if(progress.attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
 Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
   Text(title, style = MaterialTheme.typography.labelMedium)
   Text(progress.summary, style = MaterialTheme.typography.labelMedium, color = if(progress.attention) accent else Color.Unspecified)
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
   repeat(progress.total) { index ->
    Box(Modifier.weight(1f).height(7.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { HorizontalDivider(thickness = 7.dp, color = if (index < progress.stage) accent else MaterialTheme.colorScheme.surfaceVariant) }
   }
  }
  Text(progress.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  progress.manualNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
 }
}

@Composable private fun OrderCard(order: OrderRecord, shipments: List<ShipmentRecord>, numbers: List<ShipmentTrackingNumberRecord>, events: List<ShipmentTrackingEventRecord>, products: List<OrderItemRecord>, onCheck: () -> Unit, onEdit: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit, onStatus: (String) -> Unit, onShipmentStatus: (ShipmentRecord, String) -> Unit, onAddShipment: (ShipmentDraft) -> Unit, onEditShipment: (ShipmentRecord, String, String, Long?, Long?) -> Unit, onEvent: (Long, String, String) -> Unit, onHandoff: (Long, String, String) -> Unit, open: (String) -> Unit) {
 var expanded by remember { mutableStateOf(false) }; var changeStatus by remember { mutableStateOf(false) }; var newShipment by remember { mutableStateOf(false) }; var shipmentDraft by remember { mutableStateOf(ShipmentDraft()) }
 var showProducts by remember { mutableStateOf(false) }
 val context = LocalContext.current
 val changes by remember(order.id) { PackageDatabase.get(context).orderDao().observeChanges(order.id) }.collectAsState(initial = emptyList())
 var showChangeHistory by remember { mutableStateOf(false) }
 val showShipmentStatus = order.archived
 ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
  SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
   Text(order.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
   Column(horizontalAlignment = Alignment.End) {
    Text("Last synced", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(dateTimeText(order.lastSyncedAt), style = MaterialTheme.typography.labelSmall)
   }
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
   Text("${order.marketplace} · #${order.orderNumber}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
   CopyButton("Order number", order.orderNumber)
  }
  val needsShipment = needsShipmentInformation(order, shipments, numbers)
  Text("Order: ${order.status}", color = MaterialTheme.colorScheme.primary, modifier = if(needsShipment) Modifier.clickable { newShipment = true } else Modifier)
  if(needsShipment) {
   Text("Shipment information needed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
   Text("Tap Shipped to add shipment details", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { newShipment = true })
  }
  if(changes.isNotEmpty()) {
   val latest = changes.first()
   Text("Latest update: ${latest.field}: ${displayHistoryValue(latest.field, latest.oldValue) { dateText(it) }.take(40)} → ${displayHistoryValue(latest.field, latest.newValue) { dateText(it) }.take(60)}", style = MaterialTheme.typography.bodySmall)
   Text("Updated: ${dateTimeText(latest.appliedAt)}", style = MaterialTheme.typography.labelSmall)
   TextButton(onClick = { showChangeHistory = !showChangeHistory }) { Text(if(showChangeHistory) "Hide update history" else "Update history (${changes.size})") }
   if(showChangeHistory) changes.take(30).forEach { change ->
    Text("${dateTimeText(change.appliedAt)} · ${change.field}: ${displayHistoryValue(change.field, change.oldValue) { dateText(it) }.take(60)} → ${displayHistoryValue(change.field, change.newValue) { dateText(it) }.take(60)}", style = MaterialTheme.typography.bodySmall)
    Text("Source: ${describeHistorySource(change.sourceMessageId, change.sourceDate)}", style = MaterialTheme.typography.labelSmall)
   }
  }
  Text("Status changed: ${dateTimeText(order.statusUpdatedAt)}", style = MaterialTheme.typography.labelSmall)
  val manualOrderStatus = !mayDeriveOrderStatus(changes.firstOrNull { it.field == "Order status" })
  // Delivery statuses belong to the Shipments tab; an active order shows only its purchase status.
  ProgressBar("Purchase progress", purchaseProgress(order.status, manualOrderStatus))
  shipments.forEachIndexed { i, ship ->
   val carrier = shipmentCarrier(ship, numbers).ifBlank { "Carrier not set" }
   Text("${if(shipments.size > 1) "Shipment ${i + 1}" else "Shipment"}: $carrier${if(showShipmentStatus) " · ${canonicalShipmentStatus(ship.status)}" else ""}", style = MaterialTheme.typography.bodySmall,
    color = if(showShipmentStatus && canonicalShipmentStatus(ship.status) == "Exception") MaterialTheme.colorScheme.error else Color.Unspecified)
  }
  if (shipments.isEmpty()) Text("Awaiting shipping information", style = MaterialTheme.typography.bodySmall)
  else shipments.forEach { ship -> numbers.filter { it.shipmentId == ship.id }.forEach { number -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { CarrierBadge(number.carrier); Text(number.number, style = MaterialTheme.typography.bodySmall); CopyButton("Tracking number", number.number) }; if(number.trackingUrl.isNotBlank()) TextButton(onClick = { open(number.trackingUrl) }) { Text("View Tracking ↗") } } }
  } }
  if(products.isNotEmpty()) TextButton(onClick = { showProducts = true }) { Text("▣ Products (${products.size})") }
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
   TextButton(onClick = onCheck) { Text("Update") }
   TextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "Hide details" else "Details") }
   if(normalizeOrderUrl(order.orderUrl).isNotBlank()) TextButton(onClick = { open(normalizeOrderUrl(order.orderUrl)) }) { Text("View Order ↗") }
  }
  if(showProducts) AlertDialog(onDismissRequest = { showProducts = false }, title = { Text("Order Products") }, text = {
   LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    items(products, key = { it.id }) { product ->
     Column {
      Text(product.name, style = MaterialTheme.typography.titleSmall)
      Text("Quantity: ${product.quantity}" + (product.priceMinor?.let { " · Unit price: $${"%.2f".format(Locale.US, it / 100.0)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
     }
     HorizontalDivider()
    }
   }
  }, confirmButton = { TextButton(onClick = { showProducts = false }) { Text("Close") } })
  if(expanded) {
   val placedLabel = dateText(order.placedAt) + if(changes.firstOrNull { it.field == "Placed date" }?.newValue?.endsWith(APPROXIMATE_DATE_SUFFIX) == true) APPROXIMATE_DATE_SUFFIX else ""
   val itemsText = products.joinToString("; ") { "${it.name} \u00d7${it.quantity}" }
   SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Retailer: ${order.marketplace}")
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Order number: ${order.orderNumber}", Modifier.weight(1f)); CopyButton("Order number", order.orderNumber) }
    Text("Placed: $placedLabel")
    Text("Status: ${order.status}")
    if(itemsText.isNotBlank()) Text("Items: $itemsText")
    Text("Order last changed: ${dateTimeText(order.statusUpdatedAt)}", style = MaterialTheme.typography.bodySmall)
    if(order.notes.isNotBlank()) Text("Notes: ${order.notes}")
    if(order.orderUrl.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) { Text("Order URL: ${order.orderUrl}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); CopyButton("Order URL", order.orderUrl) }
   } }
   OutlinedButton(onClick = { copyToClipboard(context, "Order details", orderCopyText(order, placedLabel, itemsText)) }) { Text("Copy order details") }
   shipments.forEachIndexed { i, shipment ->
    var showEvents by remember { mutableStateOf(false) }
    var editingShipment by remember { mutableStateOf(false) }
    var addingEvent by remember { mutableStateOf(false) }
    var addingHandoff by remember { mutableStateOf(false) }
    var handoffNumber by remember { mutableStateOf("") }
    var handoffCarrier by remember { mutableStateOf("") }
    if(showShipmentStatus) Text("Shipment status updated: ${dateTimeText(shipment.statusUpdatedAt)} · Source: ${shipment.statusSource}", style = MaterialTheme.typography.bodySmall)
    val currentTracking = numbers.firstOrNull { it.shipmentId == shipment.id }
    var trackingNumber by remember(currentTracking?.number) { mutableStateOf(currentTracking?.number ?: "") }
    var carrier by remember(currentTracking?.carrier, shipment.carrier) { mutableStateOf(currentTracking?.carrier ?: shipment.carrier) }
    var eta by remember(shipment.estimatedDeliveryAt) { mutableStateOf(shipment.estimatedDeliveryAt?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) } ?: "") }
    var eventDesc by remember { mutableStateOf("") }
    var shipped by remember(shipment.shippedAt) { mutableStateOf(shipment.shippedAt?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) } ?: "") }
    var eventLoc by remember { mutableStateOf("") }
    var shipmentStatusMenu by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf(false) }
    HorizontalDivider()
    Text("Shipment ${i + 1}${if(showShipmentStatus) " · ${canonicalShipmentStatus(shipment.status)}" else ""}", style = MaterialTheme.typography.titleSmall)
    Text("Estimated: ${dateText(shipment.estimatedDeliveryAt)} · Shipped: ${dateText(shipment.shippedAt)}", style = MaterialTheme.typography.bodySmall)
    if(numbers.none { it.shipmentId == shipment.id } && shipment.carrier.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CarrierBadge(shipment.carrier); Text("No tracking number yet", style = MaterialTheme.typography.bodySmall) }
    numbers.filter { it.shipmentId == shipment.id }.forEach { number ->
     Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CarrierBadge(number.carrier); Text(number.number) }
     if(number.trackingUrl.isNotBlank()) TextButton(onClick = { open(number.trackingUrl) }) { Text("View Tracking ↗") }
    }
    Box {
     OutlinedButton(onClick = { actionMenu = true }) { Text("Shipment actions ▾") }
     DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
      if(showShipmentStatus) DropdownMenuItem(text = { Text("Update status") }, onClick = { actionMenu = false; shipmentStatusMenu = true })
      DropdownMenuItem(text = { Text("Edit shipment") }, onClick = { actionMenu = false; editingShipment = true })
      DropdownMenuItem(text = { Text("Add tracking event") }, onClick = { actionMenu = false; addingEvent = true })
      DropdownMenuItem(text = { Text("Add carrier handoff") }, onClick = { actionMenu = false; addingHandoff = true })
      DropdownMenuItem(text = { Text(if(showEvents) "Hide tracking history" else "Show tracking history") }, onClick = { actionMenu = false; showEvents = !showEvents })
     }
    }
    if(editingShipment) AlertDialog(onDismissRequest = { editingShipment = false }, title = { Text("Edit shipment") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
     Field("Tracking number", trackingNumber, { trackingNumber = it })
     Choice("Carrier", carrier, carriers) { carrier = it }
     OrderDatePicker(eta) { eta = it }
    } }, confirmButton = { TextButton(enabled = carrier.isNotBlank() && (trackingNumber.isNotBlank() || currentTracking == null) && (eta.isBlank() || parseDate(eta) != null) && (shipped.isBlank() || parseDate(shipped) != null), onClick = { onEditShipment(shipment, trackingNumber, carrier, parseDate(eta), parseDate(shipped)); editingShipment = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editingShipment = false }) { Text("Cancel") } })
    if(addingHandoff) AlertDialog(onDismissRequest = { addingHandoff = false }, title = { Text("Add carrier handoff") }, text = { Column {
     Field("New tracking number", handoffNumber, { handoffNumber = it })
     Choice("New carrier", handoffCarrier, carriers) { handoffCarrier = it }
     Text("Existing tracking numbers and events are preserved.", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(enabled = handoffNumber.isNotBlank() && handoffCarrier.isNotBlank(), onClick = { onHandoff(shipment.id, handoffNumber, handoffCarrier); addingHandoff = false; handoffNumber = ""; handoffCarrier = "" }) { Text("Add") } }, dismissButton = { TextButton(onClick = { addingHandoff = false }) { Text("Cancel") } })
 if(addingEvent) AlertDialog(onDismissRequest = { addingEvent = false }, title = { Text("Add tracking event") }, text = { Column { Field("Description", eventDesc, { eventDesc = it }); Field("Location (optional)", eventLoc, { eventLoc = it }) } }, confirmButton = { TextButton(enabled = eventDesc.isNotBlank(), onClick = { onEvent(shipment.id, eventDesc, eventLoc); eventDesc = ""; eventLoc = ""; addingEvent = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { addingEvent = false }) { Text("Cancel") } })
    if(shipmentStatusMenu) AlertDialog(onDismissRequest = { shipmentStatusMenu = false }, title = { Text("Shipment status") }, text = { Column { shipmentStatuses.forEach { status -> TextButton(onClick = { onShipmentStatus(shipment, status); shipmentStatusMenu = false }) { Text(status) } } } }, confirmButton = { TextButton(onClick = { shipmentStatusMenu = false }) { Text("Cancel") } })
    if(showEvents) {
     val history = events.filter { it.shipmentId == shipment.id }.sortedByDescending { it.eventAt }
     if(history.isEmpty()) Text("No tracking events yet. Live updates arrive in Phase 3.")
     history.forEach { event -> Text("${dateTimeText(event.eventAt)} · ${event.description}${if(event.location.isNotBlank()) " · ${event.location}" else ""}", style = MaterialTheme.typography.bodySmall) }
    }
   }
   OutlinedButton(onClick = { newShipment = true }) { Text("+ Add Shipment") }
   Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    TextButton(onClick = onEdit) { Text("Edit") }
    TextButton(onClick = { changeStatus = true }) { Text("Set Status") }
    TextButton(onClick = onArchive) { Text(if(order.archived) "Restore" else "Archive") }
    TextButton(onClick = onDelete) { Text("Delete") }
   }
  }
 } }
 if(changeStatus) AlertDialog(onDismissRequest = { changeStatus = false }, title = { Text("Order status") }, text = { Column { orderStatuses.forEach { status -> TextButton(onClick = { onStatus(status); changeStatus = false }) { Text(status) } } } }, confirmButton = { TextButton(onClick = { changeStatus = false }) { Text("Cancel") } })
 if(newShipment) AlertDialog(onDismissRequest = { newShipment = false }, title = { Text("Add Shipment") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { ShipmentFields(shipmentDraft) { shipmentDraft = it } } }, confirmButton = { TextButton(enabled = shipmentDraft.number.isBlank() || (shipmentDraft.carrier.isNotBlank() && shipmentDraft.confirmed), onClick = { onAddShipment(shipmentDraft); shipmentDraft = ShipmentDraft(); newShipment = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { newShipment = false }) { Text("Cancel") } })
}

@Composable internal fun ShipmentDetailCard(
 shipment: ShipmentRecord, order: OrderRecord?, tracking: List<ShipmentTrackingNumberRecord>, events: List<ShipmentTrackingEventRecord>, orders: List<OrderRecord>,
 onStatus: (String) -> Unit, onEdit: (String, String, Long?, Long?) -> Unit, onEvent: (String, String) -> Unit,
 onLink: (OrderRecord) -> Unit, onHandoff: (String, String) -> Unit, onArchive: () -> Unit, onDelete: () -> Unit, open: (String) -> Unit,
 lastChecked: Long? = null, onUpdate: (() -> Unit)? = null
) {
 val context = LocalContext.current
 var expanded by remember { mutableStateOf(false) }
 var editing by remember { mutableStateOf(false) }
 var linking by remember { mutableStateOf(false) }
 var addingEvent by remember { mutableStateOf(false) }
 var addingHandoff by remember { mutableStateOf(false) }
 var handoffNumber by remember { mutableStateOf("") }
 var handoffCarrier by remember { mutableStateOf("") }
 var deleting by remember { mutableStateOf(false) }
 val primary = tracking.firstOrNull()
 var editNumber by remember(primary?.number) { mutableStateOf(primary?.number ?: "") }
 var editCarrier by remember(primary?.carrier) { mutableStateOf(primary?.carrier ?: "") }
 var editEta by remember(shipment.estimatedDeliveryAt) { mutableStateOf(shipment.estimatedDeliveryAt?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) } ?: "") }
 var editShipped by remember(shipment.shippedAt) { mutableStateOf(shipment.shippedAt?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) } ?: "") }
 var eventDescription by remember { mutableStateOf("") }
 var eventLocation by remember { mutableStateOf("") }
 ElevatedCard(Modifier.fillMaxWidth()) {
  Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text("${shipment.title.ifBlank { order?.let { "${it.marketplace} · ${it.name}" } ?: "Standalone Shipment" }} · ${canonicalShipmentStatus(shipment.status)}", style = MaterialTheme.typography.titleMedium)
   if(order != null) Row(verticalAlignment = Alignment.CenterVertically) {
    Text("Order: ${order.marketplace} · #${order.orderNumber}${if(order.archived) " (archived)" else ""} · ${order.status}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    CopyButton("Order number", order.orderNumber)
   }
   Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { CarrierBadge(primary?.carrier ?: shipment.carrier); Text(primary?.number ?: "No tracking number"); CopyButton("Tracking number", primary?.number.orEmpty()) }
   Text("Shipped: ${dateText(shipment.shippedAt)} · Estimated delivery: ${dateText(shipment.estimatedDeliveryAt)}", style = MaterialTheme.typography.bodySmall)
   Text("Status updated: ${dateTimeText(shipment.statusUpdatedAt)} · Last checked: ${lastChecked?.let { dateTimeText(it) } ?: "Not checked yet"}", style = MaterialTheme.typography.labelSmall)
   ProgressBar("Shipment progress", standaloneShipmentProgress(shipment.status))
   } }
   Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    if(onUpdate != null) TextButton(onClick = onUpdate) { Text("Update") }
    TextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "Hide details ▲" else "Shipment details ▼") }
    if(!primary?.trackingUrl.isNullOrBlank()) TextButton(onClick = { open(primary!!.trackingUrl) }) { Text("View Tracking ↗") }
   }
   Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TextButton(onClick = onArchive) { Text(if(shipment.archived) "Restore shipment" else "Archive shipment") }
    TextButton(onClick = { deleting = true }) { Text("Delete shipment") }
   }
   if(expanded) {
    Choice("Shipment status", shipment.status, shipmentStatuses, onStatus)
    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
     if(order != null) Text("Retailer: ${order.marketplace}")
     if(order != null) Row(verticalAlignment = Alignment.CenterVertically) { Text("Order number: ${order.orderNumber}", Modifier.weight(1f)); CopyButton("Order number", order.orderNumber) }
     tracking.forEach { t -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { CarrierBadge(t.carrier); Text("${t.number} (${t.role})", style = MaterialTheme.typography.bodySmall); CopyButton("Tracking number", t.number) } }
     if(primary != null && primary.trackingUrl.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) { Text("Tracking URL: ${primary.trackingUrl}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); CopyButton("Tracking URL", primary.trackingUrl) }
    } }
    OutlinedButton(onClick = { copyToClipboard(context, "Shipment details", shipmentCopyText(shipment, tracking, order, dateText(shipment.shippedAt), dateText(shipment.estimatedDeliveryAt))) }) { Text("Copy shipment details") }
    Text("Tracking history (newest first)", style = MaterialTheme.typography.titleSmall)
    if(events.isEmpty()) Text("No tracking events yet. Live updates require Phase 3.", style = MaterialTheme.typography.bodySmall)
    events.sortedWith(compareByDescending<ShipmentTrackingEventRecord> { it.eventAt }.thenByDescending { it.id }).forEach { e ->
     Text("● ${dateTimeText(e.eventAt)} · ${e.description}${if(e.location.isNotBlank()) " · ${e.location}" else ""} · ${e.source}", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = { addingEvent = true }) { Text("+ Add tracking event") }
    OutlinedButton(onClick = { addingHandoff = true }) { Text("+ Add carrier handoff") }
    OutlinedButton(onClick = { editing = true }) { Text("Edit shipment") }
    OutlinedButton(onClick = { linking = true }, enabled = orders.isNotEmpty() && order == null) { Text(if(order == null) "Link to existing order" else "Linked to an order") }

   }
  }
 }
 if(editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("Edit shipment") }, text = {
  Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Field("Tracking number", editNumber, { editNumber = it })
   Choice("Carrier", editCarrier, carriers) { editCarrier = it }
   OrderDatePicker(editEta) { editEta = it }
   Text("Shipped date", style = MaterialTheme.typography.labelMedium)
   OrderDatePicker(editShipped) { editShipped = it }
  }
 }, confirmButton = { TextButton(enabled = (editNumber.isNotBlank() || primary == null) && editCarrier.isNotBlank() && (editEta.isBlank() || parseDate(editEta) != null) && (editShipped.isBlank() || parseDate(editShipped) != null), onClick = { onEdit(editNumber.trim(), editCarrier, parseDate(editEta), parseDate(editShipped)); editing = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
 if(addingHandoff) AlertDialog(onDismissRequest = { addingHandoff = false }, title = { Text("Add carrier handoff") }, text = { Column {
  Field("New tracking number", handoffNumber, { handoffNumber = it })
  Choice("New carrier", handoffCarrier, carriers) { handoffCarrier = it }
  Text("Existing tracking history will remain available.", style = MaterialTheme.typography.bodySmall)
 } }, confirmButton = { TextButton(enabled = handoffNumber.isNotBlank() && handoffCarrier.isNotBlank(), onClick = { onHandoff(handoffNumber, handoffCarrier); addingHandoff = false; handoffNumber = ""; handoffCarrier = "" }) { Text("Add") } }, dismissButton = { TextButton(onClick = { addingHandoff = false }) { Text("Cancel") } })
 if(addingEvent) AlertDialog(onDismissRequest = { addingEvent = false }, title = { Text("Add tracking event") }, text = { Column {
  Field("Description", eventDescription, { eventDescription = it }); Field("Location (optional)", eventLocation, { eventLocation = it })
 } }, confirmButton = { TextButton(enabled = eventDescription.isNotBlank(), onClick = { onEvent(eventDescription, eventLocation); eventDescription = ""; eventLocation = ""; addingEvent = false }) { Text("Save event") } }, dismissButton = { TextButton(onClick = { addingEvent = false }) { Text("Cancel") } })
 if(linking) AlertDialog(onDismissRequest = { linking = false }, title = { Text("Link shipment to order") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
  orders.forEach { order -> TextButton(onClick = { onLink(order); linking = false }) { Text("${order.marketplace} · #${order.orderNumber} · ${order.name}${if(order.archived) " (archived)" else ""}") } }
 } }, confirmButton = { TextButton(onClick = { linking = false }) { Text("Cancel") } })
 if(deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete shipment permanently?") }, text = { Text("This permanently deletes this shipment, its tracking numbers and events. The associated order is preserved. This cannot be undone.") }, confirmButton = { TextButton(onClick = { onDelete(); deleting = false }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

@Composable private fun SettingSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, change) } }
// Rendered only from committed results; discarded when the Update screen is left.
@Composable private fun ScanResultsList(summary: ScanUpdateSummary) {
 var showUnchanged by remember { mutableStateOf(false) }
 var expandedDiagnostics by remember { mutableStateOf(setOf<Long>()) }
 Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
  if(summary.updated == 0) Text("No saved information changed.", style = MaterialTheme.typography.bodySmall)
  summary.results.filter { it.outcome != ScanOutcome.UNCHANGED }.forEach { r ->
   ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("${r.retailer} — Order #${r.orderNumber}", style = MaterialTheme.typography.titleSmall)
    val label = when(r.outcome) {
     ScanOutcome.UPDATED -> "Updated · ${r.changes.size} saved ${if(r.changes.size == 1) "change" else "changes"}"
     ScanOutcome.REVIEW_REQUIRED -> "Review required · nothing was applied"
     ScanOutcome.FAILED -> "Failed · nothing was applied"
     ScanOutcome.UNCHANGED -> "Unchanged"
    }
    Text(label, style = MaterialTheme.typography.bodyMedium, color = if(r.outcome == ScanOutcome.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    r.changes.forEach { change ->
     HorizontalDivider()
     change.detailLines { dateTimeText(it) }.forEachIndexed { index, line ->
      Text(line, style = if(index == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall)
     }
    }
    r.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
    if(r.diagnostics.isNotEmpty()) {
     val expanded = r.orderId in expandedDiagnostics
     TextButton(onClick = { expandedDiagnostics = if(expanded) expandedDiagnostics - r.orderId else expandedDiagnostics + r.orderId }) {
      Text(if(expanded) "Hide email diagnostics" else "Email diagnostics (${r.diagnostics.size})")
     }
     if(expanded) r.diagnostics.forEach { diagnostic ->
      HorizontalDivider()
      Text(diagnostic.subject, style = MaterialTheme.typography.bodyMedium)
      Text("Retailer: ${diagnostic.retailer} · Order in email: ${diagnostic.orderNumbers.joinToString().ifBlank { "Not present" }}", style = MaterialTheme.typography.bodySmall)
      Text("Carrier: ${diagnostic.carrier ?: "Not identified"} · Tracking: ${diagnostic.trackingNumbers.joinToString().ifBlank { "Not identified" }}", style = MaterialTheme.typography.bodySmall)
      Text("Evidence: ${diagnostic.matchingEvidence}", style = MaterialTheme.typography.bodySmall)
      Text("Candidate saved order IDs: ${diagnostic.candidateSavedOrderIds.joinToString().ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
      Text("Result: ${diagnostic.result}", style = MaterialTheme.typography.bodySmall)
      if(diagnostic.changesApplied.isNotEmpty()) Text("Changes: ${diagnostic.changesApplied.joinToString()}", style = MaterialTheme.typography.bodySmall)
      Text("Database update committed: ${if(diagnostic.databaseCommitted) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall)
      diagnostic.rejectedCandidates.forEach { Text("Not matched: $it", style = MaterialTheme.typography.bodySmall) }
     }
    }
   } }
  }
  val unchanged = summary.results.filter { it.outcome == ScanOutcome.UNCHANGED }
  if(unchanged.isNotEmpty()) {
   TextButton(onClick = { showUnchanged = !showUnchanged }) { Text(if(showUnchanged) "Hide unchanged orders" else "Show unchanged orders (${unchanged.size})") }
  if(showUnchanged) unchanged.forEach { result ->
   Text("${result.retailer} — Order #${result.orderNumber}: no changes", style = MaterialTheme.typography.bodySmall)
   result.diagnostics.forEach { diagnostic ->
    Text("${diagnostic.subject} · ${diagnostic.retailer} · ${diagnostic.matchingEvidence} · ${diagnostic.result}", style = MaterialTheme.typography.bodySmall)
   }
  }
  }
 }
}
private fun reviewKey(item: ReviewItem): String = when(item) {
 is ReviewItem.EmailNeedsReview -> "email:" + emailKey(item.email)
 is ReviewItem.PossibleDuplicate -> "dup:${item.firstId}:${item.secondId}"
}

// Items the scan would not guess: the user confirms an association (kept for future scans) or a merge. Nothing is applied before that.
@Composable private fun NeedsReviewSection(vm: TrackerVm, reviews: List<ReviewItem>) {
 val orders by vm.orders.collectAsState(); val shipments by vm.shipments.collectAsState(); val numbers by vm.numbers.collectAsState()
 val reviewScope = rememberCoroutineScope()
 // One form per email for the life of the section, so typed text survives a failed attempt or a closed dialog.
 val forms = remember { mutableMapOf<String, AssociationFormState>() }
 var hidden by remember(reviews) { mutableStateOf(setOf<String>()) }
 var dismissedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
 var associating by remember(reviews) { mutableStateOf<ReviewItem.EmailNeedsReview?>(null) }
 var merging by remember(reviews) { mutableStateOf<ReviewItem.PossibleDuplicate?>(null) }
 LaunchedEffect(vm) { dismissedIds = vm.dismissedEmailIds() }
 val visible = reviews.filter { reviewKey(it) !in hidden && (it !is ReviewItem.EmailNeedsReview || it.email.messageId !in dismissedIds) }
 if(visible.isEmpty()) return
 Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
  Text("Needs review (${visible.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
  Text("Nothing below was applied. Associating connects an email to an existing order; removing an email excludes it from future scans and imports.", style = MaterialTheme.typography.bodySmall)
  visible.forEach { item ->
   when(item) {
    is ReviewItem.EmailNeedsReview -> ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
     Text(item.email.subject.ifBlank { "(No subject)" }, style = MaterialTheme.typography.titleSmall)
     Text("From: ${item.email.sender}", style = MaterialTheme.typography.bodySmall)
     Text(item.email.date, style = MaterialTheme.typography.labelSmall)
      val finding = extractEmailFinding(item.email, numbers, shipments)
      Text("Retailer: ${item.candidateOrderIds.mapNotNull { id -> orders.firstOrNull { it.id == id }?.marketplace }.distinct().joinToString(" / ").ifBlank { retailerNameFromSender(item.email.sender).ifBlank { "Uncertain" } }} · Order in email: ${finding.orderNumber ?: "Not present"}", style = MaterialTheme.typography.bodySmall)
      Text("Carrier: ${finding.carrier ?: "Not identified"} · Tracking: ${item.trackingCandidates.joinToString().ifBlank { "Not identified" }}", style = MaterialTheme.typography.bodySmall)
     Text(item.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
     item.candidateOrderIds.mapNotNull { id -> orders.firstOrNull { it.id == id } }.forEach { o ->
      Text("Candidate: ${o.marketplace} \u00b7 #${o.orderNumber} \u00b7 ${o.name} \u2014 ${candidateEvidenceText(o, item.email, numbers, shipments)}", style = MaterialTheme.typography.bodySmall)
     }
    var dismissalMessage by remember(item) { mutableStateOf("") }
    if(item.trackingCandidates.isNotEmpty()) Text("Tracking found: ${item.trackingCandidates.joinToString()}", style = MaterialTheme.typography.bodySmall)
     OutlinedButton(onClick = { associating = item }) { Text("Associate with an order\u2026") }
    TextButton(onClick = {
     reviewScope.launch {
      val outcome = vm.dismissEmailFromReview(item.email)
      if(outcome == SaveOutcome.Saved) { dismissedIds = dismissedIds + item.email.messageId; hidden = hidden + reviewKey(item) }
      else dismissalMessage = (outcome as? SaveOutcome.Blocked)?.message.orEmpty()
     }
    }) { Text("Remove from review and don't scan again") }
    if(dismissalMessage.isNotBlank()) Text(dismissalMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } }
    is ReviewItem.PossibleDuplicate -> {
     val first = orders.firstOrNull { it.id == item.firstId }; val second = orders.firstOrNull { it.id == item.secondId }
     if(first != null && second != null) ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Possible duplicate order #${first.orderNumber}", style = MaterialTheme.typography.titleSmall)
      Text("${first.marketplace} \u00b7 ${first.name}  and  ${second.marketplace} \u00b7 ${second.name}", style = MaterialTheme.typography.bodySmall)
      Text(if(item.sameRetailer) "Same retailer and order number." else "Same order number, different retailer names. They are merged only if you confirm they are one retailer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
      OutlinedButton(onClick = { merging = item }) { Text("Review and merge\u2026") }
     } }
    }
   }
  }
 }
 associating?.let { item ->
  var chosen by remember(item) { mutableStateOf<OrderRecord?>(null) }
  var shipmentId by remember(item) { mutableStateOf<Long?>(null) }
  var preview by remember(item) { mutableStateOf<AssociationPreview?>(null) }
  var busy by remember(item) { mutableStateOf(false) }
  var failure by remember(item) { mutableStateOf("") }
  val form = forms.getOrPut(emailKey(item.email)) {
   val p = prefillAssociation(item.email, orders)
   initialAssociationForm(p, rankSavedOrders(item.email, orders, item.candidateOrderIds, numbers, shipments, p.retailer))
  }
  LaunchedEffect(chosen?.id, shipmentId) { preview = chosen?.let { vm.previewAssociation(item.email, it.id, shipmentId) } }
  AlertDialog(onDismissRequest = { associating = null },
   title = { Text(if(chosen == null) "Associate with which order?" else "Confirm association") },
   text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val target = chosen
    if(target == null) {
     val activeOrders = orders
     val ranked = rankSavedOrders(item.email, orders, item.candidateOrderIds, numbers, shipments, form.retailer)
     fun rankedLabel(r: RankedOrder) = (if(r.tier.label.isNotEmpty()) "${r.tier.label} \u00b7 " else "") + "#${r.order.orderNumber} \u00b7 ${r.order.name}" + if(r.order.archived) " (archived)" else ""
     Text(form.prefill.retailerHint, style = MaterialTheme.typography.bodySmall)
     val retailerChoices = retailerOptions(form.prefill, orders)
     Text("Retailer", style = MaterialTheme.typography.labelMedium)
     OrderPicker(form.retailer.ifBlank { "Select a retailer" }, retailerChoices.mapIndexed { i, o -> o.label to i.toString() }) { form.pickRetailer(retailerChoices[it.toInt()], orders, ranked = ranked) }
     if(form.addingRetailer) Field("New retailer name", form.retailer, { form.editRetailer(it) }, placeholder = "Type the retailer name")
     val choices = orderChoices(form.prefill, form.retailer, ranked)
     val selected = ranked.firstOrNull { it.order.id == form.selectedOrderId }
     Text("Order number", style = MaterialTheme.typography.labelMedium)
     OrderPicker(when {
      selected != null -> rankedLabel(selected)
      form.manualNumber -> "Enter an order number\u2026"
      form.number.isNotBlank() -> "New order #${form.number}"
      else -> "Pick an order number"
     }, choices.mapIndexed { i, c -> when(c) {
      is OrderChoice.Saved -> rankedLabel(c.ranked)
      is OrderChoice.FromEmail -> "From this email: #${c.number} (new order)"
      OrderChoice.Manual -> "Enter an order number\u2026"
     } to i.toString() }) { key ->
      when(val c = choices[key.toInt()]) {
       is OrderChoice.Saved -> form.selectOrder(c.ranked.order.id)
       is OrderChoice.FromEmail -> form.pickEmailNumber(c.number)
       OrderChoice.Manual -> form.enterNumberManually()
      }
     }
     if(form.retailer.isBlank()) Text("Pick or add a retailer first to see its saved order numbers.", style = MaterialTheme.typography.bodySmall)
     else if(choices.none { it is OrderChoice.Saved }) Text("No saved orders for ${form.retailer} yet.", style = MaterialTheme.typography.bodySmall)
     if(form.manualNumber) Field("Order number", form.number, { form.number = it }, placeholder = "Type the order number")
     if(selected != null) {
      Text(if(selected.tier == MatchTier.VERIFIED || selected.tier == MatchTier.LIKELY) "Strongest match: ${selected.evidence}" else "Not a strong match: ${selected.evidence}", style = MaterialTheme.typography.bodySmall)
      val inRetailer = ranked.filter { sameRetailerName(it.order.marketplace, form.retailer) }
      if(topMatchIsTied(inRetailer) && selected.order.id == inRetailer.first().order.id)
       Text("Another order matches equally well; check this is the right one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      Button(onClick = { chosen = selected.order; shipmentId = null }) { Text("Review association\u2026") }
     } else {
     Field("Title", form.title, { form.title = it }, placeholder = "Optional \u2014 left empty, the order is named after the retailer")
     PickMenu("Pick title", activeOrders.map { it.name }.filter { it.isNotBlank() }.distinct().map { it to it }) { form.pickTitle(it, orders) }
     val duplicate = form.existing(orders)
     if(duplicate != null) {
      Text("${duplicate.marketplace} #${duplicate.orderNumber} is already saved. Associate with it instead of creating a duplicate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      TextButton(onClick = { chosen = duplicate; shipmentId = null }) { Text("Associate with that order") }
     }
     Button(enabled = !busy && form.canCreate(orders), onClick = {
      busy = true; failure = ""
      vm.createOrderForEmail(item.email, form.retailer, form.number, form.title) { outcome ->
       busy = false
       if(outcome == SaveOutcome.Saved) { hidden = hidden + reviewKey(item); associating = null }
       else failure = (outcome as? SaveOutcome.Blocked)?.message.orEmpty()
      }
     }) { Text("Create order #${form.number.ifBlank { "\u2026" }} and associate") }
     }
     if(failure.isNotEmpty()) Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else {
     Text("${target.marketplace} \u00b7 #${target.orderNumber}", style = MaterialTheme.typography.titleSmall)
     val own = shipments.filter { it.orderId == target.id }
     if(own.isNotEmpty()) {
      Text("Shipment", style = MaterialTheme.typography.labelMedium)
      TextButton(onClick = { shipmentId = null }) { Text((if(shipmentId == null) "\u2713 " else "") + "Match automatically") }
      own.forEachIndexed { i, s -> TextButton(onClick = { shipmentId = s.id }) {
       Text((if(shipmentId == s.id) "\u2713 " else "") + "Shipment ${i + 1} \u00b7 ${s.status}" + (numbers.firstOrNull { it.shipmentId == s.id }?.let { " \u00b7 ${it.number}" } ?: "")) } }
     }
     val p = preview
     if(p != null) {
      Text("If confirmed:", style = MaterialTheme.typography.labelMedium)
      Text("Tracking: ${if(p.trackingNumbers.isEmpty()) "none found in this email" else p.trackingNumbers.joinToString()}", style = MaterialTheme.typography.bodySmall)
      Text("Carrier: ${p.carrier ?: "not identified"} \u00b7 Shipment status evidence: ${p.status ?: "none"}", style = MaterialTheme.typography.bodySmall)
      Text("The choice is remembered, so this email is not asked about again.", style = MaterialTheme.typography.bodySmall)
      p.conflict?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
     }
     if(failure.isNotEmpty()) Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
   } },
   confirmButton = { if(chosen != null) TextButton(enabled = !busy && preview != null && preview?.conflict == null, onClick = {
    busy = true
    vm.associateEmail(item.email, chosen!!.id, shipmentId) { outcome ->
     busy = false
     if(outcome == SaveOutcome.Saved) { hidden = hidden + reviewKey(item); associating = null }
     else failure = (outcome as? SaveOutcome.Blocked)?.message.orEmpty()
    }
   }) { Text("Confirm association") } },
   dismissButton = { TextButton(onClick = { if(chosen != null) chosen = null else associating = null }) { Text(if(chosen != null) "Back" else "Cancel") } })
 }
 merging?.let { item ->
  val first = orders.firstOrNull { it.id == item.firstId }; val second = orders.firstOrNull { it.id == item.secondId }
  if(first == null || second == null) { merging = null } else {
   var keepFirst by remember(item) { mutableStateOf(true) }
   var sameRetailer by remember(item) { mutableStateOf(false) }
   var failure by remember(item) { mutableStateOf("") }
   val keep = if(keepFirst) first else second; val drop = if(keepFirst) second else first
   fun describe(o: OrderRecord) = "${o.marketplace} \u00b7 ${o.name}: ${shipments.count { it.orderId == o.id }} shipment(s), ${numbers.count { n -> shipments.any { it.id == n.shipmentId && it.orderId == o.id } }} tracking number(s)"
   AlertDialog(onDismissRequest = { merging = null }, title = { Text("Merge duplicate orders?") },
    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
     Text("Keep this record:", style = MaterialTheme.typography.labelMedium)
     TextButton(onClick = { keepFirst = true }) { Text((if(keepFirst) "\u2713 " else "") + describe(first)) }
     TextButton(onClick = { keepFirst = false }) { Text((if(!keepFirst) "\u2713 " else "") + describe(second)) }
     Text("Items, shipments, tracking numbers, tracking events, history and processed emails of the other record move to the kept order. Values already on the kept order are not overwritten, and its status choice is kept. The emptied record is then removed.", style = MaterialTheme.typography.bodySmall)
     if(!first.marketplace.equals(second.marketplace, ignoreCase = true)) {
      Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(sameRetailer, { sameRetailer = it }); Text("Remember that \"${first.marketplace}\" and \"${second.marketplace}\" are one retailer", style = MaterialTheme.typography.bodySmall) }
     }
     if(failure.isNotEmpty()) Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } },
    confirmButton = { TextButton(onClick = {
     vm.merge(keep.id, drop.id, sameRetailer) { outcome ->
      if(outcome == SaveOutcome.Saved) { hidden = hidden + reviewKey(item); merging = null }
      else failure = (outcome as? SaveOutcome.Blocked)?.message.orEmpty()
     }
    }) { Text("Merge") } },
    dismissButton = { TextButton(onClick = { merging = null }) { Text("Cancel") } })
  }
 }
}
@Composable private fun InboxOrdersPanel(vm: TrackerVm, mode: String, selectedOrder: OrderRecord?, selectedShipment: ShipmentRecord?, onClose: () -> Unit) {
 val context = LocalContext.current
 val authClient = remember(context) { Identity.getAuthorizationClient(context) }
 var gmailToken by remember { mutableStateOf(GmailConnectionState.token) }
 LaunchedEffect(GmailConnectionState.token) { gmailToken = GmailConnectionState.token }
 var gmailBusy by remember { mutableStateOf(false) }
 var gmailMessage by remember { mutableStateOf(GmailConnectionState.status) }
 LaunchedEffect(GmailConnectionState.status) { gmailMessage = GmailConnectionState.status }
 val scanScope = rememberCoroutineScope()
 val savedTracking by vm.numbers.collectAsState()
 val savedShipments by vm.shipments.collectAsState()
 val savedOrders by vm.orders.collectAsState()
 var scanBusy by remember { mutableStateOf(false) }
 var importBusy by remember { mutableStateOf(false) }
 var importMessage by remember { mutableStateOf("") }
 var importOutcomes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
 var runSummary by remember { mutableStateOf<ScanRunSummary?>(null) }
 var importAllSummary by remember { mutableStateOf("") }
 var scanMessage by remember { mutableStateOf("No scan performed") }
 var scanResults by remember { mutableStateOf<List<GmailPreview>>(emptyList()) }
 var expandedScanGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
 var markers by remember { mutableStateOf<List<ProcessedGmailMessage>>(emptyList()) }
 LaunchedEffect(scanResults, savedOrders) { markers = vm.associationMarkers() }
 val authResultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
  gmailBusy = false
  if(result.resultCode == android.app.Activity.RESULT_OK) {
   runCatching { authClient.getAuthorizationResultFromIntent(result.data).accessToken }
    .onSuccess { token -> gmailToken = token; GmailConnectionState.token = token; GmailConnectionState.checked = true; GmailConnectionState.disconnectedByUser = false; gmailMessage = if(token.isNullOrBlank()) "Authorization returned no access token" else "Authorized for this app session" }
    .onFailure { gmailMessage = "Authorization failed: ${it.localizedMessage ?: "Unknown error"}" }
  } else gmailMessage = "Authorization canceled"
 }

 val panelScope = rememberCoroutineScope()
 val allSavedOrders by vm.orders.collectAsState()
 val allSavedNumbers by vm.numbers.collectAsState()
 val allSavedShipments by vm.shipments.collectAsState()
 var aliasGroups by remember { mutableStateOf(emptyList<Set<String>>()) }
 LaunchedEffect(mode) { aliasGroups = vm.retailerAliasGroups() }
 val aliasBook = remember(aliasGroups) { AliasBook.of(aliasGroups) }
 var updateMessage by remember { mutableStateOf("") }
 var updateSummary by remember { mutableStateOf<ScanUpdateSummary?>(null) }
 var shipmentSummary by remember { mutableStateOf<ShipmentUpdateSummary?>(null) }
 var pendingReconnect by remember { mutableStateOf(false) }
 val shipmentMode = mode == "shipment" || mode == "shipments"
 LaunchedEffect(mode, selectedOrder?.id, selectedShipment?.id) {
  updateSummary = null; shipmentSummary = null
  if(mode != "import") updateMessage = "Tap Check Gmail to update ${when(mode) { "single" -> "this order"; "shipment" -> "this shipment"; "shipments" -> "all active shipments"; else -> "saved orders" }}."
 }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  Row { TextButton(onClick = onClose) { Text(if(shipmentMode) "Back to Shipments" else if(mode == "import") "Back to Settings" else "Back to Orders") }; Text(when(mode) { "import" -> "Scan Inbox / Import"; "single" -> "Update Order"; "shipment" -> "Update Shipment"; "shipments" -> "Update All Shipments"; else -> "Update All" }) }
  if(pendingReconnect) AlertDialog(onDismissRequest = { pendingReconnect = false }, title = { Text("Reconnect Gmail") }, text = { Text("Google requires your approval to read Inbox updates.") }, confirmButton = { TextButton(onClick = {
   pendingReconnect = false; gmailBusy = true
   val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/gmail.readonly"))).build()
   authClient.authorize(request).addOnSuccessListener { result ->
    if(result.hasResolution()) { val sender = result.pendingIntent?.intentSender; if(sender != null) authResultLauncher.launch(IntentSenderRequest.Builder(sender).build()) else gmailBusy = false }
    else { gmailToken = result.accessToken; GmailConnectionState.token = result.accessToken; GmailConnectionState.checked = true; GmailConnectionState.disconnectedByUser = false; gmailBusy = false; updateMessage = "Gmail connected. Tap Check Gmail to retry." }
   }.addOnFailureListener { gmailBusy = false; updateMessage = "Gmail connection failed: ${it.localizedMessage}" }
  }) { Text("Reconnect Gmail") } }, dismissButton = { TextButton(onClick = { pendingReconnect = false }) { Text("Cancel") } })
  if(mode != "import") {
   Text(when(mode) {
    "single" -> "Checking ${selectedOrder?.marketplace ?: "order"} #${selectedOrder?.orderNumber ?: ""} only."
    "shipment" -> "Checking mail that names this shipment's saved tracking number (${selectedShipment?.title?.ifBlank { "shipment" } ?: "shipment"})."
    "shipments" -> "Check every active shipment with one Inbox scan. Shipments are never created here."
    else -> "Check all existing orders. New orders are not imported."
   })
   Button(enabled = !scanBusy, onClick = {
    scanBusy = true
    updateSummary = null
    shipmentSummary = null
    panelScope.launch {
     try {
      val token = obtainGmailToken(context) ?: gmailToken
      if(token == null) { pendingReconnect = true; updateMessage = "Gmail authorization required" }
      else {
       val messages = scanGmailPreviews(token) { count -> updateMessage = "Scanning Gmail: $count messages…" }
       if(shipmentMode) {
        val targets = if(mode == "shipment") listOfNotNull(selectedShipment) else allSavedShipments.filter { !it.archived }
        val result = withStatusNotifications(context) { vm.updateShipmentsFromEmails(messages, targets) }
        shipmentSummary = result
        updateMessage = result.headline()
       } else {
       val targets = if(mode == "single") listOfNotNull(selectedOrder) else allSavedOrders
      val result = withStatusNotifications(context) { vm.updateOrdersFromEmails(messages, targets, orderSpecific = mode == "single") }
       updateSummary = result
       updateMessage = result.headline()
       }
      }
     } catch(e: Exception) { updateMessage = "Update failed: ${e.localizedMessage}" }
     finally { scanBusy = false }
    }
   }) { Text(if(scanBusy) "Checking…" else "Check Gmail") }
   Text(updateMessage)
   shipmentSummary?.lines?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
   updateSummary?.let { ScanResultsList(it) }
   updateSummary?.let { NeedsReviewSection(vm, it.reviews) }
  }
  if(mode == "import") Section("Gmail Inbox Import") {
   Text("Inbox-only discovery. Scans all Inbox pages and groups related messages. Review each order before importing. No other Gmail labels are searched.")
   Text(gmailMessage, color = MaterialTheme.colorScheme.secondary)
   if(gmailToken == null) {
    Button(enabled = !gmailBusy, onClick = {
     gmailBusy = true; gmailMessage = "Requesting Google authorization…"
     val request = AuthorizationRequest.builder()
      .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/gmail.readonly"))).build()
     authClient.authorize(request).addOnSuccessListener { result ->
      if(result.hasResolution()) {
       val sender = result.pendingIntent?.intentSender
       if(sender != null) authResultLauncher.launch(IntentSenderRequest.Builder(sender).build())
       else { gmailBusy = false; gmailMessage = "Google authorization requires a resolution that was not provided" }
      } else {
       gmailBusy = false
       gmailToken = result.accessToken
       GmailConnectionState.token = result.accessToken
       GmailConnectionState.checked = true
       GmailConnectionState.disconnectedByUser = false
       gmailMessage = if(gmailToken.isNullOrBlank()) "Authorization returned no access token" else "Authorized for this app session"
      }
     }.addOnFailureListener { error -> gmailBusy = false; gmailMessage = "Google authorization unavailable: ${error.localizedMessage ?: "Unknown error"}" }
    }) { Text(if(gmailBusy) "Connecting…" else "Connect Gmail") }
   } else {
    Text("Connected for this app session. Reauthorization may be required after restart.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
     gmailToken = null; GmailConnectionState.token = null; GmailConnectionState.disconnectedByUser = true; GmailConnectionState.status = "Disconnected locally · Google access not revoked"; gmailMessage = "Session cleared. Google authorization has not been revoked."; scanResults = emptyList(); expandedScanGroups = emptySet(); importOutcomes = emptyMap(); importAllSummary = ""; scanMessage = "No scan performed"
    }) { Text("Clear Gmail Session") }
   }
   run {
    Button(enabled = !scanBusy, onClick = {
     scanBusy = true
     scanResults = emptyList()
     runSummary = null
     expandedScanGroups = emptySet()
     importOutcomes = emptyMap()
     importAllSummary = ""
     scanMessage = "Scanning Gmail…"
     scanScope.launch {
      try {
       val token = obtainGmailToken(context) ?: gmailToken ?: throw IllegalStateException("Reconnect Gmail using the button above")
       val found = scanGmailPreviews(token) { count -> scanMessage = "Scanning Gmail: $count messages fetched…" }
      scanResults = vm.filterDismissedEmails(found)
       GmailConnectionState.lastScan = DateFormat.getDateTimeInstance().format(Date())
       scanMessage = "Importing verified records…"
       val run = withStatusNotifications(context) { vm.autoImportFromMessages(found, reanalyze = true) }
       runSummary = run
       scanMessage = if (found.isEmpty()) "No order or shipment messages found in Inbox." else "Found ${found.size} Inbox messages" +
        (if(lastScanSkippedMessages > 0) " (${lastScanSkippedMessages} could not be fetched and were skipped)" else "") + ". " + run.headline()
      } catch (error: Exception) {
       scanMessage = "Scan failed: ${error.localizedMessage ?: "Unknown error"}"
      } finally { scanBusy = false }
     }
    }) { Text(if (scanBusy) "Scanning…" else "Scan Gmail") }
    Text(scanMessage, style = MaterialTheme.typography.bodySmall)
    if (scanResults.isNotEmpty()) {
     val groups = remember(scanResults, savedTracking, savedShipments, savedOrders, aliasBook, markers) { groupedScanResults(scanResults, savedTracking, savedShipments, savedOrders, aliasBook, manualAssociationOwners(markers), persistedAssociationOwners(markers)) }
     val matched = groups.count { it.shipment != null }
     val scanFacts = remember(scanResults) { scanResults.distinctBy { emailKey(it) }.map(::buildEvidence) }
     val groupedKeys = groups.flatMap { it.emails }.map { emailKey(it) }.toSet()
     val ignoredWithOrder = scanFacts.filter { it.orderNumbers.isNotEmpty() && emailKey(it.email) !in groupedKeys }
     Text("Order confirmations recognized: ${scanFacts.count(::isOrderConfirmation)} · relevant to an order or tracking: ${scanFacts.count(::isRelevantEmail)} · ignored (nothing useful): ${scanFacts.count { !isRelevantEmail(it) }}", style = MaterialTheme.typography.bodySmall)
     if(ignoredWithOrder.isNotEmpty()) {
      val showIgnored = "ignored" in expandedScanGroups
      TextButton(onClick = { expandedScanGroups = if(showIgnored) expandedScanGroups - "ignored" else expandedScanGroups + "ignored" }) {
       Text(if(showIgnored) "Hide ignored messages that mention an order number" else "Ignored messages that mention an order number (${ignoredWithOrder.size})")
      }
      if(showIgnored) ignoredWithOrder.take(40).forEach { Text("${it.email.sender.substringBefore('<').trim()} · ${it.email.subject.ifBlank { "(No subject)" }} · order ${it.orderNumbers.first()}", style = MaterialTheme.typography.bodySmall) }
     }
     HorizontalDivider()
     Text("INBOX SCAN RESULTS — REVIEW & IMPORT", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
     Text("${scanResults.size} emails scanned · ${groups.size} unique groups · $matched saved shipment matches", style = MaterialTheme.typography.bodyMedium)
     Text("Verified orders and shipments are imported automatically. Existing orders keep their data; ambiguous mail waits in Needs review.", style = MaterialTheme.typography.bodySmall)
     runSummary?.let { run ->
      Text(run.headline(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
      run.outcomes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
     }
     if(importMessage.isNotBlank()) Text(importMessage, color = MaterialTheme.colorScheme.primary)
     val importable = remember(groups, savedOrders, aliasBook) { importableGroups(groups, savedOrders, aliasBook) }
     if (importable.isNotEmpty()) Text("${importable.size} verified ${if(importable.size == 1) "record was" else "records were"} not imported automatically. Use the button on each card to retry or review it.", style = MaterialTheme.typography.bodySmall)
     // Groups that can be neither imported nor applied are listed only under Needs review, where they can be associated.
     val actionable = groups.filter { g -> g.key.startsWith("room:") || g.key.startsWith("shipment:saved:") || g.key in importOutcomes || importable.any { it.key == g.key } }
     if (importable.isEmpty() && groups.isNotEmpty()) {
      Text("Verified records were imported automatically; anything ambiguous is listed under Needs review.", style = MaterialTheme.typography.bodySmall)
      OutlinedButton(onClick = { scanResults = emptyList(); importOutcomes = emptyMap(); importAllSummary = ""; runSummary = null }) { Text("Exit") }
     }
     if (importAllSummary.isNotBlank()) Text(importAllSummary, color = MaterialTheme.colorScheme.primary)
     actionable.forEach { group ->
      ElevatedCard(Modifier.fillMaxWidth()) {
       Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(group.title, style = MaterialTheme.typography.titleSmall)
        Text("${group.emails.size} ${if (group.emails.size == 1) "email" else "emails"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        group.orderNumber?.let { Text("Order #$it", style = MaterialTheme.typography.bodySmall) }
        if(group.basis.isNotEmpty()) Text("Evidence: ${group.basis}", style = MaterialTheme.typography.labelSmall)
        dedupeTracking(group.emails.flatMap(::extractTrackingCandidates)).takeIf { it.isNotEmpty() }?.let { Text("Tracking: ${it.joinToString()}", style = MaterialTheme.typography.bodySmall) }
        Text(group.review ?: "Preview only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        group.key.removePrefix("room:").toLongOrNull()?.takeIf { group.key.startsWith("room:") }?.let { orderId ->
         savedShipments.filter { it.orderId == orderId }.forEachIndexed { index, shipment ->
          Text("Shipment ${index + 1} \u00b7 ${shipment.status}" + savedTracking.filter { it.shipmentId == shipment.id }.joinToString { " \u00b7 ${it.number}" }.let { if(it.isBlank()) "" else " \u00b7 " + it.trim().removePrefix("\u00b7").trim() }, style = MaterialTheme.typography.bodySmall)
         }
        }
        importOutcomes[group.key]?.let { outcome ->
         Text("Import result: $outcome", color = if (outcome.startsWith("Imported") || outcome.startsWith("Existing")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if(importable.any { it.key == group.key }) {
         Button(enabled = !importBusy && group.review?.startsWith("Conflicting") != true, onClick = {
          importBusy = true
          scanScope.launch {
           try {
            val outcome = vm.importScanGroup(group)
            importOutcomes = importOutcomes + (group.key to outcome)
            importMessage = "${group.title}: $outcome"
           } catch (e: Exception) {
            val outcome = "Failed: ${e.localizedMessage ?: "Unknown error"}"
            importOutcomes = importOutcomes + (group.key to outcome)
            importMessage = "${group.title}: $outcome"
           }
           finally { importBusy = false }
          }
         }) { Text(if (importOutcomes[group.key]?.startsWith("Imported") == true || importOutcomes[group.key]?.startsWith("Existing") == true) "Check / Import Again" else if(group.key.startsWith("shipment:")) "Import This Shipment" else "Import This Order") }
        }
        if(group.key.startsWith("room:") || group.key.startsWith("shipment:saved:")) {
         Button(enabled = !importBusy, onClick = {
          importBusy = true
          scanScope.launch {
           val outcome = try { vm.importScanGroup(group) } catch(e: Exception) { "Failed: ${e.localizedMessage ?: "Unknown error"}" }
           importOutcomes = importOutcomes + (group.key to outcome)
           importMessage = "${group.title}: $outcome"
           importBusy = false
          }
         }) { Text(if(group.key.startsWith("shipment:")) "Apply updates to this shipment" else "Apply updates to this order") }
        }
        val open = group.key in expandedScanGroups
        OutlinedButton(onClick = {
         expandedScanGroups = if (open) expandedScanGroups - group.key else expandedScanGroups + group.key
        }) { Text(if (open) "Hide correspondence" else "View correspondence (${group.emails.size})") }
        if (open) group.emails.forEach { email ->
         HorizontalDivider()
         Text(email.subject.ifBlank { "(No subject)" }, style = MaterialTheme.typography.bodyMedium)
         Text("From: ${email.sender}", style = MaterialTheme.typography.bodySmall)
         Text(email.date, style = MaterialTheme.typography.bodySmall)
         Text(email.snippet.take(240), style = MaterialTheme.typography.bodySmall)
         val finding = extractEmailFinding(email, savedTracking, savedShipments)
         Text("Tracking: ${extractTrackingCandidates(email).joinToString().ifBlank { "Not identified" }} · Carrier: ${finding.carrier ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
         Text("Status suggestion: ${finding.status ?: "Not identified"}", style = MaterialTheme.typography.bodySmall)
        }
       }
      }
     }
     val previewReviews = groups.filter { g -> actionable.none { it.key == g.key } }.flatMap { group ->
      group.emails.map { email ->
       val candidates = (group.candidateOrderIds + savedOrders.filter { order ->
        (group.orderNumber == null || normalizeId(order.orderNumber) == normalizeId(group.orderNumber)) &&
         retailerEvidence(order, email, aliasBook) != RetailerEvidence.NONE
       }.map { it.id }).distinct()
       ReviewItem.EmailNeedsReview(email, group.review ?: "Relevant correspondence could not be matched safely", candidates,
        extractTrackingCandidates(email))
      }
     }
     NeedsReviewSection(vm, previewReviews)
    }
   }
  if(mode == "import") {
   val globalDuplicates = remember(allSavedOrders, aliasBook) {
    findDuplicateOrders(allSavedOrders, aliasBook).map { ReviewItem.PossibleDuplicate(it.first.id, it.second.id, it.sameRetailer) }
   }
   NeedsReviewSection(vm, globalDuplicates)
  }
   Text("Clear Gmail Session only removes the in-memory token. To revoke account access, use Google Account settings. Full disconnect will be implemented separately.", style = MaterialTheme.typography.bodySmall)
   Text("Requires a Google Cloud OAuth configuration for this Android package and signing certificate. No API keys or secrets are bundled.", style = MaterialTheme.typography.bodySmall)
  }
 }
}

@Composable internal fun GmailAccountsSection(s: TrackerSettings, onScanImport: () -> Unit) {
  Section("Email Accounts") {
   val (connectGmail, connectingGmail) = rememberGmailAuthorization()
   val connected = !GmailConnectionState.token.isNullOrBlank()
   var accountMenu by remember { mutableStateOf(false) }
   var disconnectDialog by remember { mutableStateOf(false) }
   Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text("Gmail", style = MaterialTheme.typography.titleMedium)
    Text(if(connected) "Connected" else "Disconnected", modifier = Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
    Box {
     IconButton(onClick = { accountMenu = true }, modifier = Modifier.semantics { contentDescription = "Gmail account menu" }) { Text("\u22ee", style = MaterialTheme.typography.headlineSmall) }
     DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
      DropdownMenuItem(text = { Text("Disconnect session") }, enabled = connected, onClick = { accountMenu = false; disconnectDialog = true })
     }
    }
   }
   Text("Read-only access to Gmail Inbox.", style = MaterialTheme.typography.bodySmall)
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(enabled = !connectingGmail, onClick = connectGmail, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
     Text(if(connectingGmail) "Connecting..." else if(connected) "Reconnect Email" else "Connect to Email", maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
    OutlinedButton(onClick = onScanImport, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) { Text("Scan / Import", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
   }
   Text("Checks: every ${s.refreshHours} hours (best-effort)", style = MaterialTheme.typography.bodySmall)
   Text("Last successful scan: ${GmailConnectionState.lastScan}", style = MaterialTheme.typography.bodySmall)
   if(disconnectDialog) AlertDialog(onDismissRequest = { disconnectDialog = false }, title = { Text("Disconnect Gmail session?") }, text = {
    Text("This clears the application's temporary Gmail session token. It does not revoke Google account authorization.")
   }, confirmButton = { TextButton(onClick = {
    GmailConnectionState.token = null
    GmailConnectionState.disconnectedByUser = true
    GmailConnectionState.status = "Disconnected locally · Google access not revoked"
    disconnectDialog = false
   }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnectDialog = false }) { Text("Cancel") } })
   HorizontalDivider()
   Text("Yahoo Mail · Coming Soon")
   Text("Outlook · Coming Soon")
   Text("Other email providers · Coming Soon")
  }
}

@Composable private fun SettingsScreen(vm: TrackerVm, s: TrackerSettings, markets: List<CustomMarketplaceRecord>, onScanImport: () -> Unit) {
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  Spacer(Modifier.height(4.dp))
  GmailAccountsSection(s, onScanImport)
  Section("Notifications") {
   Text("Notifications depend on Android permission and supported status changes.", style = MaterialTheme.typography.bodySmall)
   val notificationContext = LocalContext.current
   var notificationsGranted by remember { mutableStateOf(BackgroundSync.notificationsAllowed(notificationContext)) }
   val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }
   if(!notificationsGranted) {
     Text("Android notification permission is off, so background updates cannot alert you. If the prompt does not appear, enable it in Android app settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
     Button(onClick = { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
   }
   SettingSwitch("Order progress (Processing, Preparing for Shipment)", s.notifyOrder) { vm.toggle("notifyOrder", it) }
   SettingSwitch("Shipped", s.notifyShipped) { vm.toggle("notifyShipped", it) }
   SettingSwitch("Out for delivery", s.notifyOut) { vm.toggle("notifyOut", it) }
   SettingSwitch("Delivered", s.notifyDelivered) { vm.toggle("notifyDelivered", it) }
   SettingSwitch("Delay, exception or cancelled order", s.notifyException) { vm.toggle("notifyException", it) }
   SettingSwitch("Every tracking update", s.notifyEvery) { vm.toggle("notifyEvery", it) }
  }
  Section("Tracking") {
   Choice("Check interval (hours)", s.refreshHours, listOf("1", "3", "6", "8", "12")) { vm.text("refreshHours", it) }
   Text("Gmail checks for new orders and shipment updates run in the background on this interval. Android decides the exact timing, and Google reauthorization may be required. Carrier APIs are deferred.", style = MaterialTheme.typography.bodySmall)
   Choice("Destination country", s.country, listOf("United States", "Canada", "United Kingdom", "Other")) { vm.text("country", it) }
   Field("Default postal code", s.postalCode, { vm.text("postalCode", it) })
  }
  Section("Archiving") {
   SettingSwitch("Automatically archive delivered orders", s.autoArchive) { vm.toggle("autoArchive", it) }
   Choice("Archive after (days)", s.archiveDays, listOf("7", "14", "30", "60")) { vm.text("archiveDays", it) }
   Text("Delivered records are archived when the app opens or data changes after the selected delay. Background scheduling is not active.", style = MaterialTheme.typography.bodySmall)
  }
  Section("Storage & Maintenance") {
   val context = LocalContext.current
   val scope = rememberCoroutineScope()
   var cleanResult by remember { mutableStateOf("") }
   var databaseBytes by remember { mutableLongStateOf(0L) }
   var showDeleteWarning by remember { mutableStateOf(false) }
   var deleteText by remember { mutableStateOf("") }
   var recordSummary by remember { mutableStateOf("") }
   Choice("Delete archived orders after (days)", s.archivedRetentionDays, listOf("7", "14", "30", "60", "90", "180", "365", "Never")) { vm.text("archivedRetentionDays", it) }
   Text("Default: 30 days. Restoring an order cancels its expiry. Older archived records are preserved until re-archived.", style = MaterialTheme.typography.bodySmall)
   HorizontalDivider()
   Text("Temporary Storage", style = MaterialTheme.typography.titleMedium)
   Text("Temporary scan results are held in memory. The cleanup button removes disposable app cache files only.", style = MaterialTheme.typography.bodySmall)
   OutlinedButton(onClick = {
    scope.launch(Dispatchers.IO) {
     val freed = StorageMaintenance.cleanTemporary(context)
     withContext(Dispatchers.Main) { cleanResult = "Removed ${freed / 1024} KB of temporary files"; databaseBytes = StorageMaintenance.storageBytes(context) }
    }
   }) { Text("Clean Up Temporary Storage") }
   OutlinedButton(onClick = { databaseBytes = StorageMaintenance.storageBytes(context) }) { Text("Check Database Size") }
   Text("Database storage: ${databaseBytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
   if(cleanResult.isNotBlank()) Text(cleanResult, style = MaterialTheme.typography.bodySmall)
   HorizontalDivider()
   Text("Danger Zone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
   Text("Delete all saved orders, shipments, tracking numbers and history. Gmail authorization and display preferences remain. This cannot be undone without a backup.", style = MaterialTheme.typography.bodySmall)
   OutlinedButton(onClick = {
    scope.launch {
     val dao = PackageDatabase.get(context).orderDao()
     val counts = withContext(Dispatchers.IO) { listOf(dao.activeCount(), dao.archivedCount(), dao.shipmentCount(), dao.trackingCount()) }
     recordSummary = "${counts[0]} active orders, ${counts[1]} archived orders, ${counts[2]} shipments, ${counts[3]} tracking numbers"
     showDeleteWarning = counts.any { it > 0 }
     if(!showDeleteWarning) cleanResult = "No saved order or shipment records to delete"
    }
   }) { Text("Delete All App Data") }
   if(showDeleteWarning) AlertDialog(onDismissRequest = { showDeleteWarning = false; deleteText = "" },
    title = { Text("Permanently delete saved data?") },
    text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
     Text("You are about to delete $recordSummary, plus related history. This cannot be undone.")
     Text("Type DELETE ALL to confirm")
     OutlinedTextField(deleteText, { deleteText = it }, label = { Text("Confirmation") })
    } },
    confirmButton = { TextButton(enabled = deleteText == "DELETE ALL", onClick = {
     showDeleteWarning = false; deleteText = ""
     scope.launch(Dispatchers.IO) {
      val db = PackageDatabase.get(context)
      db.withTransaction {
       val dao = db.orderDao()
       dao.allOrderKeys().forEach { dao.markDeleted(DeletedOrderMarker(marketplace = it.marketplace, orderNumber = it.orderNumber)) }
       dao.deleteAllOrders()
       dao.deleteAllShipments()
       dao.deleteLegacyPackages()
       dao.clearProcessedMessages()
       dao.clearChangeHistory()
      }
      withContext(Dispatchers.Main) { cleanResult = "Saved order and shipment records deleted. Previously deleted orders will not automatically reimport." }
     }
    }) { Text("Delete permanently") } },
    dismissButton = { TextButton(onClick = { showDeleteWarning = false; deleteText = "" }) { Text("Cancel") } })
  }
  Section("Display & Sorting") {
   SettingSwitch("Most recent status first", s.sortRecent) { vm.toggle("sortRecent", it) }
   SettingSwitch("Untracked orders last", s.missingLast) { vm.toggle("missingLast", it) }
   SettingSwitch("Out for delivery first", s.deliveryFirst) { vm.toggle("deliveryFirst", it) }
   SettingSwitch("Show carrier labels", s.showCarrierLabels) { vm.toggle("showCarrierLabels", it) }
   SettingSwitch("Show carrier logos", s.showCarrierLogos) { vm.toggle("showCarrierLogos", it) }
  }
  Section("Appearance") { Choice("Theme", s.appearance, listOf("Dark", "Light", "System")) { vm.text("appearance", it) }; Choice("Tracking language", s.language, listOf("English", "Spanish", "French")) { vm.text("language", it) } }
  Section("Custom Marketplaces") {
   if(markets.isEmpty()) Text("No custom marketplaces yet. Add one from the Add tab.", style = MaterialTheme.typography.bodySmall)
   markets.forEach { market ->
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var name by remember(market.name) { mutableStateOf(market.name) }
    var website by remember(market.website) { mutableStateOf(market.website) }
    Text(market.name)
    Row { TextButton(onClick = { editing = true }) { Text("Edit") }; TextButton(onClick = { deleting = true }) { Text("Remove") } }
    if(editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("Edit marketplace") }, text = { Column { Field("Name", name, { name = it }); Field("Website", website, { website = it }) } }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.editMarket(market, name, website); editing = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
    if(deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Remove marketplace?") }, text = { Text("Existing orders will keep their marketplace names.") }, confirmButton = { TextButton(onClick = { vm.removeMarket(market); deleting = false }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
   }
  }
  Section("Retailer Aliases") {
   var groups by remember { mutableStateOf(emptyList<Set<String>>()) }
   LaunchedEffect(Unit) { groups = vm.retailerAliasGroups() }
   Text("Names listed together are treated as one retailer, so one purchase is not imported twice. Equal order numbers at other retailers are never merged automatically.", style = MaterialTheme.typography.bodySmall)
   if(groups.isEmpty()) Text("No aliases.", style = MaterialTheme.typography.bodySmall)
   groups.forEach { group -> Text(group.joinToString(" = ") + if(group.any { name -> AliasBook.builtIn.any { it.any { b -> lowerAlnum(b) == lowerAlnum(name) } } }) "  (built in)" else "  (confirmed by you)") }
   Text("Add an alias by merging duplicate orders from the Needs Review list and choosing to treat the names as one retailer.", style = MaterialTheme.typography.bodySmall)
  }
  Section("Marketplace Imports · Phase 3") { Text("Import from Amazon, eBay, AliExpress and other retailers when authorized integrations are available."); Text("Not available yet.", style = MaterialTheme.typography.bodySmall) }
  Section("About · Beta") { Text("Pixel Package Tracker · v1.0.2"); Text("Personal-use release for emulator testing. Gmail Inbox scans and scheduled background checks require Google authorization. Live carrier API tracking is not implemented."); Text("Future: Yahoo Mail, Outlook, and authorized free carrier API integrations.", style = MaterialTheme.typography.bodySmall) }
  Spacer(Modifier.height(16.dp))
 }
}
