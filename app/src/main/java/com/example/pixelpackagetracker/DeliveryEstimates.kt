package com.example.pixelpackagetracker

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal data class DeliveryEstimate(val start: LocalDate, val end: LocalDate? = null)
internal data class DeliveryDateFinding(val estimate: DeliveryEstimate? = null, val problem: String? = null)
internal data class MailTime(val at: Long, val localDate: LocalDate?)

internal fun mailTime(email: GmailPreview): MailTime? {
 val header = email.date.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
 try {
  val parsed = OffsetDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(java.time.format.ResolverStyle.STRICT))
  return MailTime(parsed.toInstant().toEpochMilli(), parsed.toLocalDate())
 } catch(_: DateTimeParseException) { }
 val patterns = listOf("EEE, d MMM uuuu HH:mm:ss Z", "d MMM uuuu HH:mm:ss Z", "EEE, d MMM uuuu HH:mm Z")
 for(pattern in patterns) {
  try {
   val parsed = OffsetDateTime.parse(header, DateTimeFormatter.ofPattern(pattern, Locale.US).withResolverStyle(java.time.format.ResolverStyle.STRICT))
   return MailTime(parsed.toInstant().toEpochMilli(), parsed.toLocalDate())
  } catch(_: DateTimeParseException) { }
 }
 return email.internalDate?.takeIf { it > 0 }?.let { MailTime(it, null) }
}

private val marketingDelivery = Regex("(?i)\\b(?:newsletter|coupons?|promo(?:tion|tional)?|sales?|deals?|discount|rewards?|gift card|price drop|back in stock|recommended|you may also like|new arrivals|limited time offer|save \\$|shop now|free shipping on|on your next|order (?:now|today)|sale ends)\\b|\\d+\\s?% off")
private val deliveryLabel = Regex("(?i)\\b(?:(?:estimated|expected|scheduled)\\s+(?:delivery|arrival)(?:\\s+date)?|delivery\\s+(?:date|estimate|window)(?:\\s+has\\s+changed)?|(?:your\\s+)?delivery\\s+(?:has\\s+)?changed|arriv(?:ing|es)|(?:your\\s+)?(?:package|shipment|parcel|order)\\s+(?:will\\s+)?arrive)\\s*(?:(?:has been|is now|is|changed to|on|by|between|to)\\b\\s*|[:\\-]\\s*)*")
private val revisedDelivery = Regex("(?i)\\b(?:changed|revised|rescheduled|updated|now)\\b")
private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
private const val MONTH = "(?:January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)"
private const val WEEKDAY = "(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue|Wed|Thu|Fri|Sat|Sun)"
private val calendarExpression = Regex("(?i)^(?:$WEEKDAY,?\\s+)?($MONTH)\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?(?:\\s*(?:-|to|through)\\s*(?:$WEEKDAY,?\\s+)?(?:($MONTH)\\s+)?(\\d{1,2})(?:,?\\s+(\\d{4}))?)?(?![\\d/-])")
private val isoExpression = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:\\s+(?:to|through)\\s+(\\d{4}-\\d{2}-\\d{2}))?(?![\\d/-])")
private val weekdayExpression = Regex("(?i)^($WEEKDAY)\\b")
private val dateExpressionHint = Regex("(?i)\\d|\\b(?:$MONTH|$WEEKDAY|today|tomorrow)\\b")
private val dateExpressionStart = Regex("(?i)^(?:\\d{4}-|$MONTH\\b|$WEEKDAY\\b|today\\b|tomorrow\\b)")
private val quotedReply = Regex("(?i)^\\s*(?:on .+wrote:|-+\\s*original message|from:)")
private val actualDelivery = Regex("(?i)\\b(?:actual delivery|was delivered|has been delivered|successfully delivered)\\b")

private fun monthNumber(raw: String): Int = months.indexOfFirst { it.startsWith(raw.lowercase(Locale.US).take(3)) } + 1
private fun validDate(year: Int, month: Int, day: Int): LocalDate? =
 try { LocalDate.of(year, month, day) } catch(_: java.time.DateTimeException) { null }

