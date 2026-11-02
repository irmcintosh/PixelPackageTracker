package com.example.pixelpackagetracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Needs Review association form: prefill from the email, saved-order ranking and editable form state. Nothing here writes to Room.

private val paymentProcessorBrands = mapOf("paypal" to "PayPal", "stripe" to "Stripe", "square" to "Square", "squareup" to "Square",
 "venmo" to "Venmo", "klarna" to "Klarna", "affirm" to "Affirm", "afterpay" to "Afterpay", "braintreepayments" to "Braintree")

internal fun paymentProcessorName(sender: String): String? = paymentProcessorBrands[domainBrand(senderDomain(sender))]

// "Amazon.com" and "Amazon" are one retailer; only an explicit domain suffix is dropped, so "Costco" is untouched.
private fun looseRetailerKey(name: String) = lowerAlnum(name.trim().lowercase().replace(Regex("\\.(?:com|net|org|co\\.uk|io)$"), ""))

internal fun sameRetailerName(a: String, b: String, aliases: AliasBook = AliasBook.default): Boolean =
 aliases.same(a, b) || looseRetailerKey(a).let { it.isNotEmpty() && it == looseRetailerKey(b) }

private val merchantLabels = listOf(
 Regex("(?i)\\b(?:receipt for your payment to|payment to|you paid(?:\\s+[^\\n]{0,30}?)?\\s+to|paid to|you sent(?:\\s+[^\\n]{0,30}?)?\\s+to)\\s+([^\\n]{2,60})"),
 Regex("(?im)^\\s*(?:merchant(?: name)?|seller|sold by|store)\\s*[:#-]\\s*([^\\n]{2,60})"))
private val merchantTail = Regex("(?i)\\s+(?:for|on|via|using|with|has|was|is)\\s.*$|[\\[(,;].*$|\\s[-\\u2014\\u2013]\\s.*$")
private val notMerchants = setOf("you", "your", "yourself", "them", "the", "a", "us", "this", "seller", "merchant")

/** The merchant a payment-processor email names explicitly; null when the email does not say. */
internal fun merchantFromProcessorEmail(email: GmailPreview): String? {
 val content = "${email.subject}\n${email.snippet}\n${email.body.take(6000)}"
 for(label in merchantLabels) for(match in label.scan(content)) {
  val name = match.groupValues[1].replace(merchantTail, "").trim().trimEnd('.', ':', ' ')
  if(name.length in 2..40 && name.any(Char::isLetter) && '@' !in name && lowerAlnum(name) !in notMerchants && paymentProcessorBrands[lowerAlnum(name)] == null) return name
 }
 return null
}

internal data class AssociationPrefill(
 val retailer: String,
 val title: String,
 val orderNumber: String,
 val retailerHint: String,
 val orderNumbersInEmail: List<String>,
 val processor: String?,
 /** The saved retailer spelling that matches the prefilled retailer, if any. */
 val suggestedRetailer: String?
)

/** Prefill only what the email supports: nothing is guessed, so a field with no reliable source stays empty. */
internal fun prefillAssociation(email: GmailPreview, orders: List<OrderRecord>, aliases: AliasBook = AliasBook.default): AssociationPrefill {
 val processor = paymentProcessorName(email.sender)
 val shared = domainBrand(senderDomain(email.sender)) in sharedMailDomains
 val merchant = if(processor != null) merchantFromProcessorEmail(email) else null
 val derived = when {
  processor != null -> merchant.orEmpty()
  shared -> ""
  else -> retailerNameFromSender(email.sender)
 }
 val saved = derived.takeIf { it.isNotBlank() }?.let { name -> orders.filter { sameRetailerName(it.marketplace, name, aliases) }.maxByOrNull { it.id }?.marketplace }
 val retailer = saved ?: derived
 val hint = when {
  processor != null && merchant != null -> "Merchant taken from the email; $processor only processed the payment."
  processor != null -> "$processor sent this email, but it does not name the merchant. Enter or pick the retailer."
  shared -> "The sender is a personal mail address, so the retailer was not identified."
  retailer.isBlank() -> "The retailer could not be identified from the sender."
  saved != null -> "Taken from the sender; matches a saved retailer."
  else -> "Taken from the sender; check that it is the actual merchant."
 }
 val content = "${email.subject}\n${email.snippet}\n${email.body}"
 val numbers = extractOrderNumbers(content).distinctBy(::normalizeId)
 val items = extractItems(content)
 val title = when {
  items.isEmpty() -> subjectItemName(email.subject).orEmpty()
  items.size == 1 -> items.single().name
  else -> "${items.first().name} + ${items.size - 1} more"
 }
 return AssociationPrefill(retailer, title, numbers.singleOrNull().orEmpty(), hint, numbers, processor, saved)
}

