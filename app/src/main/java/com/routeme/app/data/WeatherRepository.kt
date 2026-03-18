package com.routeme.app.data

import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.data.db.DailyWeatherEntity
import com.routeme.app.data.db.WeatherDao
import com.routeme.app.model.DailyWeather
import com.routeme.app.network.NwsWeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cache-first weather data. Checks Room first; fetches from NWS if missing.
 *
 * For today's date the cache is refreshed if older than 3 hours so the forecast
 * stays reasonably current while the user is out on a route.
 */
class WeatherRepository(private val weatherDao: WeatherDao) {

    companion object {
        private const val STALE_THRESHOLD_MS = 3 * 60 * 60 * 1000L // 3 hours
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
}
