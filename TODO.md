# TODO

## JDK 27 bytecode (blocked)

CI guest already runs Temurin 27 EA. Kotlin 2.4.20-RC3 still maxes at JvmTarget.JVM_26.
When JetBrains ships JvmTarget.JVM_27, bump JavaVersion.VERSION_27 and JvmTarget.JVM_27 in every module and remove this section.