internal enum class MatchTier(val label: String) { VERIFIED("Verified match"), LIKELY("Likely match"), SAME_RETAILER("Same retailer"), OTHER("") }
internal data class RankedOrder(val order: OrderRecord, val tier: MatchTier, val evidence: String)

/** Saved orders, archived ones included, ordered by how well they match the email: exact number + retailer first, newest first within a tier. */
internal fun rankSavedOrders(email: GmailPreview, orders: List<OrderRecord>, candidateIds: Collection<Long>, numbers: List<ShipmentTrackingNumberRecord>,
 shipments: List<ShipmentRecord>, prefillRetailer: String = "", aliases: AliasBook = AliasBook.default): List<RankedOrder> {
 val ev = buildEvidence(email)
 return orders.map { order ->
  val retailerEv = retailerEvidence(order, email, aliases)
  val retailerMatch = retailerEv != RetailerEvidence.NONE || (prefillRetailer.isNotBlank() && sameRetailerName(order.marketplace, prefillRetailer, aliases))
  val exact = normalizeId(order.orderNumber) in ev.orderNumberKeys
  val shipmentIds = shipments.filter { it.orderId == order.id }.map { it.id }.toSet()
  val trackingMatch = ev.trackingCandidates.any { c -> numbers.any { it.shipmentId in shipmentIds && sameTrackingNumber(it.number, c) } }
  val tier = when {
   exact && retailerMatch -> MatchTier.VERIFIED
   exact || trackingMatch || order.id in candidateIds -> MatchTier.LIKELY
   retailerMatch -> MatchTier.SAME_RETAILER
   else -> MatchTier.OTHER
  }
  RankedOrder(order, tier, candidateEvidenceText(order, email, numbers, shipments, aliases))
 }.sortedWith(compareBy<RankedOrder> { it.tier.ordinal }.thenByDescending { it.order.id })
}

/** The default selection: the top-ranked order, but only when it is a verified or likely match. Anything weaker defaults to a new order. */
internal fun strongestMatchId(ranked: List<RankedOrder>): Long? =
 ranked.firstOrNull()?.takeIf { it.tier == MatchTier.VERIFIED || it.tier == MatchTier.LIKELY }?.order?.id

/** True when the best two orders are equally strong, so the default is only a guess and the user should check it. */
internal fun topMatchIsTied(ranked: List<RankedOrder>): Boolean =
 ranked.size > 1 && ranked[0].tier == ranked[1].tier && (ranked[0].tier == MatchTier.VERIFIED || ranked[0].tier == MatchTier.LIKELY)

/** An order that already uses this retailer (alias-aware) and exact order number, so creating it would duplicate it. */
internal fun existingOrderFor(retailer: String, number: String, orders: List<OrderRecord>, aliases: AliasBook = AliasBook.default): OrderRecord? =
 if(retailer.isBlank() || number.isBlank()) null
 else orders.firstOrNull { sameRetailerName(it.marketplace, retailer, aliases) && normalizeId(it.orderNumber) == normalizeId(number) }

internal enum class RetailerOptionKind { SUGGESTED_SAVED, SAVED, SUGGESTED_NEW, ADD_NEW }
internal data class RetailerOption(val kind: RetailerOptionKind, val name: String) {
 val label: String get() = when(kind) {
  RetailerOptionKind.SUGGESTED_SAVED -> "Suggested: $name (saved)"
  RetailerOptionKind.SAVED -> name
  RetailerOptionKind.SUGGESTED_NEW -> "Suggested new retailer: $name"
  RetailerOptionKind.ADD_NEW -> "Add new retailer\u2026"
 }
}

/** Suggested saved retailer first, then every saved retailer once (newest spelling), then the extracted new one, then Add new retailer. */
internal fun retailerOptions(prefill: AssociationPrefill, orders: List<OrderRecord>, aliases: AliasBook = AliasBook.default): List<RetailerOption> {
 val savedNames = orders.filter { it.marketplace.isNotBlank() }.groupBy { aliases.canonical(looseRetailerKey(it.marketplace)) }
  .map { (_, group) -> group.maxByOrNull { it.id }!!.marketplace }.sortedBy { it.lowercase() }
 val suggested = prefill.suggestedRetailer
 return buildList {
  if(suggested != null) add(RetailerOption(RetailerOptionKind.SUGGESTED_SAVED, suggested))
  savedNames.filter { suggested == null || !sameRetailerName(it, suggested, aliases) }.forEach { add(RetailerOption(RetailerOptionKind.SAVED, it)) }
  if(suggested == null && prefill.retailer.isNotBlank()) add(RetailerOption(RetailerOptionKind.SUGGESTED_NEW, prefill.retailer))
  add(RetailerOption(RetailerOptionKind.ADD_NEW, ""))
 }
}

