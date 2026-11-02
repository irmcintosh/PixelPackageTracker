package com.example.pixelpackagetracker

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Pure (no Android, no database) Gmail evidence extraction and staged email-to-order assignment.
// Assignment never guesses: anything that could belong to more than one order is Ambiguous.

/** Bump when extraction logic improves; messages processed by an older version may be re-analyzed. */
internal const val GMAIL_PARSER_VERSION = 3

/** Marker version written for a user-confirmed association; it is authoritative and never reanalyzed. */
internal const val MANUAL_ASSOCIATION_VERSION = 1000

internal fun shouldProcessMessage(markerVersion: Int?, reanalyze: Boolean): Boolean =
 markerVersion == null || (reanalyze && markerVersion < GMAIL_PARSER_VERSION)

internal fun normalizeId(value: String?): String = (value ?: "").filter(Char::isLetterOrDigit).uppercase(Locale.US)

internal fun possibleOrderNumberVariant(first: String, second: String): Boolean {
 val a = normalizeId(first); val b = normalizeId(second)
 val longer = if(a.length > b.length) a else b
 val shorter = if(a.length > b.length) b else a
 return shorter.length >= 7 && longer.length == shorter.length + 1 && longer.firstOrNull()?.isLetter() == true && longer.drop(1) == shorter
}

internal fun lowerAlnum(value: String) = value.filter(Char::isLetterOrDigit).lowercase(Locale.US)

internal fun emailKey(email: GmailPreview): String = email.messageId.ifBlank { "${email.threadId}|${email.date}|${email.subject}" }

// An identifier is present only as a whole token, so a short number never matches inside a longer one.
// Maximal chains of alphanumeric runs joined by single hyphens ("16-15304-06041") or digit groups joined by single
// spaces ("9400 1118 9922") are offered joined; sub-chains are not, so a prefix of a longer number never matches.
internal fun identifierTokens(text: String): Set<String> {
 val runs = Regex("[A-Za-z0-9]+").scan(text)
 val tokens = HashSet<String>(runs.size * 2)
 runs.forEach { tokens += it.value.uppercase(Locale.US) }
 fun chain(separator: Char, accept: (RegexHit) -> Boolean) {
  var i = 0
  while(i < runs.size) {
   if(!accept(runs[i])) { i++; continue }
   var j = i
   var joined = runs[i].value
   while(j + 1 < runs.size && accept(runs[j + 1]) && runs[j + 1].range.first - runs[j].range.last == 2 &&
    text[runs[j].range.last + 1] == separator && joined.length + runs[j + 1].value.length <= 34) { j++; joined += runs[j].value }
   if(j > i) tokens += joined.uppercase(Locale.US)
   i = j + 1
  }
 }
 chain('-') { true }
 chain(' ') { it.value.length in 2..8 && it.value.all(Char::isDigit) }
 return tokens
}

private val orderLabels = listOf(
 Regex("(?i)\\border\\s*(?:number|no\\.?|#|id|ref(?:erence)?)\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9-]{3,26}[A-Z0-9])"),
 Regex("(?i)\\border\\s*:\\s*#?\\s*([A-Z0-9][A-Z0-9-]{3,26}[A-Z0-9])"),
 // "Order 28855918" / "Order DSDF66669184": no label word, so the token itself must look like an order id.
 Regex("(?i)\\border\\s+#?\\s*([A-Z]{0,6}[0-9][A-Z0-9-]{5,26})\\b"))

// Explicit, labeled order numbers only; a candidate must contain a digit so words such as "Items" are never order IDs.
internal fun extractOrderNumbers(content: String): List<String> =
 orderLabels.flatMap { re -> re.scan(content).map { it.groupValues[1].trim('-') } }
  .filter { it.length >= 5 && it.any(Char::isDigit) }
  .distinctBy(::normalizeId)

private val noTrackingBefore = Regex("(?i)(?:order|invoice|reference|ref|po|account|phone|sku|item|confirmation)\\s*(?:number|no\\.?|#|id)?\\W*$")

private fun validTrackingToken(token: String): Boolean = isPlausibleTrackingToken(token)

