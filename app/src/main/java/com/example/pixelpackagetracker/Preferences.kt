package com.example.pixelpackagetracker

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "tracker_settings")
// Separate file: writing a check time must not re-emit `settings`, whose collector re-applies the background schedule.
private val Context.shipmentCheckStore by preferencesDataStore(name = "shipment_last_checked")

data class TrackerSettings(
 val country: String = "United States", val postalCode: String = "", val language: String = "English",
 val darkTheme: Boolean = true, val notifications: Boolean = false, val autoArchive: Boolean = true,
 val sortRecent: Boolean = true, val missingLast: Boolean = false, val deliveryFirst: Boolean = true,
 val showCarrierLabels: Boolean = false, val showCarrierLogos: Boolean = true,
 val archiveDays: String = "14", val archivedRetentionDays: String = "30", val refreshHours: String = "8", val appearance: String = "Dark",
 val notifyShipped: Boolean = true, val notifyOut: Boolean = true, val notifyDelivered: Boolean = true,
 val notifyException: Boolean = true, val notifyEvery: Boolean = false, val notifyOrder: Boolean = true
)

class SettingsRepository(private val context: Context) {
 private val keys = mapOf(
  "country" to stringPreferencesKey("country"), "postalCode" to stringPreferencesKey("postalCode"), "language" to stringPreferencesKey("language"), "archiveDays" to stringPreferencesKey("archiveDays"), "archivedRetentionDays" to stringPreferencesKey("archivedRetentionDays"), "refreshHours" to stringPreferencesKey("refreshHours"), "appearance" to stringPreferencesKey("appearance"))
 private val boolKeys = mapOf(
  "darkTheme" to booleanPreferencesKey("darkTheme"), "notifications" to booleanPreferencesKey("notifications"),
  "autoArchive" to booleanPreferencesKey("autoArchive"), "sortRecent" to booleanPreferencesKey("sortRecent"),
  "missingLast" to booleanPreferencesKey("missingLast"), "deliveryFirst" to booleanPreferencesKey("deliveryFirst"),
  "showCarrierLabels" to booleanPreferencesKey("showCarrierLabels"), "showCarrierLogos" to booleanPreferencesKey("showCarrierLogos"),
  "notifyShipped" to booleanPreferencesKey("notifyShipped"), "notifyOut" to booleanPreferencesKey("notifyOut"), "notifyDelivered" to booleanPreferencesKey("notifyDelivered"), "notifyException" to booleanPreferencesKey("notifyException"), "notifyEvery" to booleanPreferencesKey("notifyEvery"), "notifyOrder" to booleanPreferencesKey("notifyOrder"))
 val settings: Flow<TrackerSettings> = context.settingsStore.data.map { p ->
  TrackerSettings(
   p[keys.getValue("country")] ?: "United States", p[keys.getValue("postalCode")] ?: "",
   p[keys.getValue("language")] ?: "English", p[boolKeys.getValue("darkTheme")] ?: true,
   p[boolKeys.getValue("notifications")] ?: false, p[boolKeys.getValue("autoArchive")] ?: true,
   p[boolKeys.getValue("sortRecent")] ?: true, p[boolKeys.getValue("missingLast")] ?: false,
   p[boolKeys.getValue("deliveryFirst")] ?: true, p[boolKeys.getValue("showCarrierLabels")] ?: false,
   p[boolKeys.getValue("showCarrierLogos")] ?: true,
   p[keys.getValue("archiveDays")] ?: "14", p[keys.getValue("archivedRetentionDays")] ?: "30", p[keys.getValue("refreshHours")] ?: "8", p[keys.getValue("appearance")] ?: "Dark",
   p[boolKeys.getValue("notifyShipped")] ?: true, p[boolKeys.getValue("notifyOut")] ?: true, p[boolKeys.getValue("notifyDelivered")] ?: true, p[boolKeys.getValue("notifyException")] ?: true, p[boolKeys.getValue("notifyEvery")] ?: false, p[boolKeys.getValue("notifyOrder")] ?: true)
 }
 suspend fun setText(name: String, value: String) { context.settingsStore.edit { it[keys.getValue(name)] = value } }
 suspend fun setBoolean(name: String, value: Boolean) { context.settingsStore.edit { it[boolKeys.getValue(name)] = value } }

 // Shipment id -> time of the last manual Gmail check (Shipments tab).
 val shipmentChecks: Flow<Map<Long, Long>> = context.shipmentCheckStore.data.map { p ->
  p.asMap().mapNotNull { (key, value) -> (value as? Long)?.let { key.name.removePrefix("s").toLongOrNull()?.to(it) } }.toMap()
 }
 internal suspend fun markShipmentsChecked(ids: Collection<Long>, at: Long) {
  if(ids.isEmpty()) return
  context.shipmentCheckStore.edit { prefs -> ids.forEach { prefs[longPreferencesKey("s$it")] = at } }
 }

 // Retailer names the user confirmed as one retailer (seeded aliases live in AliasBook).
 private val aliasKey = stringPreferencesKey("retailerAliases")
 internal suspend fun aliasGroups(): List<Set<String>> = AliasBook.decode(context.settingsStore.data.first()[aliasKey].orEmpty())
 internal suspend fun addAliasGroup(names: Set<String>) { context.settingsStore.edit { it[aliasKey] = AliasBook.encode(AliasBook.decode(it[aliasKey].orEmpty()) + listOf(names)) } }
 internal suspend fun clearAliases() { context.settingsStore.edit { it.remove(aliasKey) } }

 private val dismissedGmailKey = stringSetPreferencesKey("dismissedGmailMessageIds")
 internal suspend fun dismissedGmailMessageIds(): Set<String> = context.settingsStore.data.first()[dismissedGmailKey].orEmpty()
 internal suspend fun dismissGmailMessage(messageId: String) {
  if(messageId.isBlank()) return
  context.settingsStore.edit { prefs -> prefs[dismissedGmailKey] = prefs[dismissedGmailKey].orEmpty() + messageId }
 }
 internal suspend fun setDismissedGmailMessageIds(messageIds: Set<String>) {
  context.settingsStore.edit { prefs -> if(messageIds.isEmpty()) prefs.remove(dismissedGmailKey) else prefs[dismissedGmailKey] = messageIds }
 }
}
