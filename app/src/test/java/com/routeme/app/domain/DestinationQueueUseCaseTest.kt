package com.routeme.app.domain

import com.routeme.app.SavedDestination
import com.routeme.app.data.PreferencesRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DestinationQueueUseCaseTest {

    private lateinit var prefs: PreferencesRepository
    private lateinit var routingEngine: RoutingEngine

    private var savedDestinationsStore: List<SavedDestination> = emptyList()
    private var activeDestinationStore: SavedDestination? = null

    @Before
    fun setup() {
        prefs = mockk(relaxed = true)
        routingEngine = mockk(relaxed = true)

        every { prefs.savedDestinations } answers { savedDestinationsStore }
        every { prefs.savedDestinations = any() } answers {
            savedDestinationsStore = firstArg()
            Unit
        }

        every { prefs.activeDestination } answers { activeDestinationStore }
        every { prefs.activeDestination = any() } answers {
            activeDestinationStore = firstArg()
            Unit
        }
    }

    @Test
    fun `saved destination operations persist through repository`() {
        val useCase = DestinationQueueUseCase(prefs, routingEngine)
        val first = dest("1", "Shop")
        val second = dest("2", "Warehouse")

        val afterFirst = useCase.addSavedDestination(first)
        assertEquals(listOf(first), afterFirst)

        val afterSecond = useCase.addSavedDestination(second)
        assertEquals(listOf(first, second), afterSecond)
        assertEquals(2, useCase.loadSavedDestinations().size)

        val afterRemove = useCase.removeSavedDestination("1")
        assertEquals(listOf(second), afterRemove)
        assertEquals(listOf(second), useCase.loadSavedDestinations())
    }

    @Test
    fun `addToDestinationQueue keeps index and updates active destination`() {
        val useCase = DestinationQueueUseCase(prefs, routingEngine)
        val queue = listOf(dest("1", "A"))
        val newDest = dest("2", "B")

        val result = useCase.addToDestinationQueue(queue, activeDestinationIndex = 0, destination = newDest)

        assertEquals(2, result.destinationQueue.size)
        assertEquals(0, result.activeDestinationIndex)
        assertEquals("Added B to destinations", result.statusMessage)
        assertEquals("A", activeDestinationStore?.name)
    }

    @Test
    fun `removeFromDestinationQueue updates active index when deleting earlier item`() {
        val useCase = DestinationQueueUseCase(prefs, routingEngine)
        val a = dest("1", "A")
        val b = dest("2", "B")
        val c = dest("3", "C")

        val result = useCase.removeFromDestinationQueue(
            destinationQueue = listOf(a, b, c),
            activeDestinationIndex = 2,
            indexToRemove = 0
        )

        assertEquals(listOf(b, c), result.destinationQueue)
        assertEquals(1, result.activeDestinationIndex)
        assertEquals(c, activeDestinationStore)
    }

    @Test
    fun `moveDestinationInQueue tracks active index movement`() {
        val useCase = DestinationQueueUseCase(prefs, routingEngine)
        val a = dest("1", "A")
        val b = dest("2", "B")
        val c = dest("3", "C")

        val result = useCase.moveDestinationInQueue(
            destinationQueue = listOf(a, b, c),
            activeDestinationIndex = 1,
            fromIndex = 1,
            toIndex = 0
        )

        assertEquals(listOf(b, a, c), result.destinationQueue)
        assertEquals(0, result.activeDestinationIndex)
        assertEquals(b, activeDestinationStore)
    }

    @Test
    fun `clear and skip destination queue update active destination`() {
        val useCase = DestinationQueueUseCase(prefs, routingEngine)
        val a = dest("1", "A")
        val b = dest("2", "B")

        val skipResult = useCase.skipDestination(
            destinationQueue = listOf(a, b),
            activeDestinationIndex = 0
        )
        assertEquals(listOf(a, b), skipResult.destinationQueue)
        assertEquals(1, skipResult.activeDestinationIndex)
        assertEquals(b, activeDestinationStore)

        val clearResult = useCase.clearDestinationQueue()
        assertTrue(clearResult.destinationQueue.isEmpty())
        assertEquals(0, clearResult.activeDestinationIndex)
        assertEquals("Destinations cleared", clearResult.statusMessage)
        assertNull(activeDestinationStore)
    }

    @Test
    fun `onDestinationReached emits snackbar and advances queue`() {
        val useCase = DestinationQueueUseCase(prefs, routingEngine)
        val a = dest("1", "A")
        val b = dest("2", "B")

        val result = useCase.onDestinationReached(
            destinationQueue = listOf(a, b),
            activeDestinationIndex = 0,
            destinationName = "A"
        )

        assertTrue(result.snackbarMessage.contains("next: B (1 remaining)"))
        assertEquals(1, result.activeDestinationIndex)
        assertEquals(b, activeDestinationStore)
    }

    @Test
    fun `optimizeDestinationQueue delegates to routing engine and resets index`() {
        val useCase = DestinationQueueUseCase(prefs, routingEngine)
        val a = dest("1", "A", 42.0, -85.0)
        val b = dest("2", "B", 42.2, -85.2)

        every {
            routingEngine.optimizeDestinationOrder(listOf(a, b), 42.1, -85.1)
        } returns listOf(b, a)

        val result = useCase.optimizeDestinationQueue(
            destinationQueue = listOf(a, b),
            currentLocation = DestinationQueueUseCase.GeoPoint(42.1, -85.1)
        )

        assertTrue(result != null)
        assertEquals(listOf(b, a), result!!.destinationQueue)
        assertEquals(0, result.activeDestinationIndex)
        assertEquals("Optimized 2 destinations", result.statusMessage)
        assertEquals(b, activeDestinationStore)
    }

    private fun dest(id: String, name: String, lat: Double = 42.0, lng: Double = -85.0): SavedDestination {
        return SavedDestination(
            id = id,
            name = name,
            address = "$name Address",
            lat = lat,
            lng = lng
        )
    }
}
