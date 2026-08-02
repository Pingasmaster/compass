package com.compass.app.testing

/**
 * Fast on-device checks that gate everything else in CI.
 * Filter: -Pandroid.testInstrumentationRunnerArguments.annotation=
 *   com.compass.app.testing.SmokeTest
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SmokeTest