/** One entry of the Order number dropdown: a saved order of the chosen retailer, a number found in the email, or typing one's own. */
internal sealed class OrderChoice {
 data class Saved(val ranked: RankedOrder) : OrderChoice()
 data class FromEmail(val number: String) : OrderChoice()
 data object Manual : OrderChoice()
}

/** Only the chosen retailer's saved orders (strongest match first), then numbers in the email that are not saved yet, then manual entry. */
internal fun orderChoices(prefill: AssociationPrefill, retailer: String, ranked: List<RankedOrder>, aliases: AliasBook = AliasBook.default): List<OrderChoice> {
 val saved = ranked.filter { retailer.isNotBlank() && sameRetailerName(it.order.marketplace, retailer, aliases) }
 val emailOnly = prefill.orderNumbersInEmail.filter { n -> saved.none { normalizeId(it.order.orderNumber) == normalizeId(n) } }
 return saved.map { OrderChoice.Saved(it) } + emailOnly.map { OrderChoice.FromEmail(it) } + OrderChoice.Manual
}

/** Holds the editable fields. It is created once per email, so recomposition never re-applies the prefill over a manual edit. */
internal class AssociationFormState(val prefill: AssociationPrefill, initialOrderId: Long? = null, initialRetailer: String = prefill.retailer) {
 /** The saved order the email will be associated with, or null when the number below is a new order. */
 var selectedOrderId by mutableStateOf(initialOrderId)
 var retailer by mutableStateOf(initialRetailer)
 var title by mutableStateOf(prefill.title)
 var number by mutableStateOf(prefill.orderNumber)
 var addingRetailer by mutableStateOf(false)
 /** True once the user chose to type an order number; later retailer changes do not replace it. */
 var manualNumber by mutableStateOf(false)

 /** Default order for the current retailer: its strongest verified/likely saved order, otherwise the email's number as a new order. */
 fun reselect(ranked: List<RankedOrder>, aliases: AliasBook = AliasBook.default) {
  if(manualNumber) { selectedOrderId = null; return }
  val best = strongestMatchId(ranked.filter { sameRetailerName(it.order.marketplace, retailer, aliases) })
  selectedOrderId = best
  if(best == null) number = prefill.orderNumber
 }

 fun pickRetailer(option: RetailerOption, orders: List<OrderRecord>, aliases: AliasBook = AliasBook.default, ranked: List<RankedOrder> = emptyList()) {
  if(option.kind == RetailerOptionKind.ADD_NEW) {
   // A typed or extracted unsaved name is kept for editing; a saved retailer name is cleared so a new one can be entered.
   if(retailer.isNotBlank() && orders.any { sameRetailerName(it.marketplace, retailer, aliases) }) retailer = ""
   addingRetailer = true
  } else {
   retailer = option.name
   addingRetailer = false
  }
  reselect(ranked, aliases)
 }

 /** Typing a retailer name clears a saved-order selection that belonged to another retailer. */
 fun editRetailer(value: String) { retailer = value; selectedOrderId = null }

 /** Picking a saved title only fills the retailer when it is still empty. */
 fun pickTitle(value: String, orders: List<OrderRecord>) {
  title = value
  if(retailer.isBlank()) orders.filter { it.name.equals(value, ignoreCase = true) }.maxByOrNull { it.id }?.let { retailer = it.marketplace }
 }

 fun pickEmailNumber(value: String) { selectedOrderId = null; manualNumber = false; number = value }

 fun selectOrder(id: Long?) { selectedOrderId = id; manualNumber = false }

 fun enterNumberManually() { selectedOrderId = null; if(!manualNumber) number = ""; manualNumber = true }

 fun existing(orders: List<OrderRecord>, aliases: AliasBook = AliasBook.default) = existingOrderFor(retailer, number, orders, aliases)

 fun canCreate(orders: List<OrderRecord>, aliases: AliasBook = AliasBook.default) =
  selectedOrderId == null && retailer.isNotBlank() && number.isNotBlank() && existing(orders, aliases) == null
}

/** Starts on the strongest verified/likely saved order for the email's retailer (or any retailer when the email names none). */
internal fun initialAssociationForm(prefill: AssociationPrefill, ranked: List<RankedOrder>, aliases: AliasBook = AliasBook.default): AssociationFormState {
 val inRetailer = ranked.filter { prefill.retailer.isBlank() || sameRetailerName(it.order.marketplace, prefill.retailer, aliases) }
 val best = inRetailer.firstOrNull { it.tier == MatchTier.VERIFIED || it.tier == MatchTier.LIKELY }
 return AssociationFormState(prefill, best?.order?.id, best?.order?.marketplace ?: prefill.retailer)
}
