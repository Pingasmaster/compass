package com.compass.app.domain.location

import com.compass.app.domain.model.GeoFix

interface LocationProvider {
    /**
     * Starts fix delivery. Contract (mirrors previous in-ViewModel behavior exactly):
     * - no-ops when the location service, coarse permission, or network provider is unavailable
     *   (and in those cases does NOT tear down an existing registration);
     * - may invoke [onFix] synchronously with a cached last-known fix before periodic delivery begins;
     * - repeated calls restart delivery, never stack listeners;
     * - must be called from a Looper thread; production delivers callbacks on the caller's
     *   Looper (today: main, via viewModelScope = Main.immediate).
     */
    fun requestUpdates(onFix: (GeoFix) -> Unit)

    /** Idempotent; safe when never started. */
    fun stopUpdates()
}
