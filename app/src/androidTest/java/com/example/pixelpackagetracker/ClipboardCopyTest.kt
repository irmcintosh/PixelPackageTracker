package com.example.pixelpackagetracker

import android.content.ClipboardManager
import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardCopyTest {
 private val instrumentation = InstrumentationRegistry.getInstrumentation()
 private val context: Context = instrumentation.targetContext

 // Android only lets the focused app or the shell read the clipboard, so the read uses the shell identity.
 private fun copyAndRead(label: String, value: String): String? {
  var result: String? = null
  instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
  try {
   instrumentation.runOnMainSync {
    copyToClipboard(context, label, value)
    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
    result = clip?.getItemAt(0)?.text?.toString()
   }
  } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
  return result
 }

 @Test fun identifiersAreCopiedExactlyIncludingLeadingZerosAndSpecialCharacters() {
  val values = listOf("00123-AB#/9", "0012345678901234", "1Z999AA10123456784", "9400 1118 9922 3197 4284 90", "https://shop.example/o/001?a=1&b=2")
  val first = copyAndRead("Order number", values[0])
  assumeTrue("the shell identity could not read the clipboard on this device", first != null)
  assertEquals(values[0], first)
  values.drop(1).forEach { assertEquals(it, copyAndRead("Tracking number", it)) }
 }

 @Test fun copyingNeverChangesStoredRecords() = runBlocking<Unit> {
  val db = Room.inMemoryDatabaseBuilder(context, PackageDatabase::class.java).allowMainThreadQueries().build()
  try {
   val dao = db.orderDao()
   val id = dao.insertOrder(OrderRecord(marketplace = "Shop", orderNumber = "000777", name = "Item", status = "Shipped"))
   dao.insertShipment(ShipmentRecord(id = 51, orderId = id, status = "In Transit", carrier = "FedEx"))
   dao.insertTrackingNumber(ShipmentTrackingNumberRecord(shipmentId = 51, number = "0012345", carrier = "FedEx"))
   val orders = dao.allOrders(); val shipments = dao.allShipments(); val numbers = dao.allTrackingNumbers()
   val orderFields = orderCopyFields(orders.single(), shipments, numbers, emptyList())
   copyAndRead("Order details", formatCopySelection(orderFields, orderFields.map { it.id }.toSet()))
   val shipmentFields = shipmentCopyFields(shipments.single(), numbers, orders.single())
   copyAndRead("Shipment details", formatCopySelection(shipmentFields, shipmentFields.map { it.id }.toSet()))
   assertEquals(orders, dao.allOrders()); assertEquals(shipments, dao.allShipments()); assertEquals(numbers, dao.allTrackingNumbers())
   assertEquals("000777", dao.allOrders().single().orderNumber)
  } finally { db.close() }
 }
}
