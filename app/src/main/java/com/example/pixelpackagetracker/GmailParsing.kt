package com.example.pixelpackagetracker

import org.json.JSONObject
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale

// Pure Gmail message parsing and email classification (no database, no Android UI).

/** A regex match detached from its Matcher. */
internal class RegexHit(val value: String, val range: IntRange, val groupValues: List<String>)

// Kotlin's findAll and replace-with-lambda open a new Matcher for every match, and on Android each Matcher copies the
// whole input into native memory that only a GC frees; on a large email body that reached gigabytes. These reuse one Matcher.
internal fun Regex.scan(input: CharSequence): List<RegexHit> {
 val m = toPattern().matcher(input)
 val hits = ArrayList<RegexHit>()
 while(m.find()) hits += RegexHit(m.group(), m.start() until m.end(), List(m.groupCount() + 1) { m.group(it) ?: "" })
 return hits
}

internal fun Regex.replaceEach(input: CharSequence, transform: (RegexHit) -> CharSequence): String {
 val m = toPattern().matcher(input)
 if(!m.find()) return input.toString()
 val out = StringBuilder(input.length)
 var last = 0
 do {
  out.append(input, last, m.start()).append(transform(RegexHit(m.group(), m.start() until m.end(), List(m.groupCount() + 1) { m.group(it) ?: "" })))
  last = m.end()
 } while(m.find())
 return out.append(input, last, input.length).toString()
}

private val namedEntities = mapOf(
 "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "rsquo" to "'", "lsquo" to "'",
 "ldquo" to "\"", "rdquo" to "\"", "ndash" to "-", "mdash" to "-", "hellip" to "...", "copy" to "\u00a9", "reg" to "\u00ae",
 "trade" to "\u2122", "zwnj" to "", "zwj" to "", "shy" to "", "bull" to "\u2022", "middot" to "\u00b7")

internal fun decodeHtmlEntities(text: String): String {
 if(!text.contains('&')) return text
 return Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);").replaceEach(text) { m ->
  val g = m.groupValues[1]
  val code = when {
   g.startsWith("#x") || g.startsWith("#X") -> g.substring(2).toIntOrNull(16)
   g.startsWith("#") -> g.substring(1).toIntOrNull()
   else -> null
  }
  when {
   code != null -> if(code > 0 && Character.isValidCodePoint(code)) String(Character.toChars(code)) else ""
   else -> namedEntities[g] ?: namedEntities[g.lowercase(Locale.US)] ?: m.value
  }
 }
}

// Non-breaking and zero-width characters defeat \s in regexes, so they are normalized before any extraction.
internal fun normalizeSpaces(text: String): String = text
 .replace(Regex("[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]"), " ")
 .replace(Regex("[\\u200B-\\u200D\\u2060\\uFEFF\\u00AD]"), "")
 .replace("\r\n", "\n").replace('\r', '\n')
 .replace(Regex("[ \\t]+"), " ")
 .replace(Regex(" ?\\n ?"), "\n")
 .replace(Regex("\\n{3,}"), "\n\n")

/** Converts HTML to text but keeps link targets as "[link: url]" so tracking and order links survive tag stripping. */
internal fun htmlToText(html: String): String {
 if(html.isBlank()) return ""
 var s = html
  .replace(Regex("(?is)<!--.*?-->"), " ")
  .replace(Regex("(?is)<(script|style|head)\\b[^>]*>.*?</\\1>"), " ")
  // Retain quoted content for existing matching, but mark its boundary for ETA extraction.
  .replace(Regex("(?is)<blockquote\\b[^>]*>|<div\\b[^>]*class\\s*=\\s*[\"'][^\"']*\\bgmail_quote\\b[^\"']*[\"'][^>]*>"), "\n--- Original Message ---\n")
 s = Regex("(?is)<a\\b[^>]*?href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>(.*?)</a>").replaceEach(s) { m ->
  val url = decodeHtmlEntities(m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).trim()
  val label = m.groupValues[4].replace(Regex("(?s)<[^>]+>"), " ")
  if(url.startsWith("http", ignoreCase = true)) " $label [link: $url] " else " $label "
 }
 s = s.replace(Regex("(?i)</?(?:p|div|tr|li|ul|ol|table|br|h[1-6]|section|article|header|footer)\\b[^>]*>"), "\n")
  .replace(Regex("(?i)</t[dh]>"), " ")
  .replace(Regex("(?s)<[^>]+>"), " ")
 return normalizeSpaces(decodeHtmlEntities(s)).trim()
}

