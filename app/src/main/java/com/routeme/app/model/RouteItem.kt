package com.routeme.app.model

import com.routeme.app.util.AppConfig

/**
 * Represents an item in a planned route — either a client stop or a supply house stop.
 */
sealed interface RouteItem {

    data class ClientStop(val planned: PlannedClient) : RouteItem

    data class SupplyHouseStop(
        val name: String = AppConfig.SupplyHouse.NAME,
        val address: String = AppConfig.SupplyHouse.ADDRESS,
        val lat: Double = AppConfig.SupplyHouse.LAT,
        val lng: Double = AppConfig.SupplyHouse.LNG
    ) : RouteItem
}
