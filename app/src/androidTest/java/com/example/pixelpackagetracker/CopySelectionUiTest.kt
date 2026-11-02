package com.example.pixelpackagetracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on a separate disposable AVD. Never uses Room, Gmail or working records. */
@RunWith(AndroidJUnit4::class)
class CopySelectionUiTest {
 @get:Rule val compose = createComposeRule()
 private val fields = listOf(CopyField("order", "Order number", "000123-AB#/9"), CopyField("handoff", "Handoff tracking number (USPS)", "ESUS366961581"))
 private fun showDialog(copy: (String) -> Unit = {}, cancel: () -> Unit = {}) {
  compose.setContent { MaterialTheme { CopySelectionDialog("Copy order details", fields, onDismiss = cancel, onCopy = copy) } }
 }
 @Test fun startsEmptyAndSupportsRowCheckboxSelectAllClearAndCancel() {
  var copied: String? = null; var cancelled = false
  showDialog({ copied = it }, { cancelled = true })
  compose.onNodeWithText("Copy order details").assertIsDisplayed()
  compose.onNodeWithText("Copy (0)").assertIsNotEnabled()
  val row = compose.onNode(isToggleable() and hasText("Order number"))
  row.assertIsOff().performClick().assertIsOn()
  compose.onNodeWithText("Copy (1)").assertIsEnabled()
  // Tap the checkbox side of the row as well as its text side.
  row.performTouchInput { click(centerLeft + androidx.compose.ui.geometry.Offset(20f, 0f)) }.assertIsOff()
  compose.onNodeWithText("Select all").performClick()
  compose.onNodeWithText("Copy (2)").assertIsEnabled()
  compose.onNodeWithText("Clear").performClick()
  compose.onNodeWithText("Copy (0)").assertIsNotEnabled()
  compose.onNodeWithText("Cancel").performClick()
  compose.runOnIdle { assertTrue(cancelled); assertNull(copied) }
 }
 @Test fun selectingOneFieldCopiesTheRawValue() {
  var copied: String? = null
  showDialog({ copied = it })
  compose.onNodeWithText("Order number").performClick()
  compose.onNodeWithText("Copy (1)").performClick()
  compose.runOnIdle { assertEquals("000123-AB#/9", copied) }
 }
 @Test fun selectingBothFieldsProducesLabeledLines() {
  var copied: String? = null
  showDialog({ copied = it })
  compose.onNodeWithText("Select all").performClick()
  compose.onNodeWithText("Copy (2)").performClick()
  compose.runOnIdle { assertEquals("Order number: 000123-AB#/9\nHandoff tracking number (USPS): ESUS366961581", copied) }
 }
 @Test fun cancelPreservesClipboardAndCopyClosesThenReopensWithNoSelection() {
  val instrumentation = InstrumentationRegistry.getInstrumentation()
  val context = instrumentation.targetContext
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
  try {
   compose.setContent { MaterialTheme { CopyDetailsButton("Copy order details", fields) } }
   compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("Sentinel", "unchanged sentinel")) }
   compose.runOnIdle { assumeTrue(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "unchanged sentinel") }
   compose.onNodeWithContentDescription("Copy order details").performClick()
   compose.onNodeWithText("Order number").performClick()
   compose.onNodeWithText("Cancel").performClick()
   compose.onNodeWithText("Copy (1)").assertDoesNotExist()
   compose.runOnIdle { assertEquals("unchanged sentinel", clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
   compose.onNodeWithContentDescription("Copy order details").performClick()
   compose.onNodeWithText("Copy (0)").assertIsNotEnabled()
   compose.onNodeWithText("Handoff tracking number (USPS)").performClick()
   compose.onNodeWithText("Copy (1)").performClick()
   compose.onNodeWithText("Copy (1)").assertDoesNotExist()
   compose.runOnIdle { assertEquals("ESUS366961581", clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
   compose.onNodeWithContentDescription("Copy order details").performClick()
   compose.onNodeWithText("Copy (0)").assertIsNotEnabled()
  } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
 }
 private fun showShipment(archived: Boolean = false) {
  compose.setContent { MaterialTheme {
   ShipmentDetailCard(ShipmentRecord(id = 4, title = "Test parcel", status = if(archived) "Delivered" else "In Transit", archived = archived), null,
    listOf(ShipmentTrackingNumberRecord(id = 7, shipmentId = 4, number = "000123", carrier = "FedEx", trackingUrl = "https://example.com/000123")), emptyList(), emptyList(),
    onStatus = {}, onEdit = { _, _, _, _, _ -> }, onEvent = { _, _ -> }, onLink = {}, onHandoff = { _, _ -> }, onArchive = {}, onDelete = {}, open = {}, onUpdate = {})
  } }
 }
 @Test fun collapsedAndExpandedShipmentHaveOneHeaderIconAndKeepExistingActions() {
  showShipment()
  compose.onAllNodesWithContentDescription("Copy shipment details").assertCountEquals(1)
  compose.onNodeWithContentDescription("Copy shipment details").performClick()
  compose.onNodeWithText("Copy shipment details").assertIsDisplayed()
  compose.onNodeWithText("Cancel").performClick()
  compose.onNodeWithText("Shipment details", substring = true).assertIsDisplayed().performClick()
  compose.onAllNodesWithContentDescription("Copy shipment details").assertCountEquals(1)
  compose.onNodeWithText("Copy shipment details").assertDoesNotExist()
  compose.onNodeWithText("Hide details", substring = true).assertIsDisplayed()
  compose.onNodeWithText("Update").assertIsDisplayed()
  compose.onNodeWithText("Archive shipment").assertIsDisplayed()
  compose.onNodeWithText("Delete shipment").assertIsDisplayed()
 }
 @Test fun archivedDeliveredShipmentHasTheSameAccessibleDialog() {
  showShipment(archived = true)
  compose.onAllNodesWithContentDescription("Copy shipment details").assertCountEquals(1)
  compose.onNodeWithContentDescription("Copy shipment details").performClick()
  compose.onNodeWithText("Copy shipment details").assertIsDisplayed()
  compose.onNodeWithText("Copy (0)").assertIsNotEnabled()
 }
}