internal fun decodeBase64Url(data: String, charset: Charset = Charsets.UTF_8): String = try {
 String(Base64.getUrlDecoder().decode(data.trim()), charset)
} catch(_: IllegalArgumentException) { "" }

internal fun gmailInboxMessagesListPath(pageToken: String?): String =
 "messages?maxResults=100&labelIds=INBOX" + (pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")

internal fun messageLabelsAreInInbox(labels: List<String>?): Boolean = labels == null || "INBOX" in labels

internal fun downloadedMessageIsInInbox(message: JSONObject): Boolean =
 messageLabelsAreInInbox(message.optJSONArray("labelIds")?.let { labels -> (0 until labels.length()).map { labels.optString(it) } })

// RFC 2047 encoded words (=?UTF-8?B?...?= / =?UTF-8?Q?...?=) in Subject and From headers.
internal fun decodeMimeWords(value: String): String {
 if(!value.contains("=?")) return value
 // Whitespace between adjacent encoded words is not part of the text.
 return Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").replace(value.replace(Regex("\\?=\\s+=\\?"), "?==?")) { m ->
  val charset = runCatching { Charset.forName(m.groupValues[1]) }.getOrDefault(Charsets.UTF_8)
  runCatching {
   if(m.groupValues[2].equals("b", ignoreCase = true)) String(Base64.getMimeDecoder().decode(m.groupValues[3]), charset)
   else {
    val q = m.groupValues[3]
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while(i < q.length) {
     when {
      q[i] == '_' -> { out.write(' '.code); i++ }
      q[i] == '=' && i + 3 <= q.length -> { out.write(q.substring(i + 1, i + 3).toInt(16)); i += 3 }
      else -> { out.write(q[i].code); i++ }
     }
    }
    String(out.toByteArray(), charset)
   }
  }.getOrDefault(m.value)
 }
}

/**
 * Turns a Gmail API `format=full` message into a GmailPreview. Plain and HTML alternatives are both kept
 * (either may hold the identifier); large parts delivered as attachments are fetched through [fetchAttachment].
 */
internal fun parseGmailMessage(message: JSONObject, fetchAttachment: (String) -> String? = { null }): GmailPreview {
 val payload = message.optJSONObject("payload")
 val headers = payload?.optJSONArray("headers")
 fun header(name: String): String {
  if(headers != null) for(i in 0 until headers.length()) {
   val h = headers.optJSONObject(i) ?: continue
   if(h.optString("name").equals(name, ignoreCase = true)) return h.optString("value")
  }
  return ""
 }
 val plain = StringBuilder()
 val html = StringBuilder()
 fun walk(part: JSONObject?) {
  if(part == null) return
  val mime = part.optString("mimeType").lowercase(Locale.US)
  if(mime == "text/plain" || mime == "text/html") {
   val body = part.optJSONObject("body")
   var data = body?.optString("data").orEmpty()
   if(data.isEmpty()) body?.optString("attachmentId").orEmpty().takeIf { it.isNotEmpty() }?.let { data = fetchAttachment(it).orEmpty() }
   var charset: Charset = Charsets.UTF_8
   val partHeaders = part.optJSONArray("headers")
   if(partHeaders != null) for(i in 0 until partHeaders.length()) {
    val h = partHeaders.optJSONObject(i) ?: continue
    if(h.optString("name").equals("Content-Type", ignoreCase = true))
     Regex("(?i)charset\\s*=\\s*\"?([A-Za-z0-9_.:-]+)").find(h.optString("value"))?.groupValues?.get(1)?.let { cs -> charset = runCatching { Charset.forName(cs) }.getOrDefault(Charsets.UTF_8) }
   }
   val text = decodeBase64Url(data, charset)
   if(text.isNotBlank()) (if(mime == "text/html") html else plain).append(text).append('\n')
  }
  val children = part.optJSONArray("parts")
  if(children != null) for(i in 0 until children.length()) walk(children.optJSONObject(i))
 }
 walk(payload)
 val body = (normalizeSpaces(plain.toString()).take(30000) + "\n" + htmlToText(html.toString()).take(40000)).trim()
 val snippet = normalizeSpaces(decodeHtmlEntities(message.optString("snippet")))
 return GmailPreview(decodeMimeWords(header("From")), decodeMimeWords(header("Subject")), header("Date"), snippet, message.optString("threadId"), body, message.optString("id"),
  message.optString("internalDate").toLongOrNull())
}

