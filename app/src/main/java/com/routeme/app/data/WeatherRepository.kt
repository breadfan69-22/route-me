package com.routeme.app.data

import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.data.db.DailyWeatherEntity
import com.routeme.app.data.db.ForecastDao
import com.routeme.app.data.db.ForecastDayEntity
import com.routeme.app.data.db.WeatherDao
import com.routeme.app.model.DailyWeather
import com.routeme.app.model.ForecastDay
import com.routeme.app.network.NwsWeatherService
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cache-first weather data. Checks Room first; fetches from NWS if missing.
 *
 * For today's date the cache is refreshed if older than 3 hours so the forecast
 * stays reasonably current while the user is out on a route.
 */
class WeatherRepository(
    private val weatherDao: WeatherDao,
    private val forecastDao: ForecastDao
) {

    companion object {
        private const val STALE_THRESHOLD_MS = 3 * 60 * 60 * 1000L // 3 hours
        private const val FORECAST_STALE_THRESHOLD_MS = 6 * 60 * 60 * 1000L // 6 hours
    }

    /**
     * Get weather for a day. Uses the shop location as default coordinates.
     * Returns null if data is unavailable (no network, NWS outage, etc.).
     */
    suspend fun getWeatherForDay(
        dateMillis: Long,
        lat: Double = SHOP_LAT,
        lng: Double = SHOP_LNG
    ): DailyWeather? = withContext(Dispatchers.IO) {
        val cached = weatherDao.getWeatherForDate(dateMillis)
        if (cached != null && !isStale(cached, dateMillis)) {
            return@withContext cached.toDomain()
        }

        val fetched = NwsWeatherService.fetchDailyWeather(lat, lng, dateMillis)
            ?: return@withContext cached?.toDomain() // return stale cache if fetch fails

        val entity = DailyWeatherEntity(
            dateMillis = dateMillis,
            highTempF = fetched.highTempF,
            lowTempF = fetched.lowTempF,
            windSpeedMph = fetched.windSpeedMph,
            windGustMph = fetched.windGustMph,
            windDirection = fetched.windDirection,
            precipitationInches = fetched.precipitationInches,
            description = fetched.description,
            fetchedAtMillis = System.currentTimeMillis()
        )
        weatherDao.upsert(entity)
        fetched
    }

    /** Today's entry should refresh if older than [STALE_THRESHOLD_MS]. Past days are always fresh. */
    private fun isStale(entity: DailyWeatherEntity, requestedDateMillis: Long): Boolean {
        val todayStart = (System.currentTimeMillis() / 86_400_000L) * 86_400_000L
        if (requestedDateMillis < todayStart) return false
        return System.currentTimeMillis() - entity.fetchedAtMillis > STALE_THRESHOLD_MS
    }

    private fun DailyWeatherEntity.toDomain() = DailyWeather(
        dateMillis = dateMillis,
        highTempF = highTempF,
        lowTempF = lowTempF,
        windSpeedMph = windSpeedMph,
        windGustMph = windGustMph,
        windDirection = windDirection,
        precipitationInches = precipitationInches,
        description = description
    )

    /**
     * Fetch the current observation nearest to [lat],[lng].
     * Lightweight call for tagging a stop event with conditions at that moment.
     */
    suspend fun fetchCurrentSnapshot(
        lat: Double,
        lng: Double
    ): NwsWeatherService.WeatherSnapshot? = withContext(Dispatchers.IO) {
        runCatching { NwsWeatherService.fetchLatestObservation(lat, lng) }.getOrNull()
    }

    suspend fun getRecentPrecip(
        lookbackDays: Int,
        lat: Double = SHOP_LAT,
        lng: Double = SHOP_LNG
    ): Double? = withContext(Dispatchers.IO) {
        if (lookbackDays < 0) return@withContext null

        var sum = 0.0
        var hasAny = false
        var dayStart = startOfTodayMillis()

        for (offset in 0..lookbackDays) {
            val weather = getWeatherForDay(dayStart, lat, lng)
            val precip = weather?.precipitationInches
            if (precip != null) {
                hasAny = true
                sum += precip
            }
            dayStart -= 86_400_000L
        }

        if (hasAny) sum else null
    }

    private fun startOfTodayMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    suspend fun getForecastDays(
        dayCount: Int = 7,
        lat: Double = SHOP_LAT,
        lng: Double = SHOP_LNG
    ): List<ForecastDay> = withContext(Dispatchers.IO) {
        if (dayCount <= 0) return@withContext emptyList()

        val cached = forecastDao.getAll().sortedBy { it.dateMillis }
        if (cached.size >= dayCount && !isForecastStale(cached.firstOrNull())) {
            return@withContext cached.take(dayCount).map { it.toDomain() }
        }

        val fetched = NwsWeatherService.fetchDailyForecast(lat, lng, dayCount)
        if (fetched.isNotEmpty()) {
            val fetchedAt = System.currentTimeMillis()
            val entities = fetched.map {
                ForecastDayEntity(
                    dateMillis = it.dateMillis,
                    highTempF = it.highTempF,
                    lowTempF = it.lowTempF,
                    windSpeedMph = it.windSpeedMph,
                    windGustMph = it.windGustMph,
                    precipProbabilityPct = it.precipProbabilityPct,
                    shortForecast = it.shortForecast,
                    detailedForecast = it.detailedForecast,
                    fetchedAtMillis = fetchedAt
                )
            }
            forecastDao.clearAll()
            forecastDao.upsertAll(entities)
            return@withContext fetched
        }

        cached.take(dayCount).map { it.toDomain() }
    }

    private fun isForecastStale(entity: ForecastDayEntity?): Boolean {
        if (entity == null) return true
        return System.currentTimeMillis() - entity.fetchedAtMillis > FORECAST_STALE_THRESHOLD_MS
    }

    private fun ForecastDayEntity.toDomain() = ForecastDay(
        dateMillis = dateMillis,
        highTempF = highTempF,
        lowTempF = lowTempF,
        windSpeedMph = windSpeedMph,
        windGustMph = windGustMph,
        precipProbabilityPct = precipProbabilityPct,
        shortForecast = shortForecast,
        detailedForecast = detailedForecast
    )
}
