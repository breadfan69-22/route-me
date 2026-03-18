package com.routeme.app.domain

import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.SavedDestination
import com.routeme.app.data.PreferencesRepository

class DestinationQueueUseCase(
    private val preferencesRepository: PreferencesRepository,
    private val routingEngine: RoutingEngine
) {
    data class GeoPoint(
        val latitude: Double,
        val longitude: Double
    )

    data class QueueMutationResult(
        val destinationQueue: List<SavedDestination>,
        val activeDestinationIndex: Int,
        val statusMessage: String? = null
    )

    data class DestinationReachedResult(
        val destinationQueue: List<SavedDestination>,
        val activeDestinationIndex: Int,
        val snackbarMessage: String
    )

    fun loadSavedDestinations(): List<SavedDestination> {
        return preferencesRepository.savedDestinations
    }

    fun addSavedDestination(dest: SavedDestination): List<SavedDestination> {
        val updated = preferencesRepository.savedDestinations + dest
        preferencesRepository.savedDestinations = updated
        return updated
    }

    fun removeSavedDestination(id: String): List<SavedDestination> {
        val updated = preferencesRepository.savedDestinations.filter { it.id != id }
        preferencesRepository.savedDestinations = updated
        return updated
    }

    fun addToDestinationQueue(
        destinationQueue: List<SavedDestination>,
        activeDestinationIndex: Int,
        destination: SavedDestination
    ): QueueMutationResult {
        val updatedQueue = destinationQueue + destination
        persistActiveDestination(updatedQueue, activeDestinationIndex)
        return QueueMutationResult(
            destinationQueue = updatedQueue,
            activeDestinationIndex = activeDestinationIndex,
            statusMessage = "Added ${destination.name} to destinations"
        )
    }

    fun removeFromDestinationQueue(
        destinationQueue: List<SavedDestination>,
        activeDestinationIndex: Int,
        indexToRemove: Int
    ): QueueMutationResult {
        val updatedQueue = destinationQueue.toMutableList().apply { removeAt(indexToRemove) }
        val newIndex = when {
            updatedQueue.isEmpty() -> 0
            activeDestinationIndex >= updatedQueue.size -> updatedQueue.size - 1
            indexToRemove < activeDestinationIndex -> activeDestinationIndex - 1
            else -> activeDestinationIndex
        }
        persistActiveDestination(updatedQueue, newIndex)
        return QueueMutationResult(
            destinationQueue = updatedQueue,
            activeDestinationIndex = newIndex
        )
    }

    fun moveDestinationInQueue(
        destinationQueue: List<SavedDestination>,
        activeDestinationIndex: Int,
        fromIndex: Int,
        toIndex: Int
    ): QueueMutationResult {
        val updatedQueue = destinationQueue.toMutableList()
        val item = updatedQueue.removeAt(fromIndex)
        updatedQueue.add(toIndex, item)

        val newActiveIndex = when {
            fromIndex == activeDestinationIndex -> toIndex
            fromIndex < activeDestinationIndex && toIndex >= activeDestinationIndex -> activeDestinationIndex - 1
            fromIndex > activeDestinationIndex && toIndex <= activeDestinationIndex -> activeDestinationIndex + 1
            else -> activeDestinationIndex
        }

        persistActiveDestination(updatedQueue, newActiveIndex)
        return QueueMutationResult(
            destinationQueue = updatedQueue,
            activeDestinationIndex = newActiveIndex
        )
    }

    fun replaceDestinationQueue(
        destinationQueue: List<SavedDestination>,
        activeDestinationIndex: Int
    ): QueueMutationResult {
        val newActiveIndex = when {
            destinationQueue.isEmpty() -> 0
            activeDestinationIndex < 0 -> 0
            activeDestinationIndex >= destinationQueue.size -> destinationQueue.lastIndex
            else -> activeDestinationIndex
        }

        persistActiveDestination(destinationQueue, newActiveIndex)
        return QueueMutationResult(
            destinationQueue = destinationQueue,
            activeDestinationIndex = newActiveIndex
        )
    }

    fun clearDestinationQueue(): QueueMutationResult {
        preferencesRepository.activeDestination = null
        return QueueMutationResult(
            destinationQueue = emptyList(),
            activeDestinationIndex = 0,
            statusMessage = "Destinations cleared"
        )
    }

    fun skipDestination(
        destinationQueue: List<SavedDestination>,
        activeDestinationIndex: Int
    ): QueueMutationResult {
        return advanceDestinationQueue(destinationQueue, activeDestinationIndex)
    }

    fun onDestinationReached(
        destinationQueue: List<SavedDestination>,
        activeDestinationIndex: Int,
        destinationName: String
    ): DestinationReachedResult {
        val remaining = destinationQueue.size - activeDestinationIndex - 1
        val snackbarMessage = if (remaining > 0) {
            val nextDestination = destinationQueue.getOrNull(activeDestinationIndex + 1)
            "Arrived at $destinationName — next: ${nextDestination?.name} ($remaining remaining)"
        } else {
            "Arrived at $destinationName — all destinations reached"
        }

        val advanced = advanceDestinationQueue(destinationQueue, activeDestinationIndex)
        return DestinationReachedResult(
            destinationQueue = advanced.destinationQueue,
            activeDestinationIndex = advanced.activeDestinationIndex,
            snackbarMessage = snackbarMessage
        )
    }

    fun optimizeDestinationQueue(
        destinationQueue: List<SavedDestination>,
        currentLocation: GeoPoint?
    ): QueueMutationResult? {
        if (destinationQueue.size <= 1) return null

        val startLat = currentLocation?.latitude ?: SHOP_LAT
        val startLng = currentLocation?.longitude ?: SHOP_LNG
        val optimized = routingEngine.optimizeDestinationOrder(destinationQueue, startLat, startLng)

        persistActiveDestination(optimized, 0)
        return QueueMutationResult(
            destinationQueue = optimized,
            activeDestinationIndex = 0,
            statusMessage = "Optimized ${optimized.size} destinations"
        )
    }

    private fun advanceDestinationQueue(
        destinationQueue: List<SavedDestination>,
        activeDestinationIndex: Int
    ): QueueMutationResult {
        val nextIndex = activeDestinationIndex + 1
        return if (nextIndex >= destinationQueue.size) {
            preferencesRepository.activeDestination = null
            QueueMutationResult(
                destinationQueue = emptyList(),
                activeDestinationIndex = 0
            )
        } else {
            persistActiveDestination(destinationQueue, nextIndex)
            QueueMutationResult(
                destinationQueue = destinationQueue,
                activeDestinationIndex = nextIndex
            )
        }
    }

    private fun persistActiveDestination(
        destinationQueue: List<SavedDestination>,
        activeDestinationIndex: Int
    ) {
        preferencesRepository.activeDestination = destinationQueue.getOrNull(activeDestinationIndex)
    }
}
