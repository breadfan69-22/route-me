package com.routeme.app.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.routeme.app.ServiceType
import com.routeme.app.data.PreferencesRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class StepSelectScreen(carContext: CarContext) : Screen(carContext), KoinComponent {

    private val prefs: PreferencesRepository by inject()

    override fun onGetTemplate(): Template {
        val currentSteps = prefs.selectedSteps
            .split(",")
            .mapNotNull { runCatching { ServiceType.valueOf(it.trim()) }.getOrNull() }
            .toSet()

        val listBuilder = ItemList.Builder()
        ServiceType.entries.forEach { step ->
            val checked = step in currentSteps
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("${if (checked) "✓ " else ""}${step.label}")
                    .setOnClickListener {
                        val updated = if (checked) currentSteps - step else currentSteps + step
                        prefs.selectedSteps = updated.joinToString(",") { it.name }
                        invalidate()
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Select Steps")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