// --- authorized carrier tracking links ---

private val carrierDomains = listOf("fedex.com" to "FedEx", "ups.com" to "UPS", "usps.com" to "USPS", "dhl.com" to "DHL",
 "dhlglobalmail.com" to "DHL eCommerce", "cainiao.com" to "Cainiao", "ontrac.com" to "OnTrac")
private val trackingParam = Regex("(?i)(?:trknbr|trackingnumbers?|tracknumbers?|tracknum|tracking[-_]?id|tracking[-_]?number|tlabels|inquirynumber1|mailnolist)=([A-Za-z0-9]{10,30})")

internal data class TrackingLink(val number: String, val carrier: String)

internal fun isPlausibleTrackingToken(token: String): Boolean {
 val normalized = token.filter(Char::isLetterOrDigit)
 val digits = normalized.count(Char::isDigit)
 return normalized.length in 10..30 && if(normalized.all(Char::isDigit)) digits >= 10 else digits >= 6
}

// Only a recognised carrier host counts, so a retailer's own "Track package" link never yields a tracking number.
internal fun extractTrackingLinks(content: String): List<TrackingLink> {
 val found = mutableListOf<TrackingLink>()
 Regex("(?i)https?://[^\\s\\]>\"')]+").scan(content).forEach { m ->
  fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
  val once = decode(m.value)
  val variants = listOf(m.value, once, decode(once))
  val carrier = carrierDomains.firstOrNull { (domain, _) ->
   val host = Regex("(?<![a-z0-9-])" + Regex.escape(domain))
   variants.any { host.containsMatchIn(it.lowercase(Locale.US)) }
  }?.second ?: return@forEach
  variants.forEach { v -> trackingParam.scan(v).forEach { t -> t.groupValues[1].takeIf(::isPlausibleTrackingToken)?.let { n -> found += TrackingLink(n, carrier) } } }
 }
 return found.distinctBy { normalizeId(it.number) }
}

// --- carrier detection ---

private val carrierMentions = listOf(
 "FedEx" to Regex("(?i)\\bfed\\s?ex\\b"),
 "USPS" to Regex("(?i)\\b(?:usps|united states postal service|us postal service)\\b"),
 // Case-sensitive: "ups" inside ordinary words must not count.
 "UPS" to Regex("\\bUPS\\b|(?i:\\bunited parcel service\\b)"),
 "DHL" to Regex("(?i)\\bdhl\\b"),
 "Cainiao" to Regex("(?i)\\bcainiao\\b"),
 "OnTrac" to Regex("(?i)\\bontrac\\b"))

private fun mentionedCarrier(text: String): String? = carrierMentions.mapNotNull { (name, re) -> re.find(text)?.let { name to it.range.first } }.minByOrNull { it.second }?.first

internal fun carrierForSender(sender: String): String? {
 val domain = senderDomain(sender)
 return carrierDomains.firstOrNull { (d, _) -> domain == d || domain.endsWith(".$d") }?.second
}

internal fun isCarrierSender(sender: String): Boolean = carrierForSender(sender) != null || senderDomain(sender).contains("trackmyshipment")

internal fun carrierFromTrackingFormat(token: String): String? {
 val n = token.uppercase(Locale.US)
 return when {
  n.matches(Regex("1Z[A-Z0-9]{16}")) -> "UPS"
  n.matches(Regex("9[2345][0-9]{18,22}")) || n.matches(Regex("[A-Z]{2}[0-9]{9}US")) -> "USPS"
  else -> null
 }
}

/** Carrier evidence, strongest first: tracking link host, naming beside the number, "shipped with", number format, sender. `strict` drops the final any-mention fallback. */
internal fun detectCarrier(email: GmailPreview, tracking: String? = null, strict: Boolean = false): String? {
 val text = "${email.subject}\n${email.snippet}\n${email.body}"
 if(tracking != null) {
  extractTrackingLinks(text).firstOrNull { normalizeId(it.number) == normalizeId(tracking) }?.let { return it.carrier }
  val at = text.indexOf(tracking)
  if(at >= 0) mentionedCarrier(text.substring(maxOf(0, at - 160), minOf(text.length, at + tracking.length + 60)))?.let { return it }
 }
 Regex("(?i)\\b(?:ship(?:ped|ping)?\\s+(?:with|via|by|through)|carrier|delivery service|shipping method|sent via)\\b[^\\n]{0,40}").scan(text).forEach { m -> mentionedCarrier(m.value)?.let { return it } }
 tracking?.let { carrierFromTrackingFormat(it) }?.let { return it }
 carrierForSender(email.sender)?.let { return it }
 return if(strict) null else mentionedCarrier(text)
}

