package com.example.pixelpackagetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncTest {
 @Test fun eachStatusFollowsItsOwnNotificationSwitch() {
  val all = TrackerSettings(notifyShipped = false, notifyOut = false, notifyDelivered = false, notifyException = false)
  listOf("Shipped", "In Transit", "Out for Delivery", "Delivered", "Exception", "Customs").forEach { assertFalse(it, BackgroundSync.shouldNotifyStatusChange(all, it)) }
  assertTrue(BackgroundSync.shouldNotifyStatusChange(all.copy(notifyShipped = true), "In Transit"))
  assertTrue(BackgroundSync.shouldNotifyStatusChange(all.copy(notifyOut = true), "Out for Delivery"))
  assertTrue(BackgroundSync.shouldNotifyStatusChange(all.copy(notifyDelivered = true), "Delivered"))
  assertTrue(BackgroundSync.shouldNotifyStatusChange(all.copy(notifyException = true), "Exception"))
  assertFalse(BackgroundSync.shouldNotifyStatusChange(all.copy(notifyDelivered = true), "Exception"))
 }

 @Test fun everyTrackingUpdateNotifiesForAnyStatus() {
  val settings = TrackerSettings(notifyShipped = false, notifyOut = false, notifyDelivered = false, notifyException = false, notifyEvery = true)
  assertTrue(BackgroundSync.shouldNotifyStatusChange(settings, "Label Created"))
  assertTrue(BackgroundSync.shouldNotifyStatusChange(settings, "Delivered"))
 }

 @Test fun defaultsNotifyForDeliveryMilestones() {
  val defaults = TrackerSettings()
  assertTrue(BackgroundSync.shouldNotifyStatusChange(defaults, "Delivered"))
  assertEquals("8", defaults.refreshHours)
 }

 private fun order(id: Long, status: String) = OrderRecord(id = id, marketplace = "Shop", orderNumber = "A-$id", name = "Item $id", status = status)
 private fun ship(id: Long, status: String) = ShipmentRecord(id = id, orderId = 1, title = "Parcel $id", status = status)
 private fun snap(orders: List<OrderRecord> = emptyList(), ships: List<ShipmentRecord> = emptyList()) =
  StatusSnapshot(orders.associateBy { it.id }, ships.associateBy { it.id })

 @Test fun everyOrderProgressStepAndCancellationNotifiesOnceWithDefaultSettings() {
  val steps = listOf("Order Placed", "Processing", "Preparing for Shipment", "Shipped", "Cancelled")
  steps.zipWithNext().forEach { (from, to) ->
   val notices = statusNotices(TrackerSettings(), snap(listOf(order(1, from))), snap(listOf(order(1, to))))
   assertEquals("$from -> $to", 1, notices.size)
   assertEquals("Order updated", notices.single().title)
   assertEquals("Shop #A-1: $to", notices.single().message)
  }
 }

 @Test fun unchangedStatusesNewRecordsAndDeletedRecordsNeverNotify() {
  val same = snap(listOf(order(1, "Processing")), listOf(ship(7, "In Transit")))
  assertTrue(statusNotices(TrackerSettings(), same, snap(listOf(order(1, "Processing")), listOf(ship(7, "In Transit")))).isEmpty())
  assertTrue(statusNotices(TrackerSettings(), snap(), snap(listOf(order(2, "Shipped")), listOf(ship(8, "Delivered")))).isEmpty())
  assertTrue(statusNotices(TrackerSettings(), same, snap()).isEmpty())
 }

 @Test fun orderNotificationsFollowTheirSwitches() {
  val placed = snap(listOf(order(1, "Order Placed")))
  fun count(settings: TrackerSettings, to: String) = statusNotices(settings, placed, snap(listOf(order(1, to)))).size
  val off = TrackerSettings(notifyOrder = false, notifyShipped = false, notifyException = false)
  assertEquals(0, count(off, "Processing"))
  assertEquals(0, count(off, "Preparing for Shipment"))
  assertEquals(0, count(off, "Shipped"))
  assertEquals(0, count(off, "Cancelled"))
  assertEquals(1, count(off.copy(notifyOrder = true), "Processing"))
  assertEquals(1, count(off.copy(notifyOrder = true), "Preparing for Shipment"))
  assertEquals(0, count(off.copy(notifyOrder = true), "Shipped"))
  assertEquals(1, count(off.copy(notifyShipped = true), "Shipped"))
  assertEquals(1, count(off.copy(notifyException = true), "Cancelled"))
  assertEquals(1, count(off.copy(notifyEvery = true), "Processing"))
 }

 @Test fun shipmentNoticesKeepTheirExistingCategoriesAndIdsDoNotCollideWithOrders() {
  val before = snap(listOf(order(7, "Shipped")), listOf(ship(7, "In Transit")))
  val after = snap(listOf(order(7, "Cancelled")), listOf(ship(7, "Delivered")))
  val notices = statusNotices(TrackerSettings(), before, after)
  assertEquals(setOf("Order updated", "Shipment updated"), notices.map { it.title }.toSet())
  assertEquals(2, notices.map { it.id }.toSet().size)
  assertEquals("Parcel 7: Delivered", notices.single { it.title == "Shipment updated" }.message)
  assertTrue(statusNotices(TrackerSettings(notifyDelivered = false), before, snap(listOf(order(7, "Shipped")), listOf(ship(7, "Delivered")))).isEmpty())
 }
}
