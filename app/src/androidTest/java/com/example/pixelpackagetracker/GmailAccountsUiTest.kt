package com.example.pixelpackagetracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** No real Gmail account, network request or app database is used. */
@RunWith(AndroidJUnit4::class)
class GmailAccountsUiTest {
 @get:Rule val compose = createComposeRule()
 private var savedToken: String? = null
 private var savedStatus = ""
 private var savedDisconnected = false
 private var savedChecked = false
 @Before fun rememberSession() {
  savedToken = GmailConnectionState.token
  savedStatus = GmailConnectionState.status
  savedDisconnected = GmailConnectionState.disconnectedByUser
  savedChecked = GmailConnectionState.checked
  GmailConnectionState.checked = true
 }
 @After fun restoreSession() {
  GmailConnectionState.token = savedToken
  GmailConnectionState.status = savedStatus
  GmailConnectionState.disconnectedByUser = savedDisconnected
  GmailConnectionState.checked = savedChecked
 }
 private fun show(connected: Boolean, onScan: () -> Unit = {}) {
  GmailConnectionState.token = if(connected) "isolated-ui-token" else null
  compose.setContent { MaterialTheme { GmailAccountsSection(TrackerSettings(), onScan) } }
 }
 @Test fun disconnectedHasConnectAndScanOnTheSameRowAndProviderPlaceholders() {
  show(false)
  val connect = compose.onNodeWithText("Connect to Email").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
  val scan = compose.onNodeWithText("Scan / Import").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
  assertEquals(connect.center.y, scan.center.y, 1f)
  assertTrue(connect.right < scan.left)
  compose.onNodeWithText("Disconnected").assertIsDisplayed()
  compose.onNodeWithText("Yahoo Mail", substring = true).assertIsDisplayed()
  compose.onNodeWithText("Outlook", substring = true).assertIsDisplayed()
  compose.onNodeWithText("Disconnect session").assertDoesNotExist()
 }
 @Test fun connectedOverflowCancellationPreservesSessionAndPrimaryLabels() {
  show(true)
  val reconnect = compose.onNodeWithText("Reconnect Email").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
  val scan = compose.onNodeWithText("Scan / Import").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
  assertEquals(reconnect.center.y, scan.center.y, 1f)
  compose.onNodeWithText("Disconnect session").assertDoesNotExist()
  compose.onNodeWithContentDescription("Gmail account menu").performClick()
  compose.onNodeWithText("Disconnect session").performClick()
  compose.onNodeWithText("temporary Gmail session token", substring = true).assertIsDisplayed()
  compose.onNodeWithText("Cancel").performClick()
  compose.runOnIdle { assertEquals("isolated-ui-token", GmailConnectionState.token) }
  compose.onNodeWithText("Reconnect Email").assertIsDisplayed()
 }
 @Test fun confirmingDisconnectChangesToConnectWithoutRevokingAuthorization() {
  show(true)
  compose.onNodeWithContentDescription("Gmail account menu").performClick()
  compose.onNodeWithText("Disconnect session").performClick()
  compose.onNodeWithText("does not revoke Google account authorization", substring = true).assertIsDisplayed()
  compose.onNodeWithText("Disconnect", useUnmergedTree = true).performClick()
  compose.runOnIdle { assertNull(GmailConnectionState.token); assertTrue(GmailConnectionState.disconnectedByUser) }
  compose.onNodeWithText("Connect to Email").assertIsDisplayed()
 }
 @Test fun scanImportUsesTheExistingCallback() {
  var scans = 0
  show(true) { scans++ }
  compose.onNodeWithText("Scan / Import").performClick()
  compose.runOnIdle { assertEquals(1, scans) }
 }
}
