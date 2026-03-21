package com.routeme.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WeekPlanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlan(entity: SavedWeekPlanEntity)

    @Query("SELECT * FROM saved_week_plan WHERE id = 0")
    suspend fun loadPlan(): SavedWeekPlanEntity?

    @Query("DELETE FROM saved_week_plan")
    suspend fun deletePlan()
}
