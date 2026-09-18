plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("com.chaquo.python") version "17.0.0" apply false
    // Plain JVM Kotlin, matching the app module's Kotlin version — used only
    // by :core, the Android-free module. No Android plugin, no Chaquopy: the
    // whole point of :core is that it builds without either, so it can ship
    // as ordinary Kotlin/JVM once wired into the app for the F-Droid port.
    kotlin("jvm") version "2.4.0" apply false
}