// USPS barcodes prepend "420" + a 5- or 9-digit ZIP to the 20-22 digit tracking number.
private fun stripUspsRouting(raw: String): String? {
 if(!raw.all(Char::isDigit) || raw.length !in 28..34 || !raw.startsWith("420")) return null
 return listOf(8, 12).map { raw.drop(it) }.firstOrNull { it.length in 20..22 && it.startsWith("9") }
}

// Equivalence is exact after normalization; the only rewrite is the carrier-specific USPS routing prefix above.
internal fun sameTrackingNumber(a: String, b: String): Boolean = normalizeId(a) == normalizeId(b)

internal fun dedupeTracking(numbers: List<String>): List<String> = numbers.distinctBy(::normalizeId)

/** Canonical tracking numbers paired with the spelling the email used, so history can show the original. */
internal fun extractTrackingDetails(email: GmailPreview): List<Pair<String, String>> {
 val content = "${email.subject}\n${email.snippet}\n${email.body}"
 val orderKeys = extractOrderNumbers(content).map(::normalizeId).toSet()
 val found = mutableListOf<Pair<String, String>>()
 fun add(raw: String) {
  val token = stripUspsRouting(raw) ?: if(raw.all(Char::isDigit)) raw else Regex("^\\d{20,22}").find(raw)?.value ?: raw
  if(validTrackingToken(token) && normalizeId(token) !in orderKeys) found += token to raw
 }
 extractTrackingLinks(content).forEach { found += it.number to it.number }
 // Label directly followed by the number.
 Regex("(?i)(?:tracking(?:\\s+(?:number|no\\.?|#|id|code))?|track\\s*#)\\s*[:#-]?\\s*([A-Z0-9]{10,30})").scan(content).forEach { add(it.groupValues[1]) }
 // Label followed by a short phrase in the same sentence ("tracking number below into FedEx: 38429466112").
 Regex("(?i)(?:tracking(?:\\s+(?:number|no\\.?|#|id|code))?|track\\s*#)([^\\n.!?]{0,120})").scan(content).forEach { m ->
  val tail = m.groupValues[1]
  Regex("(?<![A-Za-z0-9])([A-Z0-9]{10,30})(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).scan(tail).forEach { t ->
   if(!noTrackingBefore.containsMatchIn(tail.substring(0, t.range.first))) add(t.groupValues[1])
  }
 }
 val fedexContext = Regex("(?i)\\b(?:fedex|tracking|shipment|track your order)\\b").containsMatchIn(content)
 Regex("(?i)(?:tracking number[^\\n]{0,100}?(?:fedex|ups|usps)\\s*[:#-]?\\s*|shipment is on the way\\s+)([0-9]{11,22})(?![0-9])")
  .find(content)?.groupValues?.get(1)?.let { add(it) }
 if(email.subject.contains("shipment", true) && fedexContext)
  Regex("(?<![0-9])([0-9]{11,22})(?![0-9])").find(email.subject)?.groupValues?.get(1)?.let { add(it) }
 return found.distinctBy { normalizeId(it.first) }
}

internal fun extractTrackingCandidates(email: GmailPreview): List<String> = extractTrackingDetails(email).map { it.first }

internal class EmailEvidence(
 val email: GmailPreview,
 val key: String,
 val orderNumbers: List<String>,
 val orderNumberKeys: Set<String>,
 val trackingCandidates: List<String>,
 val tokens: Set<String>,
 val kind: EmailKind,
 val carrierSender: Boolean,
 // Computed once with the kind; true only when a shipping notice carries explicit dispatch or carrier-acceptance wording.
 val dispatch: Boolean = false
) {
 /** In Transit needs dispatch wording; a tracking-number-only notice is a created label. */
 val shipmentStatus: String? get() = if(kind == EmailKind.SHIPPED && !dispatch) "Label Created" else statusForKind(kind)
}

