package com.routeme.app.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import org.koin.core.component.KoinComponent

class RouteMeSession : Session(), KoinComponent {

    override fun onCreateScreen(intent: Intent): Screen =
        HomeScreen(carContext)
}
