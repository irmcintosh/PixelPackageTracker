package com.example.pixelpackagetracker

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// On ART every java.util.regex.Matcher copies its whole input into native memory that only a GC frees. Before the fix,
// analysing one ~120K-character marketing email opened a Matcher per word and exhausted the device.
@RunWith(AndroidJUnit4::class)
class RegexNativeMemoryTest {
 private val body = (1..1500).joinToString("\n") { "Line $it of a marketing email with some words, a link https://shop.example/p/$it and nothing about shipping." }
 private val email = GmailPreview("Shop <news@shop.com>", "Weekly deals", "Mon, 15 Jan 2024 10:00:00 +0000", "", "t", body, "m")
 private fun nativeMb() = Debug.getNativeHeapAllocatedSize() / 1_048_576

 @Test fun analysingALargeEmailRepeatedlyKeepsNativeMemoryBounded() {
  Runtime.getRuntime().gc()
  val before = nativeMb()
  var peak = before
  repeat(10) {
   val ev = buildEvidence(email)
   isRelevantEmail(ev)
   extractOrderDetails(email)
   peak = maxOf(peak, nativeMb())
  }
  Log.i("PPTMEM", "large email x10: native ${before}MB -> peak ${peak}MB")
  assertTrue("native heap grew by ${peak - before} MB", peak - before < 150)
 }

 @Test fun singleMatcherHelpersReturnTheSameMatchesAsKotlinRegex() {
  val re = Regex("(?i)order\\s*#?\\s*([A-Z0-9-]{5,})|(track)")
  val text = "Order #A-12345 shipped. Track it. order 99999-B and ORDER #x1y2z3 track"
  val kotlin = re.findAll(text).map { Triple(it.value, it.range, it.groupValues) }.toList()
  val ours = re.scan(text).map { Triple(it.value, it.range, it.groupValues) }
  assertEquals(kotlin, ours)
  assertEquals(re.replace(text) { "<${it.groupValues[1]}>" }, re.replaceEach(text) { "<${it.groupValues[1]}>" })
  assertEquals("none", Regex("zzz").replaceEach("none") { "x" })
 }
}
