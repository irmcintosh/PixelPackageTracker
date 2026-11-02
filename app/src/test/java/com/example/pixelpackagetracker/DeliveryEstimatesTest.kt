package com.example.pixelpackagetracker

import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class DeliveryEstimatesTest {
 private fun mail(text: String, date: String = "Wed, 28 Oct 2026 23:30:00 -0700", id: String = "eta") =
  GmailPreview("FedEx <updates@fedex.com>", "Your shipment update", date, "", body = text, messageId = id)
 private fun estimate(text: String, date: String = "Wed, 28 Oct 2026 23:30:00 -0700") =
  extractDeliveryEstimate(mail(text, date)).estimate
 private val oct30 = DeliveryEstimate(LocalDate.of(2026, 10, 30))
 private fun updated(ship: ShipmentRecord, text: String, date: String, id: String) =
  reviseDeliveryEstimate(ship, extractDeliveryEstimate(mail(text, date, id)).estimate!!, mail(text, date, id))

 @Test fun exactDatesAndExplicitWeekdayDates() {
  assertEquals(oct30, estimate("Estimated delivery: October 30, 2026"))
  assertEquals(oct30, estimate("Expected delivery on Friday, October 30"))
  assertEquals(oct30, estimate("Scheduled delivery: 2026-10-30"))
  assertEquals(oct30, extractDeliveryEstimate(mail("Estimated delivery: October 30, 2026").copy(subject = "Delivery estimate updated")).estimate)
  assertEquals(oct30, estimate("Scheduled delivery: October 30, 2026", "Wed, 28 Oct 2026 10:00:00 GMT"))
 }
 @Test fun aDateImmediatelyBelowItsDeliveryLabelIsSupportedButUnrelatedLinesAreNot() {
  assertEquals(oct30, estimate("Estimated delivery:\nOctober 30, 2026"))
  assertEquals(oct30, estimate("Expected delivery\n\nOctober 30, 2026"))
  assertNull(estimate("Estimated delivery:\n> October 30, 2026"))
  assertNull(estimate("Estimated delivery:\nTracking number: 38429466112\nOctober 30, 2026"))
 }
 @Test fun rangesAreOneEstimateIncludingCrossMonthAndYear() {
  assertEquals(DeliveryEstimate(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 12)), estimate("Estimated delivery: November 5\u201312"))
  assertEquals(DeliveryEstimate(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2)), estimate("Expected delivery: Oct 30 - Nov 2"))
  assertEquals(DeliveryEstimate(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2)),
   estimate("Estimated delivery: December 30 - January 2", "Mon, 28 Dec 2026 12:00:00 +0000"))
  assertEquals(DeliveryEstimate(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 12)), estimate("Estimated delivery: November 5-12, 2026", "unreadable"))
 }
 @Test fun tomorrowUsesHistoricalLocalMessageDateNotUtcOrScanDate() {
  assertEquals(DeliveryEstimate(LocalDate.of(2024, 1, 4)), estimate("Arriving tomorrow", "Wed, 03 Jan 2024 23:30:00 -0700"))
  assertEquals(DeliveryEstimate(LocalDate.of(2024, 1, 4)), estimate("Arriving tomorrow", "Wed, 03 Jan 2024 00:30:00 +1400"))
 }
 @Test fun weekdaysUseSameDayOrNextOccurrence() {
  assertEquals(oct30, estimate("Your package will arrive by Friday"))
  assertEquals(oct30, estimate("Arriving Friday", "Fri, 30 Oct 2026 10:00:00 +0000"))
 }
 @Test fun missingYearsRollFromDecemberIntoJanuaryButNotArbitrarily() {
  assertEquals(DeliveryEstimate(LocalDate.of(2027, 1, 2)), estimate("Estimated delivery: January 2", "Thu, 31 Dec 2026 10:00:00 +0000"))
  assertNull(estimate("Estimated delivery: January 2"))
 }
 @Test fun invalidReversedAndAmbiguousDatesAreRejected() {
  for(text in listOf("February 30, 2026", "October 30, 20265", "Friday, October 31", "November 12-5", "October 30 or November 2", "2026-02-29", "in 2 business days")) {
   val finding = extractDeliveryEstimate(mail("Estimated delivery: $text"))
   assertNull(text, finding.estimate); assertNotNull(text, finding.problem)
  }
 }
 @Test fun explicitRevisionWinsOverOldEstimatesAndQuotedReplies() {
  assertEquals(DeliveryEstimate(LocalDate.of(2026, 11, 2)), estimate("Your delivery date has changed to November 2\nEstimated delivery: October 30, 2026"))
  assertEquals(oct30, estimate("Estimated delivery: October 30, 2026\n> Estimated delivery: October 29, 2026"))
  assertNull(estimate("Estimated delivery: October 30, 2026\nEstimated delivery: November 2, 2026"))
  assertEquals(oct30, estimate(htmlToText("<div>Estimated delivery: October 30, 2026</div><blockquote>Your delivery date has changed to November 2</blockquote>")))
  assertEquals(oct30, estimate(htmlToText("<p>Estimated delivery: October 30, 2026</p><div class=\"gmail_quote\">Your delivery date has changed to November 2</div>")))
 }
 @Test fun promotionalPurchaseAndActualDeliveryDatesAreNotEstimates() {
  assertNull(estimate("Sale ends Friday\nOrder date: October 30, 2026"))
  assertNull(extractDeliveryEstimate(mail("Estimated delivery: October 30, 2026").copy(subject = "Save 20% on your next order")).estimate)
  assertNull(estimate("Actual delivery date: October 30, 2026"))
  assertNull(estimate("Your package was delivered. Delivery date: October 30, 2026"))
  assertNull(estimate("Shipped October 28, 2026"))
 }
 @Test fun internalDateFallbackDoesNotInventSenderTimezone() {
  val email = mail("Estimated delivery: October 30, 2026", "unreadable").copy(internalDate = 100L)
  assertEquals(oct30, extractDeliveryEstimate(email).estimate)
  assertEquals(100L, mailTime(email)!!.at)
  assertNull(mailTime(email)!!.localDate)
  assertNull(extractDeliveryEstimate(email.copy(body = "Arriving tomorrow")).estimate)
  assertNotNull(reviseDeliveryEstimate(ShipmentRecord(), oct30, email))
  assertNull(reviseDeliveryEstimate(ShipmentRecord(), oct30, email.copy(internalDate = null)))
 }
 @Test fun newerEvidenceRevisesButOlderAndEqualConflictsDoNot() {
  val first = updated(ShipmentRecord(), "Estimated delivery: October 30, 2026", "Wed, 28 Oct 2026 10:00:00 +0000", "a")!!
  assertTrue(first.valueChanged)
  val next = updated(first.shipment, "Estimated delivery: November 2, 2026", "Thu, 29 Oct 2026 10:00:00 +0000", "b")!!
  assertEquals(deliveryEpoch(LocalDate.of(2026, 11, 2)), next.shipment.estimatedDeliveryAt)
  assertNull(updated(next.shipment, "Estimated delivery: October 30, 2026", "Wed, 28 Oct 2026 10:00:00 +0000", "a"))
  assertNull(updated(next.shipment, "Estimated delivery: November 3, 2026", "Thu, 29 Oct 2026 10:00:00 +0000", "c"))
 }
 @Test fun identicalNewerEvidenceAdvancesFreshnessWithoutValueChange() {
  val first = updated(ShipmentRecord(), "Estimated delivery: October 30, 2026", "Wed, 28 Oct 2026 10:00:00 +0000", "a")!!
  val next = updated(first.shipment, "Estimated delivery: October 30, 2026", "Thu, 29 Oct 2026 10:00:00 +0000", "b")!!
  assertFalse(next.valueChanged)
  assertEquals("b", next.shipment.estimatedDeliverySourceMessageId)
  assertTrue(next.shipment.estimatedDeliverySourceAt!! > first.shipment.estimatedDeliverySourceAt!!)
  assertNull(reviseDeliveryEstimate(next.shipment, oct30, mail("No delivery date", id = "b")))
  assertNull(extractDeliveryEstimate(mail("No delivery estimate available.")).estimate)
 }
 @Test fun manualValuesClearingAndLegacyValuesAreProtected() {
  assertNull(reviseDeliveryEstimate(ShipmentRecord(estimatedDeliveryAt = 123), oct30, mail("")))
  val manual = manualDeliveryEstimate(ShipmentRecord(), 123L, true)
  assertEquals(SOURCE_MANUAL, manual.estimatedDeliverySourceMessageId)
  assertNull(reviseDeliveryEstimate(manual, oct30, mail("")))
  val cleared = manualDeliveryEstimate(manual, null, true)
  assertNull(cleared.estimatedDeliveryAt)
  assertEquals(SOURCE_MANUAL, cleared.estimatedDeliverySourceMessageId)
  assertNull(reviseDeliveryEstimate(cleared, oct30, mail("")))
 }
 @Test fun unrelatedEditsPreserveRangeAndProvenance() {
  val ship = ShipmentRecord(estimatedDeliveryAt = 10, estimatedDeliveryEndAt = 20, estimatedDeliverySourceMessageId = "email", estimatedDeliverySourceAt = 30)
  assertEquals(ship, manualDeliveryEstimate(ship, null, false))
 }
 @Test fun dateOnlyFormattingAndEditorRemainStableAcrossDeviceTimezones() {
  val previous = TimeZone.getDefault()
  try {
   val ship = ShipmentRecord(estimatedDeliveryAt = deliveryEpoch(oct30.start), estimatedDeliveryEndAt = deliveryEpoch(LocalDate.of(2026, 11, 2)), estimatedDeliverySourceMessageId = "eta")
   for(zone in listOf("Pacific/Honolulu", "Pacific/Kiritimati")) {
    TimeZone.setDefault(TimeZone.getTimeZone(zone))
    assertEquals("Oct 30, 2026 - Nov 2, 2026", formatDeliveryEstimate(ship))
    assertEquals("2026-10-30", deliveryEditorDate(ship))
    assertEquals(formatDeliveryEstimate(ship), shipmentCopyFields(ship, emptyList(), null).single { it.id == "estimated" }.value)
   }
  } finally { TimeZone.setDefault(previous) }
 }
 @Test fun inboxLabelChecksNeverAdmitExplicitNonInboxLabels() {
  assertTrue(messageLabelsAreInInbox(listOf("INBOX", "UNREAD")))
  assertFalse(messageLabelsAreInInbox(listOf("SENT", "CATEGORY_UPDATES")))
  assertFalse(messageLabelsAreInInbox(emptyList()))
  assertTrue(messageLabelsAreInInbox(null))
  assertTrue(gmailInboxMessagesListPath("next").contains("labelIds=INBOX"))
 }
 @Test fun estimateOnlyMailIsNotShipmentMovementEvidence() {
  assertTrue(isEstimateOnlyEmail(buildEvidence(mail("Tracking number: 38429466112\nEstimated delivery: October 30, 2026"))))
  assertFalse(isEstimateOnlyEmail(buildEvidence(mail("Your package has shipped with FedEx.\nEstimated delivery: October 30, 2026"))))
  assertFalse(isEstimateOnlyEmail(buildEvidence(mail("Your package is out for delivery.\nEstimated delivery: October 30, 2026"))))
 }
}
