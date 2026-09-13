// Toolchain pinned to versions already in the local Gradle cache (same as WakiUsage) so
// a release build never depends on resolving a new AGP or Kotlin at ship time.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
