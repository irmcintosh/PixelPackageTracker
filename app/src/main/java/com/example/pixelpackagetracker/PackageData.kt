package com.example.pixelpackagetracker

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "packages", indices = [Index(value = ["carrier", "trackingNumber"])])
data class TrackedPackage(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val name: String,
 val carrier: String,
 val trackingNumber: String,
 val status: String = "Added manually",
 val archived: Boolean = false,
 val createdAt: Long = System.currentTimeMillis(),
 val marketplace: String = "Other",
 val orderId: String = "",
 val lastMileTrackingNumber: String = "",
 val lastMileCarrier: String = "",
 val statusUpdatedAt: Long = System.currentTimeMillis(),
 val deliveredAt: Long? = null,
 val orderUrl: String = "",
 val trackingUrl: String = "",
 val orderStatus: String = "Order placed"
)

@Entity(tableName = "tracking_events", foreignKeys = [ForeignKey(entity = TrackedPackage::class, parentColumns = ["id"], childColumns = ["packageId"], onDelete = ForeignKey.CASCADE)], indices = [Index("packageId")])
data class TrackingEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0, val packageId: Long, val description: String, val carrier: String = "", val eventAt: Long = System.currentTimeMillis())

@Entity(tableName = "tracking_identifiers", foreignKeys = [ForeignKey(entity = TrackedPackage::class, parentColumns = ["id"], childColumns = ["packageId"], onDelete = ForeignKey.CASCADE)], indices = [Index("packageId")])
data class TrackingIdentifier(@PrimaryKey(autoGenerate = true) val id: Long = 0, val packageId: Long, val number: String, val carrier: String, val role: String = "handoff")

// Phase 2 normalized records. Legacy packages remain intact until the UI migration.
@Entity(tableName = "orders", indices = [Index(value = ["marketplace", "orderNumber"])])
data class OrderRecord(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val marketplace: String,
 val orderNumber: String,
 val name: String,
 val status: String = "Order Placed",
 val placedAt: Long? = null,
 val orderUrl: String = "",
 val notes: String = "",
 val archived: Boolean = false,
 val createdAt: Long = System.currentTimeMillis(),
 val statusUpdatedAt: Long = System.currentTimeMillis(),
 val lastSyncedAt: Long? = null,
 val archivedAt: Long? = null
)

@Entity(tableName = "order_items", foreignKeys = [ForeignKey(entity = OrderRecord::class, parentColumns = ["id"], childColumns = ["orderId"], onDelete = ForeignKey.CASCADE)], indices = [Index("orderId")])
data class OrderItemRecord(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val orderId: Long,
 val name: String,
 val quantity: Int = 1,
 val priceMinor: Long? = null,
 val currency: String = "USD"
)

@Entity(tableName = "shipments", foreignKeys = [ForeignKey(entity = OrderRecord::class, parentColumns = ["id"], childColumns = ["orderId"], onDelete = ForeignKey.CASCADE)], indices = [Index("orderId")])
data class ShipmentRecord(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val orderId: Long? = null,
 val status: String = "Awaiting Tracking",
 val estimatedDeliveryAt: Long? = null,
 val deliveredAt: Long? = null,
 val statusUpdatedAt: Long = System.currentTimeMillis(),
 val statusSource: String = "manual",
 val archived: Boolean = false,
 val title: String = "",
 // Carrier known independently of any tracking number (a Gmail notice can name the carrier without one).
 val carrier: String = "",
 val shippedAt: Long? = null,
 val estimatedDeliveryEndAt: Long? = null,
 @ColumnInfo(defaultValue = "''") val estimatedDeliverySourceMessageId: String = "",
 val estimatedDeliverySourceAt: Long? = null
)

@Entity(tableName = "shipment_tracking_numbers", foreignKeys = [ForeignKey(entity = ShipmentRecord::class, parentColumns = ["id"], childColumns = ["shipmentId"], onDelete = ForeignKey.CASCADE)], indices = [Index("shipmentId")])
data class ShipmentTrackingNumberRecord(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val shipmentId: Long,
 val number: String,
 val carrier: String,
 val role: String = "primary",
 val trackingUrl: String = ""
)

