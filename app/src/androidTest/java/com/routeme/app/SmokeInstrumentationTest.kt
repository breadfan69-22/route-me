package com.routeme.app

import android.location.Location
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeInstrumentationTest {

    @Test
    fun appContext_usesRouteMePackage() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(appContext.packageName.startsWith("com.routeme.app"))
    }

    @Test
    fun mainActivity_launchesSuccessfully() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertFalse(activity.isFinishing)
        }
        scenario.close()
    }

    @Test
    fun mainActivity_displaysTopToolbar() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val toolbar = activity.findViewById<View>(R.id.topToolbar)
            assertNotNull(toolbar)
            assertTrue(toolbar.visibility == View.VISIBLE)
        }
        scenario.close()
    }

    @Test
    fun clusterCompletionDialog_confirmPersistsWeatherFields() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(appContext)
        val seedStamp = System.currentTimeMillis()
        val firstClientId = "it-weather-$seedStamp-a"
        val secondClientId = "it-weather-$seedStamp-b"

        runBlocking {
            db.clientDao().insertClients(
                listOf(
                    createClientEntity(
                        id = firstClientId,
                        name = "IT Weather A",
                        latitude = 39.9526,
                        longitude = -75.1652
                    ),
                    createClientEntity(
                        id = secondClientId,
                        name = "IT Weather B",
                        latitude = 39.9531,
                        longitude = -75.1648
                    )
                )
            )
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            dismissStartTrackingPromptIfVisible(scenario)

            val clients = waitForClients(
                scenario = scenario,
                requiredIds = setOf(firstClientId, secondClientId)
            )

            val now = System.currentTimeMillis()
            val expectedWeatherByClientId = mapOf(
                firstClientId to Triple(72, 9, "Sunny"),
                secondClientId to Triple(68, 13, "Cloudy")
            )
            val location = Location("instrumentation").apply {
                latitude = 39.9529
                longitude = -75.1650
                time = now
            }
            val members = clients.mapIndexed { index, client ->
                val weather = expectedWeatherByClientId.getValue(client.id)
                ClusterMember(
                    client = client,
                    timeOnSiteMillis = (6 + index) * 60_000L,
                    arrivedAtMillis = now - (10 + index) * 60_000L,
                    location = location,
                    weatherTempF = weather.first,
                    weatherWindMph = weather.second,
                    weatherDesc = weather.third
                )
            }

            scenario.onActivity { activity ->
                val showClusterDialog = MainActivity::class.java
                    .getDeclaredMethod("showClusterCompletionDialog", List::class.java)
                showClusterDialog.isAccessible = true
                showClusterDialog.invoke(activity, members)
            }

            if (!clickDialogButton(scenario, android.R.id.button1)) {
                throw AssertionError("Unable to click cluster confirm dialog button")
            }

            val persistedRows = waitForClientStopRows(
                db = db,
                clientIds = setOf(firstClientId, secondClientId)
            )

            val latestByClientId = persistedRows
                .groupBy { it.clientId }
                .mapValues { (_, rows) -> rows.maxBy { row -> row.endedAtMillis } }

            expectedWeatherByClientId.forEach { (clientId, expectedWeather) ->
                val row = latestByClientId[clientId]
                    ?: throw AssertionError("No persisted stop row for $clientId")
                assertEquals(ClientStopStatus.DONE.name, row.status)
                assertEquals(expectedWeather.first, row.weatherTempF)
                assertEquals(expectedWeather.second, row.weatherWindMph)
                assertEquals(expectedWeather.third, row.weatherDesc)
            }
        } finally {
            scenario.close()
            runBlocking {
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM client_stop_events WHERE clientId IN (?, ?)",
                    arrayOf(firstClientId, secondClientId)
                )
                db.clientDao().deleteClient(firstClientId)
                db.clientDao().deleteClient(secondClientId)
            }
        }
    }

    private fun createClientEntity(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double
    ): ClientEntity {
        return ClientEntity(
            id = id,
            name = name,
            address = "123 Test Ln",
            zone = "Test",
            notes = "",
            subscribedSteps = "1",
            hasGrub = false,
            mowDayOfWeek = 1,
            lawnSizeSqFt = 2000,
            sunShade = "SUN",
            terrain = "FLAT",
            windExposure = "OPEN",
            cuSpringPending = false,
            cuFallPending = false,
            latitude = latitude,
            longitude = longitude
        )
    }

    private fun dismissStartTrackingPromptIfVisible(scenario: ActivityScenario<MainActivity>) {
        clickDialogButton(scenario, android.R.id.button2, timeoutMs = 1_500L)
    }

    private fun clickDialogButton(
        scenario: ActivityScenario<MainActivity>,
        buttonId: Int,
        timeoutMs: Long = 5_000L
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var clicked = false
            scenario.onActivity {
                clicked = clickDialogButtonAcrossWindows(buttonId)
            }
            if (clicked) return true
            Thread.sleep(100)
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun clickDialogButtonAcrossWindows(buttonId: Int): Boolean {
        val windowManagerGlobalClass = Class.forName("android.view.WindowManagerGlobal")
        val windowManager = windowManagerGlobalClass.getMethod("getInstance").invoke(null)
        val viewsField = windowManagerGlobalClass.getDeclaredField("mViews").apply {
            isAccessible = true
        }
        val roots = viewsField.get(windowManager) as? List<View> ?: return false
        roots.forEach { root ->
            val button = root.findViewById<View>(buttonId)
            if (button?.isShown == true && button.isEnabled) {
                button.performClick()
                return true
            }
        }
        return false
    }

    private fun waitForClients(
        scenario: ActivityScenario<MainActivity>,
        requiredIds: Set<String>,
        timeoutMs: Long = 15_000L
    ): List<Client> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var matched: List<Client> = emptyList()
            scenario.onActivity { activity ->
                matched = readActivityClients(activity)
                    .filter { client -> requiredIds.contains(client.id) }
            }
            if (matched.map { it.id }.toSet() == requiredIds) {
                return matched.sortedBy { it.id }
            }
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for seeded clients in MainActivity state")
    }

    private fun waitForClientStopRows(
        db: AppDatabase,
        clientIds: Set<String>,
        timeoutMs: Long = 15_000L
    ): List<ClientStopRow> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rows = runBlocking {
                db.clientDao().getClientStopsForDateRange(0L, System.currentTimeMillis() + 60_000L)
            }.filter { row -> row.clientId in clientIds }

            if (rows.map { it.clientId }.toSet().containsAll(clientIds)) {
                return rows
            }
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for client_stop_events rows for seeded clients")
    }

    @Suppress("UNCHECKED_CAST")
    private fun readActivityClients(activity: MainActivity): List<Client> {
        val clientsField = MainActivity::class.java.getDeclaredField("clients")
        clientsField.isAccessible = true
        return (clientsField.get(activity) as MutableList<Client>).toList()
    }
}