/** Carrier for a shipment with no tracking number: an explicit "shipped with"-style statement, else the only carrier the email names. Never guessed. */
internal fun carrierForTracklessShipment(email: GmailPreview): String? {
 detectCarrier(email, null, strict = true)?.let { return it }
 val text = "${email.subject}\n${email.snippet}\n${email.body}"
 return carrierMentions.filter { (_, re) -> re.containsMatchIn(text) }.map { it.first }.singleOrNull()
}

private val itemSubjectPrefix = Regex("(?i)^\\s*(?:re:\\s*)?(?:out for delivery|order update|order status|ordered(?:\\s+\\d+\\s+items?)?|shipped|shipping (?:update|notification|confirmation)|delivered|delivery (?:update|notification)|arriving[^:]{0,30}|on its way|in transit|dispatched)\\s*:\\s*(.+)$")
private val notAnItem = Regex("(?i)^(?:your|the|a|an)\\b|^\\d+\\s+items?$|\\b(?:order|package|shipment|parcel|delivery)\\b")

/** The product named after a status prefix in a subject ("OUT FOR DELIVERY: Google Pixel C 64GB..."); null when the subject names none. */
internal fun subjectItemName(subject: String): String? {
 val raw = itemSubjectPrefix.find(subject.trim())?.groupValues?.get(1) ?: return null
 val name = raw.replace(Regex("[.\u2026\\s]+$"), "").trim()
 return name.takeIf { it.length >= 3 && it.count(Char::isLetter) >= 3 && !notAnItem.containsMatchIn(it) }
}

/** An order title taken only from the emails: an explicit Item/Product line first, then the product named in a subject; null when they name none. */
internal fun orderTitleFromEmails(emails: List<GmailPreview>): String? =
 emails.firstNotNullOfOrNull { extractOrderDetails(it).items.firstOrNull()?.name } ?: emails.firstNotNullOfOrNull { subjectItemName(it.subject) }

// --- email classification ---

private val separatePackagesWording = Regex("(?i)\\b(?:(?:package|shipment|parcel)\\s*\\d+\\s*(?:of|/)\\s*\\d+|(?:ship(?:ped)?|sent|arrive[sd]?)\\s+(?:in|as)\\s+(?:\\d+|two|three|four|multiple|separate)\\s+(?:separate\\s+)?(?:packages|shipments|parcels|boxes)|ship(?:ped)? separately|separate (?:packages|shipments|parcels))\\b")
private val carrierHandoffWording = Regex("(?i)\\b(?:hand(?:ed)?[- ]?(?:off|over)|last[- ]mile|final[- ]delivery|transferr?ed to|forwarded to|delivered to (?:usps|ups|fedex|dhl|ontrac)|local carrier)\\b")

/** Explicit wording that the email describes several separate parcels. */
internal fun hasSeparatePackageEvidence(email: GmailPreview): Boolean = separatePackagesWording.containsMatchIn("${email.subject}\n${email.snippet}\n${email.body}")

/** Explicit wording that a second number is the same parcel continuing with another carrier. */
internal fun hasCarrierHandoffEvidence(email: GmailPreview): Boolean = carrierHandoffWording.containsMatchIn("${email.subject}\n${email.snippet}\n${email.body}")

internal enum class EmailKind { CONFIRMATION, SHIPPED, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, OTHER }

private val conditionalWords = Regex("(?i)\\b(?:when|once|after|until|will|we'll|we will|soon|notify|let you know|if|unless|as soon as|expected to ship|ships? (?:in|within)|not yet|has not|hasn't|haven't|can be|may be|should be)\\b")
private val confirmationSubject = Regex("(?i)\\b(?:order\\b[^\\n]{0,40}?\\b(?:confirmation|confirmed|placed|received)|thanks? for (?:your|the) order|purchase confirmation|receipt for your order|we(?:'ve| have) received your order)\\b")
internal fun isConfirmationSubject(subject: String) = confirmationSubject.containsMatchIn(subject)
private val orderedSubject = Regex("(?i)^\\s*(?:re:\\s*)?ordered\\b")
private val confirmationSenderLocalPart = Regex("(?i)^(?:auto[-_.]?confirm|order[-_.]?confirm\\w*|confirmation)$")

