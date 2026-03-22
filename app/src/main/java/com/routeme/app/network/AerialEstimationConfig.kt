package com.routeme.app.network

object AerialEstimationConfig {
    // Parcel sources – tried in order until one returns a result.
    // Each source has its own URL, outFields, and field-name mappings.
    data class ParcelSourceConfig(
        val name: String,
        val url: String,
        val outFields: String,
        val parcelIdField: String,
        val addressField: String,
        val assessedAcresField: String,
        val gisAcresField: String
    )

    val PARCEL_SOURCES: List<ParcelSourceConfig> = listOf(
        // 1) Kalamazoo County (covers most townships)
        ParcelSourceConfig(
            name = "kzoo_county",
            url = "https://services3.arcgis.com/RGltUbqpl4h9E3Qh/arcgis/rest/services/Parcels_2025/FeatureServer/11",
            outFields = "PELNUMBER,PCOMBINED,Acres,gis_acres,GOVUNIT",
            parcelIdField = "PELNUMBER",
            addressField = "PCOMBINED",
            assessedAcresField = "Acres",
            gisAcresField = "gis_acres"
        ),
        // 2) Kalamazoo City (city parcels only, SR 2253 native)
        ParcelSourceConfig(
            name = "kzoo_city",
            url = "https://gis.kalamazoocity.org/hosting/rest/services/General_Items/Parcel_Map/MapServer/3",
            outFields = "dbo.Assessing_Join_View.pnum,dbo.Assessing_Join_View.propstreetcombined,dbo.Assessing_Join_View.totalacres",
            parcelIdField = "dbo.Assessing_Join_View.pnum",
            addressField = "dbo.Assessing_Join_View.propstreetcombined",
            assessedAcresField = "dbo.Assessing_Join_View.totalacres",
            gisAcresField = ""
        ),
        // 3) Barry County (minimal fields: Tparcel, Acres)
        ParcelSourceConfig(
            name = "barry_county",
            url = "https://services.arcgis.com/i0SsdDzLI3mLGmPR/arcgis/rest/services/Parcels_OpenData_View/FeatureServer/14",
            outFields = "Tparcel,Acres",
            parcelIdField = "Tparcel",
            addressField = "",
            assessedAcresField = "Acres",
            gisAcresField = ""
        ),
        // 4) Allegan County (Gun Plain Twp, Plainwell, etc.)
        ParcelSourceConfig(
            name = "allegan_county",
            url = "https://gis.allegancounty.org/server/rest/services/Hosted/ParcelViewer_ParcelLayer_August2024/FeatureServer/0",
            outFields = "mapping_id,acres,propstreet,ownername1",
            parcelIdField = "mapping_id",
            addressField = "propstreet",
            assessedAcresField = "acres",
            gisAcresField = ""
        )
    )

    // Imagery service
    const val WORLD_IMAGERY_EXPORT =
        "https://services.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/export"
    const val WORLD_IMAGERY_METADATA_LAYER =
        "https://services.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/10"
    const val IMAGE_SIZE = 512
    const val IMAGERY_SPATIAL_REF = 3857  // Web Mercator
    const val MIN_BBOX_SPAN_METERS = 250.0  // ArcGIS needs enough bbox for 512px tile
    const val MAX_BBOX_SPAN_METERS = 400.0  // cap large parcels to keep sub-meter resolution

    // Classification thresholds (HSV)
    val TURF_HUE_RANGE = 60f..160f
    const val TURF_SAT_MIN = 0.15f
    const val TURF_VAL_MIN = 0.20f
    val TREE_HUE_RANGE = 60f..160f
    const val TREE_SAT_MIN = 0.15f
    const val TREE_VARIANCE_MIN = 0.015f
    const val TREE_HUE_VARIANCE_MIN = 120f  // hue variance (degrees²) — mixed species in woods
    const val TREE_HUE_BRIGHTNESS_FLOOR = 0.004f // min brightness variance to pair with hue diversity
    const val HUE_VARIANCE_KERNEL_SIZE = 11  // larger kernel to capture species diversity
    const val HUE_VARIANCE_MIN_NEIGHBORS = 4  // need enough green neighbors for meaningful variance
    const val BUILDING_SAT_MAX = 0.15f
    const val HARDSCAPE_SAT_MAX = 0.10f
    const val TEXTURE_KERNEL_SIZE = 5
    const val DARK_SHADOW_VAL_MAX = 0.10f  // too dark to classify

    // Sun/shade thresholds
    const val FULL_SUN_TREE_RATIO = 0.25
    const val PARTIAL_SHADE_TREE_RATIO = 0.60

    // Confidence thresholds
    const val IMAGERY_AGE_LOW_CONFIDENCE_YEARS = 3
    const val LOW_TURF_RATIO = 0.05
    const val HIGH_TURF_RATIO = 0.50

    // Large-parcel yard cap: when the parcel polygon covers an entire farm,
    // restrict classification to a residential-sized window around the house.
    const val LARGE_PARCEL_YARD_CAP_ACRES = 5.0   // parcels above this get synthetic yard mask
    const val LARGE_PARCEL_YARD_SIDE_METERS = 100.0 // side of the synthetic yard square

    // Large-lot crop/field guard
    const val LARGE_LOT_TURF_RATIO = 0.80
    const val LARGE_LOT_ACRES_THRESHOLD = 2.0

    // Batch processing
    const val BATCH_THROTTLE_MS = 100L
    const val HTTP_CONNECT_TIMEOUT_MS = 10_000
    const val HTTP_READ_TIMEOUT_MS = 10_000
}