private fun parseDeliveryExpression(raw: String, reference: LocalDate?): DeliveryEstimate? {
 val text = raw.trim().replace('\u2013', '-').replace('\u2014', '-')
 if(Regex("(?i)\\b(?:or|maybe|business days|within)\\b").containsMatchIn(text)) return null
 if(Regex("(?i)^tomorrow\\b").containsMatchIn(text)) return reference?.plusDays(1)?.let { DeliveryEstimate(it) }
 if(Regex("(?i)^today\\b").containsMatchIn(text)) return reference?.let { DeliveryEstimate(it) }
 isoExpression.find(text)?.let { m ->
  val start = try { LocalDate.parse(m.groupValues[1]) } catch(_: DateTimeParseException) { return null }
  val end = m.groupValues[2].takeIf { it.isNotBlank() }?.let {
   try { LocalDate.parse(it) } catch(_: DateTimeParseException) { return null }
  }
  return if(end != null && end < start) null else DeliveryEstimate(start, end)
 }
 calendarExpression.find(text)?.let { m ->
  if(Regex("(?i)^\\s*(?:,?\\s*\\d|[-/]|(?:to|through)\\b)").containsMatchIn(text.substring(m.range.last + 1))) return null
  val month = monthNumber(m.groupValues[1])
  val rangeEndYear = m.groupValues[6].toIntOrNull()
  val rangeEndMonth = m.groupValues[4].takeIf { it.isNotBlank() }?.let(::monthNumber) ?: month
  val explicitYear = m.groupValues[3].toIntOrNull() ?: rangeEndYear?.let { if(month == 12 && rangeEndMonth == 1) it - 1 else it }
  var year = explicitYear ?: reference?.year ?: return null
  if(explicitYear == null && reference != null && reference.monthValue == 12 && month == 1) year++
  // Do not infer a year for an estimate more than a month before its email.
  val start = validDate(year, month, m.groupValues[2].toInt()) ?: return null
  val statedWeekday = weekdayExpression.find(text)?.groupValues?.get(1)
  if(statedWeekday != null && !start.dayOfWeek.name.startsWith(statedWeekday.uppercase(Locale.US).take(3))) return null
  if(explicitYear == null && reference != null && start < reference.minusDays(31)) return null
  val endDay = m.groupValues[5].toIntOrNull()
  val end = if(endDay == null) null else {
   val endMonth = m.groupValues[4].takeIf { it.isNotBlank() }?.let(::monthNumber) ?: month
   val endYear = m.groupValues[6].toIntOrNull() ?: if(month == 12 && endMonth == 1) year + 1 else year
   validDate(endYear, endMonth, endDay) ?: return null
  }
  if(end != null && end < start) return null
  return DeliveryEstimate(start, end)
 }
 weekdayExpression.find(text)?.let { m ->
  val day = DayOfWeek.values().first { it.name.startsWith(m.groupValues[1].uppercase(Locale.US).take(3)) }
  return reference?.with(TemporalAdjusters.nextOrSame(day))?.let { DeliveryEstimate(it) }
 }
 return null
}

