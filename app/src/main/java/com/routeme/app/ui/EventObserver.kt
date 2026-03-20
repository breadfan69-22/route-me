package com.routeme.app.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.routeme.app.TrackingEvent
import com.routeme.app.TrackingEventBus
import kotlinx.coroutines.launch

class EventObserver(
    private val lifecycleOwner: LifecycleOwner,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val viewModel: MainViewModel,
    private val trackingEventBus: TrackingEventBus,
    private val onMainEvent: (MainEvent) -> Unit,
    private val onTrackingEvent: (TrackingEvent) -> Unit
) {
    fun start() {
        lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.events.collect { event ->
                        onMainEvent(event)
                    }
                }
                launch {
                    trackingEventBus.events.collect { event ->
                        onTrackingEvent(event)
                    }
                }
            }
        }
    }
}
