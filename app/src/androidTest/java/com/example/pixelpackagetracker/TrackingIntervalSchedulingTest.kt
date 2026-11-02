package com.example.pixelpackagetracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

// Verifies the real WorkManager schedule; the periodic job is shared with the app, so the user's saved interval is restored afterwards.
@RunWith(AndroidJUnit4::class)
class TrackingIntervalSchedulingTest {
 private val context = InstrumentationRegistry.getInstrumentation().targetContext

 private fun scheduled(): WorkInfo =
  WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackgroundSync.WORK).get().single { !it.state.isFinished }

 @Test fun changingTheIntervalReplacesTheSinglePeriodicJobAndKeepsItsConstraints() = runBlocking<Unit> {
  val saved = SettingsRepository(context).settings.first().refreshHours
  try {
   BackgroundSync.schedule(context, "3")
   assertEquals(TimeUnit.HOURS.toMillis(3), scheduled().periodicityInfo!!.repeatIntervalMillis)
   BackgroundSync.schedule(context, "12")
   val work = scheduled()
   assertEquals(TimeUnit.HOURS.toMillis(12), work.periodicityInfo!!.repeatIntervalMillis)
   assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
   assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackgroundSync.WORK).get().count { !it.state.isFinished })
   BackgroundSync.schedule(context, "not a number")
   assertEquals(TimeUnit.HOURS.toMillis(8), scheduled().periodicityInfo!!.repeatIntervalMillis)
   // Read once: a worker can finish between two reads (RUNNING then ENQUEUED).
   val state = scheduled().state
   assertTrue("Unexpected periodic work state: $state", state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING)
  } finally { BackgroundSync.schedule(context, saved) }
 }
}
