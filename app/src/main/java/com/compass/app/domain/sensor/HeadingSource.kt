package com.compass.app.domain.sensor

import com.compass.app.domain.model.CompassReading
import com.compass.app.domain.model.GeoFix
import kotlinx.coroutines.flow.Flow

/** Abstraction over the device compass pipeline (sensor events to [CompassReading]). */
interface HeadingSource {
    /** Must be answerable synchronously at construction: the ViewModel reads it eagerly for its stateIn initial value. */
    val hasSensor: Boolean

    /** Cold flow; in production, collection starts/stops hardware listener registration. */
    val readings: Flow<CompassReading>

    fun setTrueNorthEnabled(enabled: Boolean)

    fun updateLocation(fix: GeoFix)
}