internal fun extractDeliveryEstimate(email: GmailPreview): DeliveryDateFinding {
 if(marketingDelivery.containsMatchIn("${email.subject}\n${email.snippet}")) return DeliveryDateFinding()
 val reference = mailTime(email)?.localDate
 val candidates = mutableListOf<Pair<DeliveryEstimate, Boolean>>()
 var invalid = false
 // Quoted replies are not fresh evidence. Plain/HTML duplicates are collapsed below.
 val lines = "${email.subject}\n${email.body}\n${email.snippet}".lines()
 for((index, line) in lines.withIndex()) {
  if(quotedReply.containsMatchIn(line)) break
  if(line.trimStart().startsWith(">") || marketingDelivery.containsMatchIn(line)) continue
  if(actualDelivery.containsMatchIn(line)) continue
  for(label in deliveryLabel.scan(line)) {
   var expression = line.substring(label.range.last + 1).trim()
   if(expression.isBlank()) {
    val following = lines.subList(index + 1, minOf(index + 3, lines.size)).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if(!dateExpressionStart.containsMatchIn(following)) continue
    expression = following
   }
   if(!dateExpressionHint.containsMatchIn(expression)) continue
   val parsed = parseDeliveryExpression(expression, reference)
   if(parsed == null) invalid = true
   else candidates += parsed to revisedDelivery.containsMatchIn(line)
  }
 }
 val revised = candidates.filter { it.second }
 val estimates = (if(revised.isNotEmpty()) revised else candidates).map { it.first }.distinct()
 return when {
  estimates.size == 1 && (!invalid || revised.isNotEmpty()) -> DeliveryDateFinding(estimates.single())
  estimates.isNotEmpty() || invalid -> DeliveryDateFinding(problem = "Delivery estimate is invalid, ambiguous, or lacks timestamp/timezone context")
  else -> DeliveryDateFinding()
 }
}

internal fun isEstimateOnlyEmail(ev: EmailEvidence): Boolean =
 extractDeliveryEstimate(ev.email).estimate != null && !ev.dispatch &&
  ev.kind !in listOf(EmailKind.OUT_FOR_DELIVERY, EmailKind.DELIVERED, EmailKind.EXCEPTION) &&
  !Regex("(?i)\\blabel (?:has been |was )?created\\b").containsMatchIn("${ev.email.subject}\n${ev.email.body}")

internal fun deliveryEpoch(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun deliveryDate(value: Long): LocalDate = Instant.ofEpochMilli(value).atOffset(ZoneOffset.UTC).toLocalDate()
internal fun formatDeliveryDate(value: Long): String = deliveryDate(value).format(DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US))

internal fun deliveryEditorDate(shipment: ShipmentRecord): String = shipment.estimatedDeliveryAt?.let {
 if(shipment.estimatedDeliverySourceMessageId.isBlank()) java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(it))
 else deliveryDate(it).toString()
}.orEmpty()

internal fun formatDeliveryEstimate(shipment: ShipmentRecord, formatDate: (Long) -> String = { value ->
 if(shipment.estimatedDeliverySourceMessageId.isBlank()) java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(value))
 else formatDeliveryDate(value)
}): String {
 val start = shipment.estimatedDeliveryAt ?: return "Not available"
 val end = shipment.estimatedDeliveryEndAt
 return if(end == null || end == start) formatDate(start) else "${formatDate(start)} - ${formatDate(end)}"
}

internal data class EstimateUpdate(val shipment: ShipmentRecord, val valueChanged: Boolean)

internal fun reviseDeliveryEstimate(shipment: ShipmentRecord, estimate: DeliveryEstimate, email: GmailPreview): EstimateUpdate? {
 val sourceAt = mailTime(email)?.at ?: return null
 if(email.messageId.isBlank() || shipment.estimatedDeliverySourceMessageId == SOURCE_MANUAL) return null
 if(shipment.estimatedDeliveryAt != null && shipment.estimatedDeliverySourceMessageId.isBlank()) return null
 if(shipment.estimatedDeliverySourceAt?.let { sourceAt <= it } == true) return null
 val start = deliveryEpoch(estimate.start)
 val end = estimate.end?.let(::deliveryEpoch)
 return EstimateUpdate(shipment.copy(estimatedDeliveryAt = start, estimatedDeliveryEndAt = end,
  estimatedDeliverySourceMessageId = email.messageId, estimatedDeliverySourceAt = sourceAt),
  shipment.estimatedDeliveryAt != start || shipment.estimatedDeliveryEndAt != end)
}

internal fun manualDeliveryEstimate(shipment: ShipmentRecord, selected: Long?, edited: Boolean): ShipmentRecord {
 if(!edited) return shipment
 val date = selected?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
 return shipment.copy(estimatedDeliveryAt = date?.let(::deliveryEpoch), estimatedDeliveryEndAt = null,
  estimatedDeliverySourceMessageId = SOURCE_MANUAL, estimatedDeliverySourceAt = null)
}