private fun senderLocalPart(sender: String): String =
 (Regex("<([^<>]*)>\\s*$").find(sender.trim())?.groupValues?.get(1) ?: sender).substringBefore('@').trim()

/** Headers that mark an order confirmation even when the subject has no "confirmed" wording ("Ordered 1 item: ...", auto-confirm@). */
internal fun hasConfirmationHeader(email: GmailPreview): Boolean =
 orderedSubject.containsMatchIn(email.subject) || confirmationSenderLocalPart.matches(senderLocalPart(email.sender))
private val shippedSubject = Regex("(?i)\\b(?:shipped|dispatched|on (?:its|the) way|in transit|shipment (?:confirmation|notification)|shipping (?:confirmation|notification)|picked up)\\b")
private val shippedBody = Regex("(?i)\\b(?:(?:has|have|had|was|were|been|just|now)\\s+(?:shipped|dispatched)|shipped\\s+(?:with|via|by|on|from)|on (?:its|the) way|in transit|picked up|accepted by (?:the )?(?:carrier|usps|ups|fedex|dhl|ontrac)|carrier (?:has )?(?:received|accepted)|(?:acceptance|origin) scan|your (?:order|item|package|shipment|parcel)s?\\b[^.\\n]{0,60}\\b(?:shipped|dispatched))\\b")
private val outForDelivery = Regex("(?i)\\bout for delivery\\b")
private val exceptionPhrase = Regex("(?i)\\b(?:delivery (?:exception|attempt(?:ed)? failed|failed)|has been delayed|is delayed|delivery delay|shipment exception)\\b")
private val trackingPhrase = Regex("(?i)\\b(?:tracking number|track (?:your|the) (?:package|shipment|order|parcel)|track package)\\b")

private fun bodyLines(email: GmailPreview): List<String> =
 "${email.snippet}\n${email.body}".take(20000).split(Regex("[\\n]|(?<=[.!?])\\s+"))

internal fun classifyEmail(email: GmailPreview, hasTracking: Boolean = false): EmailKind {
 if(confirmedDelivery(email)) return EmailKind.DELIVERED
 val subject = email.subject
 val lines = bodyLines(email)
 fun positive(re: Regex) = (re.containsMatchIn(subject) && !conditionalWords.containsMatchIn(subject)) ||
  lines.any { re.containsMatchIn(it) && !conditionalWords.containsMatchIn(it) }
 if(positive(outForDelivery)) return EmailKind.OUT_FOR_DELIVERY
 if(exceptionPhrase.containsMatchIn(subject) || lines.any { exceptionPhrase.containsMatchIn(it) && !conditionalWords.containsMatchIn(it) }) return EmailKind.EXCEPTION
 val isConfirmation = isConfirmationSubject(subject) || hasConfirmationHeader(email)
 if(shippedSubject.containsMatchIn(subject) && !conditionalWords.containsMatchIn(subject)) return EmailKind.SHIPPED
 if(!isConfirmation) {
  if(lines.any { shippedBody.containsMatchIn(it) && !conditionalWords.containsMatchIn(it) }) return EmailKind.SHIPPED
  // A merchant or carrier message that hands over a tracking number reports a shipment, not an order.
  if(hasTracking && trackingPhrase.containsMatchIn("$subject\n${email.snippet}\n${email.body}")) return EmailKind.SHIPPED
 }
 return if(isConfirmation) EmailKind.CONFIRMATION else EmailKind.OTHER
}

internal fun statusForKind(kind: EmailKind): String? = when(kind) {
 EmailKind.DELIVERED -> "Delivered"
 EmailKind.OUT_FOR_DELIVERY -> "Out for Delivery"
 EmailKind.EXCEPTION -> "Exception"
 EmailKind.SHIPPED -> "In Transit"
 else -> null
}

internal fun isShippingKind(kind: EmailKind) = kind == EmailKind.SHIPPED || kind == EmailKind.OUT_FOR_DELIVERY || kind == EmailKind.DELIVERED || kind == EmailKind.EXCEPTION

/** Explicit, non-conditional wording that the parcel was dispatched or picked up; a tracking number alone is not enough. */
internal fun hasDispatchWording(email: GmailPreview): Boolean =
 (shippedSubject.containsMatchIn(email.subject) && !conditionalWords.containsMatchIn(email.subject)) ||
  bodyLines(email).any { shippedBody.containsMatchIn(it) && !conditionalWords.containsMatchIn(it) }

