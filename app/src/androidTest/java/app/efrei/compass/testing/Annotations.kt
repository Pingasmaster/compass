package app.efrei.compass.testing

/**
 * Fast on-device checks that gate everything else in CI.
 * Filter: -Pandroid.testInstrumentationRunnerArguments.annotation=
 *   app.efrei.compass.testing.SmokeTest
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SmokeTest
