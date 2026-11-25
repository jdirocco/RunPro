# ADR 0002: Gradle build foundation

- Status: proposed (accepted when issue #1 is merged)
- Date: 2026-11-25

## Context

The nine modules in `docs/ARCHITECTURE.md` need a reproducible build and a local quality gate.
Issue #1 requires Kotlin 2.x, JDK 21, Compose for Wear, ktlint, detekt and the latest target SDK.
The latest stable Android major SDK is API 37 (Android 17).

## Decision

- Pin Gradle 9.6.1 with its distribution SHA-256 in the checked-in wrapper. Keep dependency and
  plugin versions in `gradle/libs.versions.toml`; use only Google Maven, Maven Central and the
  Gradle Plugin Portal. Do not use dynamic versions or developer-specific repositories.
- Use AGP 9.3.1, Kotlin/Compose compiler 2.4.10 and a JDK 21 toolchain. AGP supplies Kotlin for
  Android; apply the Kotlin JVM plugin only to core and tool modules. Compile and target API 37;
  both app placeholders have minSdk 30. The phone's minimum can be revisited when its features exist.
- Keep the seven JVM modules empty of production code until their own issues. Run a shared JVM
  build contract test in each module: Java 21 executes the tests and Android framework classes
  are absent. Module dependencies are added alongside the features that need them.
- Use Wear Compose Material 3 and Activity Compose for a single launcher screen. The watch manifest
  requires watch hardware and declares a standalone app. The mobile app has no activity yet.
- Apply ktlint 14.2.0 (engine 1.8.0) and detekt 2.0.0-alpha.6 to every leaf module. Root `check`
  also runs all module checks and ktlint on the root build scripts. Android lint treats warnings
  as errors. Allow Compose's conventional capitalised function names in both Kotlin analysers.
- Use Kotlin Test with JUnit Jupiter for JVM tests. Use JUnit 4, Robolectric and Compose UI testing
  for a local launcher test on API 30, the minimum supported watch SDK. No emulator is required.

## Consequences

- New checkouts need JDK 21 and Android SDK platform 37.0/build-tools 37.0.0. The wrapper downloads
  Gradle and the first build resolves dependencies. SDK locations and signing material stay local.
- Detekt 2 is an alpha build tool, chosen because its published compatibility table covers Gradle
  9.6.1, Kotlin 2.4.10 and AGP 9.3.1. It does not ship in the APK. Upgrades must keep this matrix
  aligned and pass the same gate; stable detekt 1.23.8 targets older build tooling.
- Robolectric verifies the placeholder UI locally. Installation and display on a Galaxy Watch
  5 Pro remain owner field validation in issue #6; a successful APK build is not field evidence.
- No recording, journal, BLE or export implementation exists yet, so R1–R8 are unchanged. Their
  feature-specific proof tests remain assigned to the later issues in the reliability plan.

## References

- [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk)
- [Detekt compatibility table](https://detekt.dev/docs/introduction/compatibility/)