private val orderPlacedPhrase = Regex("(?i)\\b(?:thank(?:s| you) for (?:your|the) (?:order|purchase)|your order (?:has been |was |is )?(?:placed|received|confirmed)|order (?:has been |was )?(?:placed|received|confirmed)|we(?:'ve| have)? received your (?:order|purchase)|(?:receipt|confirmation) for (?:your |the )?(?:order|purchase)|order (?:summary|receipt))\\b")
/** A genuine new-order confirmation: a confirmation subject, or order wording/purchase details beside an order number, and never a shipping notice. */
internal fun isOrderConfirmation(ev: EmailEvidence): Boolean {
 val email = ev.email
 if(isConfirmationSubject(email.subject)) return true
 if(ev.orderNumbers.isEmpty() || isShippingKind(ev.kind)) return false
 if(hasConfirmationHeader(email) ||
  orderPlacedPhrase.containsMatchIn("${email.subject}\n${email.snippet}\n${email.body.take(6000)}")) return true
 val details = extractOrderDetails(email)
 return details.placedAt != null && (details.items.isNotEmpty() || details.notes.contains("Order total"))
}

/** Only mail that can change Notes, Placed date, Order status or Tracking is processed; everything else is ignored. */
internal fun isRelevantEmail(ev: EmailEvidence): Boolean {
 if(extractDeliveryEstimate(ev.email).let { it.estimate != null || it.problem != null }) return true
 if(isOrderConfirmation(ev) || statusForKind(ev.kind) != null || orderStatusNotice(ev.email, ev.kind) != null || ev.trackingCandidates.isNotEmpty() || orderEligibility(ev).eligible) return true
 val details = extractOrderDetails(ev.email)
 return details.placedAt != null || details.notes.isNotBlank() || details.orderUrl.isNotBlank() || details.items.isNotEmpty()
}

internal data class OrderEligibility(val eligible: Boolean, val basis: String, val reason: String, val signals: List<String> = emptyList())

private val nonOrderSubject = Regex("(?i)\\b(?:refunds?|refunded|returns?|returned|cancel(?:l?ed|lation)?|survey|feedback|rate your|review your|newsletter|coupons?|promo(?:tion|tional)?|rewards?|gift card|dispute|support|ticket|price drop|back in stock|recommended|you may also like|sales?|deals?)\\b|\\d+\\s?% off|save \\$")
private val orderUpdateWording = Regex("(?i)\\border (?:update|status)\\b")
private val deliveryInfoWording = Regex("(?i)\\b(?:estimated delivery|expected delivery|delivery (?:date|estimate|window)|(?:estimated|expected) arrival|arriv(?:es|ing)\\b|get it by|delivered (?:on|to))")
private val orderLinkHint = Regex("(?i)order|transid|itemid|invoice")

private fun hasOrderLink(content: String, senderBrand: String, orderKey: String): Boolean =
 Regex("(?i)https?://[^\\s\\]>\"')]+").scan(content).any { m ->
  val url = m.value
  val host = url.substringAfter("//").substringBefore('/').substringBefore('?')
  senderBrand.isNotEmpty() && domainBrand(host) == senderBrand && (normalizeId(url).contains(orderKey) || orderLinkHint.containsMatchIn(url))
 }

/**
 * The single decision on whether one email reliably establishes a new order, shared by scan grouping, import validation and the Import UI.
 * A confirmation with one order number suffices. Otherwise a retailer notification needs a verified retailer sender (not shared mail, a payment
 * processor or a carrier), exactly one labeled order number, no marketing/return/support subject, and at least two order-specific signals.
 */