@Entity(tableName = "shipment_tracking_events", foreignKeys = [ForeignKey(entity = ShipmentRecord::class, parentColumns = ["id"], childColumns = ["shipmentId"], onDelete = ForeignKey.CASCADE)], indices = [Index("shipmentId")])
data class ShipmentTrackingEventRecord(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val shipmentId: Long,
 val trackingNumberId: Long? = null,
 val description: String,
 val location: String = "",
 val carrier: String = "",
 val status: String = "",
 val source: String = "manual",
 val eventAt: Long = System.currentTimeMillis(),
 val sourceMessageId: String = ""
)

@Entity(tableName = "custom_marketplaces", indices = [Index(value = ["name"], unique = true)])
data class CustomMarketplaceRecord(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val name: String,
 val website: String = ""
)


@Entity(tableName = "processed_gmail_messages", foreignKeys = [ForeignKey(entity = OrderRecord::class, parentColumns = ["id"], childColumns = ["orderId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["messageId", "orderId"], unique = true), Index("orderId")])
data class ProcessedGmailMessage(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val messageId: String,
 val orderId: Long,
 val processedAt: Long = System.currentTimeMillis(),
 val extractionVersion: Int = GMAIL_PARSER_VERSION
)

@Entity(tableName = "order_change_history", foreignKeys = [ForeignKey(entity = OrderRecord::class, parentColumns = ["id"], childColumns = ["orderId"], onDelete = ForeignKey.CASCADE)], indices = [Index("orderId")])
data class OrderChangeHistory(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val orderId: Long,
 val field: String,
 val oldValue: String,
 val newValue: String,
 val sourceMessageId: String = "",
 val sourceDate: String = "",
 val appliedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "deleted_order_markers", indices = [Index(value = ["marketplace", "orderNumber"], unique = true)])
data class DeletedOrderMarker(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val marketplace: String,
 val orderNumber: String,
 val deletedAt: Long = System.currentTimeMillis()
)

data class OrderKey(val marketplace: String, val orderNumber: String)

@Dao interface OrderDao {
 @Query("SELECT * FROM orders ORDER BY statusUpdatedAt DESC") fun observeOrders(): Flow<List<OrderRecord>>
 @Query("SELECT * FROM order_items ORDER BY id") fun observeAllItems(): Flow<List<OrderItemRecord>>
 @Query("SELECT * FROM shipments ORDER BY id") fun observeAllShipments(): Flow<List<ShipmentRecord>>
 @Query("SELECT * FROM shipment_tracking_numbers ORDER BY id") fun observeAllNumbers(): Flow<List<ShipmentTrackingNumberRecord>>
 @Query("SELECT * FROM shipment_tracking_events ORDER BY eventAt DESC") fun observeAllEvents(): Flow<List<ShipmentTrackingEventRecord>>
 @Query("SELECT * FROM orders WHERE id = :id LIMIT 1") suspend fun getOrder(id: Long): OrderRecord?
 @Query("SELECT * FROM order_items WHERE orderId = :id") suspend fun getItems(id: Long): List<OrderItemRecord>
 @Query("SELECT * FROM shipments WHERE orderId IS NULL ORDER BY statusUpdatedAt DESC") fun observeStandaloneShipments(): Flow<List<ShipmentRecord>>
 @Query("SELECT * FROM shipments WHERE orderId = :id ORDER BY id") suspend fun getShipments(id: Long): List<ShipmentRecord>
 @Query("SELECT * FROM shipments WHERE id = :id LIMIT 1") suspend fun getShipment(id: Long): ShipmentRecord?
 @Query("SELECT * FROM orders ORDER BY id") suspend fun allOrders(): List<OrderRecord>
 @Query("SELECT * FROM shipment_tracking_numbers WHERE shipmentId = :id ORDER BY id") suspend fun getTrackingNumbersForShipment(id: Long): List<ShipmentTrackingNumberRecord>
 @Query("SELECT * FROM shipment_tracking_numbers ORDER BY id") suspend fun allTrackingNumbers(): List<ShipmentTrackingNumberRecord>
 @Query("SELECT * FROM shipments ORDER BY id") suspend fun allShipments(): List<ShipmentRecord>
 @Query("DELETE FROM order_items WHERE orderId = :id") suspend fun clearItems(id: Long)
 @Query("DELETE FROM shipments WHERE orderId = :id") suspend fun clearShipments(id: Long)

 @Query("SELECT * FROM order_items WHERE orderId = :orderId") fun observeItems(orderId: Long): Flow<List<OrderItemRecord>>
 @Query("SELECT * FROM shipments WHERE orderId = :orderId ORDER BY id") fun observeShipments(orderId: Long): Flow<List<ShipmentRecord>>
 @Query("SELECT * FROM shipment_tracking_numbers WHERE shipmentId = :shipmentId ORDER BY id") fun observeTrackingNumbers(shipmentId: Long): Flow<List<ShipmentTrackingNumberRecord>>
 @Query("SELECT * FROM shipment_tracking_events WHERE shipmentId = :shipmentId ORDER BY eventAt DESC, id DESC") fun observeEvents(shipmentId: Long): Flow<List<ShipmentTrackingEventRecord>>
 @Query("SELECT sourceMessageId FROM shipment_tracking_events WHERE shipmentId = :shipmentId AND sourceMessageId != ''") suspend fun eventMessageIds(shipmentId: Long): List<String>
 @Query("SELECT * FROM custom_marketplaces ORDER BY name COLLATE NOCASE") fun observeMarketplaces(): Flow<List<CustomMarketplaceRecord>>
 @Query("SELECT * FROM orders WHERE marketplace = :marketplace COLLATE NOCASE AND orderNumber = :number COLLATE NOCASE") suspend fun findDuplicates(marketplace: String, number: String): List<OrderRecord>
 @Insert suspend fun insertOrder(order: OrderRecord): Long
 @Insert suspend fun insertItem(item: OrderItemRecord): Long
 @Insert suspend fun insertShipmentRow(shipment: ShipmentRecord): Long
 @Transaction suspend fun insertShipment(shipment: ShipmentRecord): Long =
  insertShipmentRow(shipment.copy(archived = shipment.archived || canonicalShipmentStatus(shipment.status) == "Delivered"))
 @Insert suspend fun insertTrackingNumber(number: ShipmentTrackingNumberRecord): Long
 @Insert suspend fun insertEvent(event: ShipmentTrackingEventRecord): Long
 @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMarketplace(marketplace: CustomMarketplaceRecord): Long
 @Update suspend fun updateOrder(order: OrderRecord)
 @Update suspend fun updateShipmentRow(shipment: ShipmentRecord)
 @Transaction suspend fun updateShipment(shipment: ShipmentRecord) {
  updateShipmentRow(shipment.copy(archived = shipment.archived || canonicalShipmentStatus(shipment.status) == "Delivered"))
 }
 @Query("UPDATE shipments SET archived = :archived WHERE id = :id") suspend fun archiveShipment(id: Long, archived: Boolean)
 @Query("UPDATE shipments SET archived = 1 WHERE archived = 0 AND LOWER(TRIM(status)) = 'delivered'") suspend fun archiveDeliveredShipments(): Int
 @Query("UPDATE shipments SET orderId = NULL WHERE orderId = :orderId") suspend fun detachShipments(orderId: Long)
 @Query("UPDATE shipments SET orderId = NULL WHERE orderId = :orderId AND archived = 0") suspend fun detachActiveShipments(orderId: Long)
 @Update suspend fun updateTrackingNumber(number: ShipmentTrackingNumberRecord)
 @Query("DELETE FROM shipment_tracking_numbers WHERE id = :id") suspend fun deleteTrackingNumber(id: Long)
 @Update suspend fun updateMarketplace(market: CustomMarketplaceRecord)
 @Delete suspend fun deleteMarketplace(market: CustomMarketplaceRecord)
 @Query("DELETE FROM shipments WHERE id = :id AND orderId IS NULL") suspend fun deleteStandaloneShipment(id: Long)
 @Query("UPDATE orders SET archived = :archived, archivedAt = CASE WHEN :archived = 1 THEN COALESCE(archivedAt, :now) ELSE NULL END WHERE id = :id") suspend fun archiveOrder(id: Long, archived: Boolean, now: Long = System.currentTimeMillis())
 @Query("DELETE FROM orders WHERE id = :id") suspend fun deleteOrder(id: Long)
 @Query("SELECT * FROM processed_gmail_messages WHERE orderId = :orderId") suspend fun processedForOrder(orderId: Long): List<ProcessedGmailMessage>
 @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markProcessed(message: ProcessedGmailMessage): Long
 @Query("UPDATE processed_gmail_messages SET extractionVersion = :version, processedAt = :at WHERE messageId = :messageId AND orderId = :orderId AND extractionVersion < :version") suspend fun upgradeProcessedVersion(messageId: String, orderId: Long, version: Int, at: Long): Int
 @Query("SELECT * FROM order_change_history WHERE orderId = :orderId AND field = :field ORDER BY appliedAt DESC, id DESC LIMIT 1") suspend fun latestChangeForField(orderId: Long, field: String): OrderChangeHistory?
 @Query("SELECT * FROM order_change_history WHERE orderId = :orderId ORDER BY id") suspend fun changesForOrder(orderId: Long): List<OrderChangeHistory>
 @Query("SELECT * FROM processed_gmail_messages ORDER BY id") suspend fun allProcessed(): List<ProcessedGmailMessage>
 @Query("SELECT * FROM processed_gmail_messages WHERE messageId = :messageId") suspend fun markersForMessage(messageId: String): List<ProcessedGmailMessage>
 @Query("UPDATE order_items SET orderId = :to WHERE orderId = :from") suspend fun moveItems(from: Long, to: Long)
 @Query("UPDATE shipments SET orderId = :to WHERE orderId = :from") suspend fun moveShipments(from: Long, to: Long)
 @Query("UPDATE shipment_tracking_numbers SET shipmentId = :to WHERE shipmentId = :from") suspend fun moveTrackingNumbers(from: Long, to: Long)
 @Query("UPDATE shipment_tracking_events SET shipmentId = :to WHERE shipmentId = :from") suspend fun moveEvents(from: Long, to: Long)
 @Query("UPDATE order_change_history SET orderId = :to WHERE orderId = :from") suspend fun moveHistory(from: Long, to: Long)
 @Query("UPDATE OR IGNORE processed_gmail_messages SET orderId = :to WHERE orderId = :from") suspend fun moveMarkers(from: Long, to: Long)
 @Query("DELETE FROM shipments WHERE id = :id") suspend fun deleteShipment(id: Long)
 @Query("DELETE FROM order_items WHERE id = :id") suspend fun deleteItem(id: Long)
 @Insert suspend fun logChange(change: OrderChangeHistory): Long
 @Query("SELECT * FROM order_change_history WHERE orderId = :orderId ORDER BY appliedAt DESC, id DESC") fun observeChanges(orderId: Long): Flow<List<OrderChangeHistory>>
 @Query("SELECT * FROM order_change_history WHERE orderId = :orderId ORDER BY appliedAt DESC, id DESC LIMIT 1") suspend fun latestChange(orderId: Long): OrderChangeHistory?
 @Query("SELECT COUNT(*) FROM orders WHERE archived = 0") suspend fun activeCount(): Int
 @Query("SELECT COUNT(*) FROM orders WHERE archived = 1") suspend fun archivedCount(): Int
 @Query("SELECT COUNT(*) FROM shipments") suspend fun shipmentCount(): Int
 @Query("SELECT COUNT(*) FROM shipment_tracking_numbers") suspend fun trackingCount(): Int
 @Query("SELECT marketplace, orderNumber FROM orders") suspend fun allOrderKeys(): List<OrderKey>
 @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markDeleted(marker: DeletedOrderMarker): Long
 @Query("SELECT COUNT(*) FROM deleted_order_markers WHERE marketplace = :marketplace COLLATE NOCASE AND orderNumber = :number COLLATE NOCASE") suspend fun wasDeleted(marketplace: String, number: String): Int
 @Query("DELETE FROM orders") suspend fun deleteAllOrders()
 @Query("DELETE FROM shipments") suspend fun deleteAllShipments()
 @Query("DELETE FROM packages") suspend fun deleteLegacyPackages()
 @Query("DELETE FROM processed_gmail_messages") suspend fun clearProcessedMessages()
 @Query("DELETE FROM order_change_history") suspend fun clearChangeHistory()
 // Shipments cascade-delete with their order, so an archived order expires only once every shipment is itself archived and past the cutoff.
 @Query("SELECT marketplace, orderNumber FROM orders WHERE archived = 1 AND archivedAt IS NOT NULL AND archivedAt <= :cutoff AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.orderId = orders.id AND NOT (s.archived = 1 AND s.statusUpdatedAt <= :cutoff))") suspend fun expiredArchivedKeys(cutoff: Long): List<OrderKey>
 @Query("DELETE FROM orders WHERE archived = 1 AND archivedAt IS NOT NULL AND archivedAt <= :cutoff AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.orderId = orders.id AND NOT (s.archived = 1 AND s.statusUpdatedAt <= :cutoff))") suspend fun deleteExpiredArchives(cutoff: Long): Int
 @Query("SELECT orderId FROM order_change_history") suspend fun historyOrderIds(): List<Long>
 @Query("SELECT shipmentId FROM shipment_tracking_events") suspend fun eventShipmentIds(): List<Long>
 @Query("DELETE FROM shipments WHERE orderId IS NULL AND archived = 1 AND statusUpdatedAt <= :cutoff") suspend fun deleteExpiredStandalone(cutoff: Long): Int

}

@Dao interface PackageDao {
 @Query("SELECT * FROM packages WHERE id = :id") suspend fun get(id: Long): TrackedPackage?
 @Query("SELECT * FROM packages ORDER BY createdAt DESC") fun observeAll(): Flow<List<TrackedPackage>>
 @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(pkg: TrackedPackage): Long
 @Update suspend fun update(pkg: TrackedPackage)
 @Query("UPDATE packages SET archived = :archived WHERE id = :id") suspend fun archive(id: Long, archived: Boolean)
 @Query("DELETE FROM packages WHERE id = :id") suspend fun delete(id: Long)
}

internal val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
 override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE shipments ADD COLUMN carrier TEXT NOT NULL DEFAULT ''")
  db.execSQL("UPDATE shipments SET carrier = COALESCE((SELECT carrier FROM shipment_tracking_numbers WHERE shipmentId = shipments.id ORDER BY id LIMIT 1), '')")
  // Earlier Gmail scans stored a number-less shipment's carrier only in the title ("eBay \u00b7 USPS"); keep that fact as real data.
  listOf("USPS", "UPS", "FedEx", "DHL", "DHL eCommerce", "OnTrac", "Cainiao").forEach { name ->
   db.execSQL("UPDATE shipments SET carrier = ? WHERE carrier = '' AND title LIKE ?", arrayOf<Any>(name, "% \u00b7 $name"))
  }
  db.execSQL("ALTER TABLE shipment_tracking_events ADD COLUMN sourceMessageId TEXT NOT NULL DEFAULT ''")
 }
}

internal val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
 override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE shipments ADD COLUMN shippedAt INTEGER")
 }
}

