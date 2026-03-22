package com.routeme.app.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

class ServiceCompleteScreen(
    carContext: CarContext,
    private val clientName: String
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder("$clientName marked complete.")
            .setTitle("Done!")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Back to List")
                    .setOnClickListener { screenManager.popToRoot() }
                    .build()
            )
            .build()
}