internal fun orderEligibility(ev: EmailEvidence): OrderEligibility {
 val email = ev.email
 val numbers = ev.orderNumbers.distinctBy(::normalizeId)
 if(isOrderConfirmation(ev)) return OrderEligibility(numbers.size == 1, "Order confirmation", if(numbers.size == 1) "order confirmation" else "confirmation without one unambiguous order number")
 if(numbers.size != 1) return OrderEligibility(false, "", if(numbers.isEmpty()) "no order number" else "several order numbers")
 if(ev.carrierSender) return OrderEligibility(false, "", "carrier mail cannot establish an order")
 val brand = domainBrand(senderDomain(email.sender))
 if(paymentProcessorName(email.sender) != null || brand in sharedMailDomains || retailerNameFromSender(email.sender).isBlank())
  return OrderEligibility(false, "", "the sender is not a verified retailer")
 if(nonOrderSubject.containsMatchIn(email.subject)) return OrderEligibility(false, "", "marketing, return or support correspondence")
 val content = "${email.subject}\n${email.snippet}\n${email.body.take(8000)}"
 val signals = buildList {
  if(statusForKind(ev.kind) != null || orderUpdateWording.containsMatchIn(email.subject) || orderStatusNotice(email, ev.kind) != null) add("order status")
  if(subjectItemName(email.subject) != null || extractOrderDetails(email).items.isNotEmpty()) add("item")
  if(deliveryInfoWording.containsMatchIn(content)) add("delivery information")
  if(hasOrderLink(content, brand, normalizeId(numbers.single()))) add("order link")
  if(ev.trackingCandidates.isNotEmpty() && detectCarrier(email, ev.trackingCandidates.first(), strict = true) != null) add("tracking")
 }
 return if(signals.size >= 2) OrderEligibility(true, "Retailer shipping notice", "verified retailer, order number and ${signals.joinToString(", ")}", signals)
 else OrderEligibility(false, "", "only ${signals.size} order-specific detail${if(signals.size == 1) "" else "s"} found (${signals.joinToString().ifBlank { "none" }}); at least two are needed", signals)
}

internal data class StandaloneEvidence(val tracking: String, val carrier: String, val status: String)

/** A genuine shipment that needs no order: one plausible tracking number, a carrier from strong evidence, and a positive shipping status. */
internal fun standaloneShipmentEvidence(ev: EmailEvidence): StandaloneEvidence? {
 val status = ev.shipmentStatus ?: return null
 val tracking = dedupeTracking(ev.trackingCandidates).singleOrNull() ?: return null
 val carrier = detectCarrier(ev.email, tracking, strict = true) ?: return null
 return StandaloneEvidence(tracking, carrier, status)
}

internal fun buildEvidence(email: GmailPreview): EmailEvidence {
 val content = "${email.subject} ${email.snippet} ${email.body}"
 val orders = extractOrderNumbers(content)
 val tracking = extractTrackingCandidates(email)
 val kind = classifyEmail(email, tracking.isNotEmpty())
 return EmailEvidence(email, emailKey(email), orders, orders.map(::normalizeId).toSet(), tracking, identifierTokens(content),
  kind, isCarrierSender(email.sender), dispatch = confirmsDispatch(email, kind))
}