internal val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
 override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE shipments ADD COLUMN estimatedDeliveryEndAt INTEGER")
  db.execSQL("ALTER TABLE shipments ADD COLUMN estimatedDeliverySourceMessageId TEXT NOT NULL DEFAULT ''")
  db.execSQL("ALTER TABLE shipments ADD COLUMN estimatedDeliverySourceAt INTEGER")
 }
}

@Database(entities = [TrackedPackage::class, TrackingEvent::class, TrackingIdentifier::class, OrderRecord::class, OrderItemRecord::class, ShipmentRecord::class, ShipmentTrackingNumberRecord::class, ShipmentTrackingEventRecord::class, CustomMarketplaceRecord::class, ProcessedGmailMessage::class, OrderChangeHistory::class, DeletedOrderMarker::class], version = 13, exportSchema = false)
abstract class PackageDatabase : RoomDatabase() {
 abstract fun dao(): PackageDao
 abstract fun orderDao(): OrderDao
 companion object {
  @Volatile private var instance: PackageDatabase? = null
  fun get(context: Context): PackageDatabase = instance ?: synchronized(this) {
   instance ?: Room.databaseBuilder(context.applicationContext, PackageDatabase::class.java, "packages.db")
    .addMigrations(object : androidx.room.migration.Migration(1, 2) {
     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE packages ADD COLUMN marketplace TEXT NOT NULL DEFAULT 'Other'")
      db.execSQL("ALTER TABLE packages ADD COLUMN orderId TEXT NOT NULL DEFAULT ''")
      db.execSQL("ALTER TABLE packages ADD COLUMN lastMileTrackingNumber TEXT NOT NULL DEFAULT ''")
      db.execSQL("ALTER TABLE packages ADD COLUMN lastMileCarrier TEXT NOT NULL DEFAULT ''")
      db.execSQL("ALTER TABLE packages ADD COLUMN statusUpdatedAt INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE packages ADD COLUMN deliveredAt INTEGER")
      db.execSQL("CREATE TABLE IF NOT EXISTS tracking_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageId INTEGER NOT NULL, description TEXT NOT NULL, carrier TEXT NOT NULL, eventAt INTEGER NOT NULL, FOREIGN KEY(packageId) REFERENCES packages(id) ON DELETE CASCADE)")
      db.execSQL("CREATE INDEX IF NOT EXISTS index_tracking_events_packageId ON tracking_events(packageId)")
      db.execSQL("CREATE TABLE IF NOT EXISTS tracking_identifiers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageId INTEGER NOT NULL, number TEXT NOT NULL, carrier TEXT NOT NULL, role TEXT NOT NULL, FOREIGN KEY(packageId) REFERENCES packages(id) ON DELETE CASCADE)")
      db.execSQL("CREATE INDEX IF NOT EXISTS index_tracking_identifiers_packageId ON tracking_identifiers(packageId)")
     }
    }, object : androidx.room.migration.Migration(2, 3) {
     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE packages ADD COLUMN orderUrl TEXT NOT NULL DEFAULT ''")
      db.execSQL("ALTER TABLE packages ADD COLUMN trackingUrl TEXT NOT NULL DEFAULT ''")
      db.execSQL("ALTER TABLE packages ADD COLUMN orderStatus TEXT NOT NULL DEFAULT 'Order placed'")
     }
    }, object : androidx.room.migration.Migration(3, 4) {
     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
      db.execSQL("DROP INDEX IF EXISTS index_packages_carrier_trackingNumber")
      db.execSQL("CREATE INDEX IF NOT EXISTS index_packages_carrier_trackingNumber ON packages(carrier, trackingNumber)")
     }
    }, object : androidx.room.migration.Migration(4, 5) {
     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
      db.execSQL("CREATE TABLE IF NOT EXISTS `orders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `marketplace` TEXT NOT NULL, `orderNumber` TEXT NOT NULL, `name` TEXT NOT NULL, `status` TEXT NOT NULL, `placedAt` INTEGER, `orderUrl` TEXT NOT NULL, `notes` TEXT NOT NULL, `archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `statusUpdatedAt` INTEGER NOT NULL)")
      db.execSQL("CREATE INDEX IF NOT EXISTS `index_orders_marketplace_orderNumber` ON `orders` (`marketplace`, `orderNumber`)")
      db.execSQL("CREATE TABLE IF NOT EXISTS `order_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL, `name` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `priceMinor` INTEGER, `currency` TEXT NOT NULL, FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
      db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_items_orderId` ON `order_items` (`orderId`)")
      db.execSQL("CREATE TABLE IF NOT EXISTS `shipments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL, `status` TEXT NOT NULL, `estimatedDeliveryAt` INTEGER, `deliveredAt` INTEGER, `statusUpdatedAt` INTEGER NOT NULL, `statusSource` TEXT NOT NULL, FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
      db.execSQL("CREATE INDEX IF NOT EXISTS `index_shipments_orderId` ON `shipments` (`orderId`)")
      db.execSQL("CREATE TABLE IF NOT EXISTS `shipment_tracking_numbers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `shipmentId` INTEGER NOT NULL, `number` TEXT NOT NULL, `carrier` TEXT NOT NULL, `role` TEXT NOT NULL, `trackingUrl` TEXT NOT NULL, FOREIGN KEY(`shipmentId`) REFERENCES `shipments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
      db.execSQL("CREATE INDEX IF NOT EXISTS `index_shipment_tracking_numbers_shipmentId` ON `shipment_tracking_numbers` (`shipmentId`)")
      db.execSQL("CREATE TABLE IF NOT EXISTS `shipment_tracking_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `shipmentId` INTEGER NOT NULL, `trackingNumberId` INTEGER, `description` TEXT NOT NULL, `location` TEXT NOT NULL, `carrier` TEXT NOT NULL, `status` TEXT NOT NULL, `source` TEXT NOT NULL, `eventAt` INTEGER NOT NULL, FOREIGN KEY(`shipmentId`) REFERENCES `shipments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
      db.execSQL("CREATE INDEX IF NOT EXISTS `index_shipment_tracking_events_shipmentId` ON `shipment_tracking_events` (`shipmentId`)")
      db.execSQL("CREATE TABLE IF NOT EXISTS `custom_marketplaces` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `website` TEXT NOT NULL)")
      db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_marketplaces_name` ON `custom_marketplaces` (`name`)")
     }
    }, object : androidx.room.migration.Migration(5, 6) {
     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
      db.execSQL("CREATE TABLE IF NOT EXISTS shipments_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, orderId INTEGER, status TEXT NOT NULL, estimatedDeliveryAt INTEGER, deliveredAt INTEGER, statusUpdatedAt INTEGER NOT NULL, statusSource TEXT NOT NULL, FOREIGN KEY(orderId) REFERENCES orders(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
      db.execSQL("INSERT INTO shipments_new SELECT id, orderId, status, estimatedDeliveryAt, deliveredAt, statusUpdatedAt, statusSource FROM shipments")
      db.execSQL("DROP TABLE shipments")
      db.execSQL("ALTER TABLE shipments_new RENAME TO shipments")
      db.execSQL("CREATE INDEX IF NOT EXISTS index_shipments_orderId ON shipments(orderId)")
     }
    }, object : androidx.room.migration.Migration(6, 7) {
     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE shipments ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
     }
    }, object : androidx.room.migration.Migration(7, 8) {
     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE shipments ADD COLUMN title TEXT NOT NULL DEFAULT ''")
     }
    }, object : androidx.room.migration.Migration(8, 9) {
     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE orders ADD COLUMN lastSyncedAt INTEGER")
     }
    }, object : androidx.room.migration.Migration(9, 10) {
     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE orders ADD COLUMN archivedAt INTEGER")
      db.execSQL("CREATE TABLE IF NOT EXISTS processed_gmail_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, messageId TEXT NOT NULL, orderId INTEGER NOT NULL, processedAt INTEGER NOT NULL, extractionVersion INTEGER NOT NULL, FOREIGN KEY(orderId) REFERENCES orders(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
      db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_processed_gmail_messages_messageId_orderId ON processed_gmail_messages(messageId, orderId)")
      db.execSQL("CREATE INDEX IF NOT EXISTS index_processed_gmail_messages_orderId ON processed_gmail_messages(orderId)")
      db.execSQL("CREATE TABLE IF NOT EXISTS order_change_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, orderId INTEGER NOT NULL, field TEXT NOT NULL, oldValue TEXT NOT NULL, newValue TEXT NOT NULL, sourceMessageId TEXT NOT NULL, sourceDate TEXT NOT NULL, appliedAt INTEGER NOT NULL, FOREIGN KEY(orderId) REFERENCES orders(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
      db.execSQL("CREATE INDEX IF NOT EXISTS index_order_change_history_orderId ON order_change_history(orderId)")
      db.execSQL("CREATE TABLE IF NOT EXISTS deleted_order_markers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, marketplace TEXT NOT NULL, orderNumber TEXT NOT NULL, deletedAt INTEGER NOT NULL)")
      db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_deleted_order_markers_marketplace_orderNumber ON deleted_order_markers(marketplace, orderNumber)")
     }
    }, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13).build().also { instance = it }
  }
 }
}
