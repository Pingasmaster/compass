package com.compass.app.data.preferences

/**
 * Persisted answers to the compat-flavor "upgrade to future" first-open
 * prompt. Empty / missing means the prompt has not been shown yet.
 */
object FutureUpgradeChoices {
    const val UNSET = ""
    const val NO = "no"
    const val DEFER = "defer"
    const val ACCEPTED = "accepted"
}
