package com.example.pixelpackagetracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveControlsUiTest {
 @get:Rule val compose = createComposeRule()
 @Test fun linkedShipmentHasArchiveAndConfirmedDeleteAndCancelDoesNothing() {
  var archives = 0
  var deletes = 0
  compose.setContent { MaterialTheme {
   ShipmentDetailCard(ShipmentRecord(id = 1, orderId = 2, title = "Isolated parcel", carrier = "FedEx"),
    OrderRecord(id = 2, marketplace = "Test", orderNumber = "T1", name = "Test order"), emptyList(), emptyList(), emptyList(),
    onStatus = {}, onEdit = { _, _, _, _, _ -> }, onEvent = { _, _ -> }, onLink = {}, onHandoff = { _, _ -> },
    onArchive = { archives++ }, onDelete = { deletes++ }, open = {})
  } }
  compose.onNodeWithText("Archive shipment").assertIsDisplayed().performClick()
  compose.runOnIdle { assertEquals(1, archives); assertEquals(0, deletes) }
  compose.onNodeWithText("Delete shipment").performClick()
  compose.onNodeWithText("This cannot be undone.", substring = true).assertIsDisplayed()
  compose.onNodeWithText("Cancel").performClick()
  compose.runOnIdle { assertEquals(0, deletes) }
  compose.onNodeWithText("Delete shipment").performClick()
  compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
  compose.runOnIdle { assertEquals(1, deletes) }
 }
 @Test fun deleteAllShowsExactCountsAndCancelDoesNotConfirm() {
  var confirmed = 0; var dismissed = 0
  compose.setContent { MaterialTheme {
   ArchiveDeletionDialog(ArchiveSelection(listOf(1, 2), listOf(3, 4, 5)), { confirmed++ }, { dismissed++ })
  } }
  compose.onNodeWithText("2 archived orders and 3 archived shipments", substring = true).assertIsDisplayed()
  compose.onNodeWithText("This cannot be undone.", substring = true).assertIsDisplayed()
  compose.onNodeWithText("Cancel").performClick()
  compose.runOnIdle { assertEquals(0, confirmed); assertEquals(1, dismissed) }
 }
 @Test fun deleteAllRequiresConfirmCallback() {
  var confirmed = 0
  compose.setContent { MaterialTheme {
   ArchiveDeletionDialog(ArchiveSelection(listOf(1), listOf(2)), { confirmed++ }, {})
  } }
  compose.runOnIdle { assertEquals(0, confirmed) }
  compose.onNodeWithText("Delete All").performClick()
  compose.runOnIdle { assertEquals(1, confirmed) }
 }
 @Test fun deleteAllIsDisabledForAnEmptyArchive() {
  compose.setContent { MaterialTheme { ArchiveDeleteAllButton(ArchiveSelection(emptyList(), emptyList())) {} } }
  compose.onNodeWithText("Delete All").assertIsNotEnabled()
 }

}