/** Retailer names that are the same retailer. Seeded aliases are verified; others come from explicit user confirmation. */
internal class AliasBook private constructor(val groups: List<Set<String>>) {
 private val byKey: Map<String, Set<String>> = groups.flatMap { g -> g.map { lowerAlnum(it) to g } }.toMap()
 fun namesFor(name: String): List<String> {
  val group = byKey[lowerAlnum(name)] ?: return listOf(name)
  return listOf(name) + group.filter { lowerAlnum(it) != lowerAlnum(name) }
 }
 fun same(a: String, b: String): Boolean {
  val x = lowerAlnum(a); val y = lowerAlnum(b)
  return x == y || (byKey[x] != null && byKey[x] === byKey[y])
 }
 fun canonical(name: String): String = byKey[lowerAlnum(name)]?.map(::lowerAlnum)?.sorted()?.joinToString("|") ?: lowerAlnum(name)
 companion object {
  // DSWATCH and DS Designer were reported by the user as one retailer (same DSDF order numbers).
  val builtIn: List<Set<String>> = listOf(setOf("DSWATCH", "DS Designer"))
  val default = of(emptyList())
  fun of(user: List<Set<String>>): AliasBook {
   val merged = mutableListOf<MutableSet<String>>()
   (builtIn + user).forEach { group ->
    val keys = group.map(::lowerAlnum).toSet()
    val overlapping = merged.filter { m -> m.map(::lowerAlnum).any { it in keys } }
    val target = overlapping.firstOrNull() ?: mutableSetOf<String>().also { merged += it }
    overlapping.drop(1).forEach { target += it; merged.remove(it) }
    target += group
   }
   return AliasBook(merged.filter { it.size > 1 })
  }
  fun encode(groups: List<Set<String>>): String = groups.joinToString("\n") { g -> g.joinToString("|") { it.replace('|', ' ').replace('\n', ' ') } }
  fun decode(raw: String): List<Set<String>> = raw.lines().map { line -> line.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.filter { it.size > 1 }
 }
}

internal enum class RetailerEvidence { NONE, WEAK, STRONG }

private val genericMarketplaces = setOf("", "other")
private val brandRegexCache = ConcurrentHashMap<String, java.util.Optional<Regex>>()

internal fun domainBrand(domain: String): String {
 val labels = domain.lowercase(Locale.US).trim().split('.').filter { it.isNotBlank() }
 if(labels.size < 2) return labels.firstOrNull().orEmpty()
 val second = labels[labels.size - 2]
 return if(second in setOf("co", "com", "org", "net", "gov", "ac") && labels.size >= 3) labels[labels.size - 3] else second
}

// The address is the final <...>; a display name may itself contain an address ("service@paypal.com" <service@paypal.com>).
internal fun senderDomain(sender: String): String {
 val address = Regex("<([^<>]*)>\\s*$").find(sender.trim())?.groupValues?.get(1) ?: sender
 return address.substringAfterLast('@', "").trim().lowercase(Locale.US)
}

internal fun retailerNameFromSender(sender: String): String {
 val raw = sender.substringBefore('<').trim()
 val display = if(raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1).trim() else raw
 val generic = setOf("customerservice", "service", "support", "orders", "notifications", "noreply")
 // Malformed headers (stray quotes, embedded addresses) fall back to the domain brand.
 return display.takeIf { it.isNotBlank() && display.none { c -> c in "\"<>@" } && lowerAlnum(it) !in generic }
  ?: domainBrand(senderDomain(sender)).replaceFirstChar { it.uppercase(Locale.US) }
}

internal val sharedMailDomains = setOf("gmail", "googlemail", "yahoo", "outlook", "hotmail", "live", "icloud", "aol", "aliyun", "qq", "163", "126", "proton", "protonmail")

internal fun senderRetailerKey(sender: String, aliases: AliasBook = AliasBook.default): String {
 val domain = senderDomain(sender)
 val brand = domainBrand(domain)
 val display = sender.substringBefore('<').trim().trim('"')
 val alias = listOf(display, brand).map(aliases::canonical).firstOrNull { it.contains('|') }
 return alias?.let { "alias:$it" }
  ?: if(brand in sharedMailDomains) brand + display.filter(Char::isLetterOrDigit).lowercase(Locale.US)
  else brand.ifBlank { domain }
}

private fun brandRegex(name: String): Regex? = brandRegexCache.getOrPut(name) {
 val words = name.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
 if(words.joinToString("").length < 3) java.util.Optional.empty()
 else java.util.Optional.of(Regex("(?<![a-z0-9])" + words.joinToString("[\\s._'\u2019-]*") { Regex.escape(it) } + "(?![a-z0-9])", RegexOption.IGNORE_CASE))
}.orElse(null)

// STRONG: the sender is the retailer. WEAK: the retailer is only named in subject or text (for example a forwarded message).
internal fun retailerEvidence(order: OrderRecord, email: GmailPreview, aliases: AliasBook = AliasBook.default): RetailerEvidence {
 val names = buildList {
  if(lowerAlnum(order.marketplace) !in genericMarketplaces) addAll(aliases.namesFor(order.marketplace))
  else if(lowerAlnum(order.name).length >= 3) add(order.name)
 }.filter { lowerAlnum(it).length >= 3 }
 if(names.isEmpty()) return RetailerEvidence.NONE
 val display = lowerAlnum(email.sender.substringBefore('<'))
 val domain = senderDomain(email.sender)
 val brand = domainBrand(domain)
 val flatDomain = lowerAlnum(domain)
 if(names.any { name ->
   val n = lowerAlnum(name)
   (display.isNotEmpty() && display.contains(n)) || brand == n || (brand.length >= 4 && n.contains(brand)) || flatDomain.contains(n)
  }) return RetailerEvidence.STRONG
 val text = "${email.subject}\n${email.snippet}\n${email.body}"
 return if(names.any { brandRegex(it)?.containsMatchIn(text) == true }) RetailerEvidence.WEAK else RetailerEvidence.NONE
}

// Archived orders stay candidates: a later shipment of the same purchase must still find its order.
internal fun isOpenOrder(order: OrderRecord) = order.status != "Delivered" && order.status != "Cancelled"

internal sealed class EmailAssignment {
 data class Matched(val orderId: Long, val viaOrderNumber: Boolean, val viaTracking: Boolean, val viaThread: Boolean = false, val viaContext: Boolean = false, val manual: Boolean = false, val viaPersisted: Boolean = false) : EmailAssignment()
 data class Ambiguous(val orderIds: Set<Long>, val reason: String) : EmailAssignment()
 data object Unrelated : EmailAssignment()
}

/**
 * Staged assignment of each email to at most one saved order:
 * 1. retailer + exact order number (aliases count as the same retailer); a tracking number saved on one order breaks ties;
 * 2. Gmail thread of an already associated email;
 * 3. a retailer shipping notice without an order number, when exactly one open order of that retailer exists;
 * 4. carrier mail through a tracking number that exactly one order claims;
 * 5. a carrier or forwarded shipping mail naming exactly one open order's retailer.
 * Anything that fits several orders, or conflicts, is Ambiguous and is never applied.
 */
internal fun assignEmails(evidence: List<EmailEvidence>, orders: List<OrderRecord>, shipments: List<ShipmentRecord>, numbers: List<ShipmentTrackingNumberRecord>,
 aliases: AliasBook = AliasBook.default, manual: Map<String, Long> = emptyMap(), persisted: Map<String, Set<Long>> = emptyMap()): Map<String, EmailAssignment> {
 val ordersById = orders.associateBy { it.id }
 val ordersByNumber = orders.groupBy { normalizeId(it.orderNumber) }.filterKeys { it.isNotEmpty() }
 val mentionable = ordersByNumber.keys.filter { it.length >= 8 && it.any(Char::isDigit) }
 val shipmentOrder = shipments.associate { it.id to it.orderId }
 // Standalone shipments (no order) never decide which order an email belongs to.
 val numberOwners: Map<String, Set<Long>> = numbers.filter { normalizeId(it.number).length >= 10 }
  .groupBy({ normalizeId(it.number) }, { shipmentOrder[it.shipmentId] })
  .mapValues { (_, owners) -> owners.filterNotNull().toSet() }
 fun trackOwnersOf(ev: EmailEvidence): Set<Long> = (ev.tokens.mapNotNull { numberOwners[it] } +
  ev.trackingCandidates.flatMap { c -> numberOwners.filterKeys { sameTrackingNumber(it, c) }.values }).flatten().toSet()
 val result = LinkedHashMap<String, EmailAssignment>()
 // Stage 1
 for(ev in evidence) {
  val forced = manual[ev.email.messageId]
  if(forced != null && forced in ordersById) { result[ev.key] = EmailAssignment.Matched(forced, viaOrderNumber = false, viaTracking = false, manual = true); continue }
  val priorOwners = persisted[ev.email.messageId].orEmpty().filter { it in ordersById }.toSet()
  if(priorOwners.size == 1) { result[ev.key] = EmailAssignment.Matched(priorOwners.single(), false, false, viaPersisted = true); continue }
  if(priorOwners.size > 1) { result[ev.key] = EmailAssignment.Ambiguous(priorOwners, "message already has saved associations to several orders"); continue }
  val trackOwners = trackOwnersOf(ev)
  // An unlabeled whole-token mention of a saved order number counts as an order number.
  val keys = ev.orderNumberKeys + mentionable.filter { it in ev.tokens }
  val hits = keys.flatMap { ordersByNumber[it].orEmpty() }.distinctBy { it.id }
  var orderOwner: Long? = null
  var orderTie: Set<Long>? = null
  var tieReason = "several orders share this order number"
  var multiOrder = false
  if(hits.isNotEmpty()) {
   if(keys.size > 1) { orderTie = hits.map { it.id }.toSet(); multiOrder = true; tieReason = "message names several order numbers" }
   else {
    val scored = hits.map { it to retailerEvidence(it, ev.email, aliases) }.filter { it.second != RetailerEvidence.NONE }
    if(scored.isNotEmpty()) {
     val best = scored.maxOf { it.second }
     val top = scored.filter { it.second == best }
     if(top.size == 1) orderOwner = top.single().first.id
     else {
      orderTie = top.map { it.first.id }.toSet()
      if(top.all { aliases.same(it.first.marketplace, top.first().first.marketplace) }) tieReason = "possible duplicate orders (same retailer and order number)"
     }
    }
   }
  }
  result[ev.key] = when {
   orderTie != null ->
    if(!multiOrder && trackOwners.size == 1 && trackOwners.single() in orderTie) EmailAssignment.Matched(trackOwners.single(), true, true)
    else EmailAssignment.Ambiguous(orderTie + trackOwners, tieReason)
   orderOwner != null -> when {
    trackOwners.isEmpty() -> EmailAssignment.Matched(orderOwner, true, false)
    trackOwners == setOf(orderOwner) -> EmailAssignment.Matched(orderOwner, true, true)
    else -> EmailAssignment.Ambiguous(trackOwners + orderOwner, "order number and tracking number point to different orders")
   }
    keys.isNotEmpty() && trackOwners.isNotEmpty() -> EmailAssignment.Ambiguous(
     hits.map { it.id }.toSet() + trackOwners,
     "explicit order number does not match the tracking number's saved order"
    )
   trackOwners.size == 1 -> EmailAssignment.Matched(trackOwners.single(), false, true)
   trackOwners.size > 1 -> EmailAssignment.Ambiguous(trackOwners, "message mentions tracking numbers of several orders")
   else -> EmailAssignment.Unrelated
  }
 }
 // A one-character order-number difference is never joined automatically (a shared thread is not proof); it becomes a review candidate.
 for(ev in evidence) {
  if(result[ev.key] != EmailAssignment.Unrelated || ev.orderNumbers.isEmpty()) continue
  val near = orders.filter { o -> ev.orderNumbers.any { possibleOrderNumberVariant(o.orderNumber, it) } && retailerEvidence(o, ev.email, aliases) != RetailerEvidence.NONE }
  if(near.isNotEmpty()) result[ev.key] = EmailAssignment.Ambiguous(near.map { it.id }.toSet(),
   "order number differs from a saved order only by a leading character; confirm manually")
 }
 // Tracking claims from order-number-verified messages can associate carrier correspondence.
 val claims = mutableMapOf<String, MutableSet<Long>>()
 for(ev in evidence) (result[ev.key] as? EmailAssignment.Matched)?.takeIf { !ev.carrierSender && !it.manual }?.let { m ->
  ev.trackingCandidates.forEach { claims.getOrPut(normalizeId(it)) { mutableSetOf() }.add(m.orderId) }
 }
 val contested = claims.filterValues { it.size > 1 }.keys
 for(ev in evidence) {
  val current = result[ev.key]
  val keys = ev.trackingCandidates.map(::normalizeId)
  if(current is EmailAssignment.Matched && !current.manual && !ev.carrierSender) {
   val clash = keys.filter { it in contested }
   if(clash.isNotEmpty()) result[ev.key] = EmailAssignment.Ambiguous(clash.flatMap { claims[it].orEmpty() }.toSet(), "tracking number is claimed by several orders")
  } else if(current is EmailAssignment.Unrelated && keys.isNotEmpty()) {
   val owners = keys.flatMap { claims[it].orEmpty() }.toSet()
   result[ev.key] = when {
    owners.isEmpty() -> current
    owners.size == 1 && keys.none { it in contested } -> EmailAssignment.Matched(owners.single(), false, true)
    else -> EmailAssignment.Ambiguous(owners, "tracking number is claimed by several orders")
   }
  }
 }
 // Retailer and thread evidence may suggest candidates for review, but never establish ownership.
 val open = orders.filter(::isOpenOrder)
 for(ev in evidence) {
  if(result[ev.key] != EmailAssignment.Unrelated || !isShippingKind(ev.kind) || ev.orderNumberKeys.isNotEmpty()) continue
  if(mentionable.any { it in ev.tokens }) continue
  val candidates = open.filter { retailerEvidence(it, ev.email, aliases) != RetailerEvidence.NONE }
  if(candidates.isNotEmpty()) result[ev.key] = EmailAssignment.Ambiguous(candidates.map { it.id }.toSet(),
   "order number or saved tracking number is missing; retailer/thread evidence alone cannot associate this email")
 }
 return result
}

internal fun manualAssociationOwners(markers: List<ProcessedGmailMessage>): Map<String, Long> = markers
 .filter { it.extractionVersion >= MANUAL_ASSOCIATION_VERSION }
 .groupBy { it.messageId }
 .mapNotNull { (messageId, rows) -> rows.map { it.orderId }.distinct().singleOrNull()?.let { messageId to it } }
 .toMap()

internal fun persistedAssociationOwners(markers: List<ProcessedGmailMessage>): Map<String, Set<Long>> = markers
 .groupBy { it.messageId }
 .mapValues { (_, rows) -> rows.map { it.orderId }.toSet() }

/** Exact normalized external order identity plus the strongest retailer evidence shared by updates and Inbox import. */
internal fun exactSavedOrderCandidates(orderNumber: String, emails: List<GmailPreview>, orders: List<OrderRecord>, aliases: AliasBook = AliasBook.default): List<OrderRecord> {
 val key = normalizeId(orderNumber)
 if(key.isBlank()) return emptyList()
 val scored = orders.filter { normalizeId(it.orderNumber) == key }
  .map { order -> order to (emails.maxOfOrNull { retailerEvidence(order, it, aliases) } ?: RetailerEvidence.NONE) }
  .filter { it.second != RetailerEvidence.NONE }
 val best = scored.maxOfOrNull { it.second } ?: return emptyList()
 return scored.filter { it.second == best }.map { it.first }
}

internal data class DuplicateCandidate(val first: OrderRecord, val second: OrderRecord, val sameRetailer: Boolean)

/** Why a saved order is, or is not, a plausible parent for an email: retailer, order number and tracking evidence. */
internal fun candidateEvidenceText(order: OrderRecord, email: GmailPreview, numbers: List<ShipmentTrackingNumberRecord>, shipments: List<ShipmentRecord>, aliases: AliasBook = AliasBook.default): String {
 val ev = buildEvidence(email)
 val retailer = when(retailerEvidence(order, email, aliases)) {
  RetailerEvidence.STRONG -> "sender matches the retailer"
  RetailerEvidence.WEAK -> "retailer only named in the text"
  RetailerEvidence.NONE -> "retailer not verified"
 }
 val key = normalizeId(order.orderNumber)
 val orderPart = when {
  ev.orderNumbers.isEmpty() -> "no order number in the email"
  key in ev.orderNumberKeys -> "exact order number"
  ev.orderNumbers.any { possibleOrderNumberVariant(order.orderNumber, it) } -> "order number differs only by a leading character"
  else -> "different order number"
 }
 val shipmentIds = shipments.filter { it.orderId == order.id }.map { it.id }.toSet()
 val trackingMatch = ev.trackingCandidates.any { c -> numbers.any { it.shipmentId in shipmentIds && normalizeId(it.number) == normalizeId(c) } }
 return listOf(retailer, orderPart).plus(if(trackingMatch) listOf("tracking matches a saved shipment") else emptyList()).joinToString(" \u00b7 ")
}

/** Orders that may be the same purchase. Nothing is merged here; the user confirms each pair. */
internal fun findDuplicateOrders(orders: List<OrderRecord>, aliases: AliasBook = AliasBook.default): List<DuplicateCandidate> {
 val result = mutableListOf<DuplicateCandidate>()
 orders.filter { normalizeId(it.orderNumber).isNotEmpty() }.groupBy { normalizeId(it.orderNumber) }.forEach { (key, group) ->
  for(i in group.indices) for(j in i + 1 until group.size) {
   val a = group[i]; val b = group[j]
   val same = aliases.same(a.marketplace, b.marketplace)
   // Equal numbers at differently named retailers are only a candidate when the number is distinctive.
   if(same || (key.length >= 8 && key.any(Char::isDigit))) result += DuplicateCandidate(a, b, same)
  }
 }
 return result
}