/** A tracking-number-only notice is a created label (Label Created); In Transit needs dispatch wording. */
internal fun statusForEmail(email: GmailPreview, kind: EmailKind): String? =
 if(kind == EmailKind.SHIPPED && !hasDispatchWording(email)) "Label Created" else statusForKind(kind)

/** True only when the email itself is evidence that the parcel left the sender. */
internal fun confirmsDispatch(email: GmailPreview, kind: EmailKind): Boolean = kind == EmailKind.SHIPPED && hasDispatchWording(email)

private val cancelledWording = Regex("(?i)\\border\\b[^.\\n]{0,60}?\\b(?:has been|was|is now|is) cancell?ed\\b|\\border cancell?ation\\b|\\bcancell?ed order\\b|\\bwe(?:'ve| have) cancell?ed your order\\b")
private val processingWording = Regex("(?i)\\b(?:order (?:is|has been|was) (?:now )?(?:being )?(?:processed|processing|prepared|packed)|we(?:'re| are) (?:now )?(?:processing|preparing|packing) your order|(?:now )?processing your order|preparing your order|order (?:update|status)\\s*[:\\-]\\s*(?:processing|being processed))\\b")

/** Order-level status stated by a retailer notice (Processing or Cancelled); shipment wording is never an order status. */
internal fun orderStatusNotice(email: GmailPreview, kind: EmailKind): String? {
 if(isShippingKind(kind)) return null
 val lines = bodyLines(email)
 fun positive(re: Regex) = (re.containsMatchIn(email.subject) && !conditionalWords.containsMatchIn(email.subject)) ||
  lines.any { re.containsMatchIn(it) && !conditionalWords.containsMatchIn(it) }
 return when {
  positive(cancelledWording) -> "Cancelled"
  kind != EmailKind.CONFIRMATION && positive(processingWording) -> "Processing"
  else -> null
 }
}

// --- order enrichment from the email itself ---

private val itemLine = Regex("(?i)^\\s*(?:item|product)(?:\\s+name)?\\s*[:\\-]\\s*(.{3,100})$")

/** Only explicit "Item:"/"Product:" lines; quantity and price are taken only from the next few lines. */
internal fun extractItems(content: String): List<ParsedItem> {
 val lines = content.lines()
 val items = mutableListOf<ParsedItem>()
 for((i, line) in lines.withIndex()) {
  val name = itemLine.find(line)?.groupValues?.get(1)?.trim() ?: continue
  if(!name.any(Char::isLetter) || Regex("(?i)^(?:id|sku|number|#)").containsMatchIn(name)) continue
  val near = lines.subList(i + 1, minOf(lines.size, i + 4)).joinToString("\n")
  val qty = Regex("(?i)\\b(?:qty|quantity)\\s*[:x]?\\s*(\\d{1,3})\\b").find(near)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
  val price = Regex("(?i)\\bprice\\s*[:\\-]?\\s*\\$\\s*([0-9][0-9,]*(?:\\.[0-9]{2})?)").find(near)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.let { (it * 100).toLong() }
  items += ParsedItem(name, qty, price)
 }
 return items.distinctBy { it.name.lowercase(Locale.US) }.take(10)
}

private val orderLinkLabel = Regex("(?i)(?:view|manage|see|check|track)\\s+(?:your\\s+)?(?:order|purchase)|order\\s+(?:details|status)")

/** Prefers a labeled order link on the sender's own domain; never follows or fetches it. */
internal fun pickOrderUrl(email: GmailPreview, content: String): String {
 val brand = domainBrand(senderDomain(email.sender))
 fun clean(u: String) = u.trimEnd('.', ',', ')', ';', ']')
 fun host(u: String) = Regex("(?i)^https://([^/?#]+)").find(u)?.groupValues?.get(1)?.lowercase(Locale.US).orEmpty()
 Regex("([^\\n\\[\\]]{0,60})\\[link: (https://[^\\s\\]]+)\\]").scan(content)
  .map { it.groupValues[1] to clean(it.groupValues[2]) }
  .firstOrNull { (label, url) -> brand.isNotEmpty() && domainBrand(host(url)) == brand && (url.contains("/order", true) || orderLinkLabel.containsMatchIn(label)) }
  ?.let { return it.second }
 return Regex("(?i)https?://[^\\s<>\"\\]]+").scan(content).map { clean(it.value) }
  .firstOrNull { it.contains("/order", true) && it.startsWith("https://", true) }.orEmpty()
}
