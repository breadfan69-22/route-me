package com.routeme.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_week_plan")
data class SavedWeekPlanEntity(
    @PrimaryKey val id: Int = 0,
    val planJson: String,
    val generatedAtMillis: Long
)
