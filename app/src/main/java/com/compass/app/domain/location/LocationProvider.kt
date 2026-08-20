package com.compass.app.domain.location

import com.compass.app.domain.model.GeoFix

/** Result of asking the platform for a single declination fix. */
enum class LocationRequestOutcome {
    /** A last-known and/or one-shot update was requested. [LocationProvider.requestFix]'s callback may already have run. */
    REQUESTED,

    /** Runtime coarse-location permission is missing. */
    MISSING_PERMISSION,

    /** The network location provider is off (user disabled location). */
    PROVIDER_DISABLED,

    /** LocationManager is missing from this device. */
    UNAVAILABLE,
}

/**
 * Why true-north correction cannot be applied right now. Null means either
 * true north is off or a usable fix has been delivered.
 */
enum class LocationIssue {
    WAITING,
    PROVIDER_DISABLED,
    UNAVAILABLE,
}

interface LocationProvider {
    /**
     * Requests one declination-quality fix (last-known if fresh enough, otherwise
     * a single network update). Contract:
     * - does not keep a standing listener when a fresh cached fix is available
     * - may invoke [onFix] synchronously with a cached last-known fix
     * - repeated calls cancel any in-flight request and start over
     * - must be called from a Looper thread; production delivers callbacks on the
     *   caller's Looper (today: main, via viewModelScope = Main.immediate)
     */
    fun requestFix(onFix: (GeoFix) -> Unit): LocationRequestOutcome

    /** Idempotent; safe when never started. Cancels an in-flight one-shot. */
    fun stopUpdates()
}
