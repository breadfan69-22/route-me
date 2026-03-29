package com.routeme.app.model

import com.routeme.app.SavedDestination
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

const val WEEKLY_PLANNER_SUPPLY_HOUSE_DESTINATION_ID = "__weekly_planner_supply_house__"

fun PlannedDay.toRouteItems(): List<RouteItem> {
    val items = mutableListOf<RouteItem>()
    clients.forEachIndexed { index, plannedClient ->
        items += RouteItem.ClientStop(plannedClient)
        if (supplyStopAfterIndex == index) {
            items += RouteItem.SupplyHouseStop()
        }
    }
    return items
}

fun RouteItem.toSavedDestinationOrNull(): SavedDestination? = when (this) {
    is RouteItem.ClientStop -> {
        val lat = planned.client.latitude
        val lng = planned.client.longitude
        if (lat == null || lng == null) {
            null
        } else {
            SavedDestination(
                id = planned.client.id,
                name = planned.client.name,
                address = planned.client.address,
                lat = lat,
                lng = lng
            )
        }
    }

    is RouteItem.SupplyHouseStop -> SavedDestination(
        id = WEEKLY_PLANNER_SUPPLY_HOUSE_DESTINATION_ID,
        name = name,
        address = address,
        lat = lat,
        lng = lng
    )
}
