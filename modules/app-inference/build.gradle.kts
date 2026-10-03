plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  api(project(":modules:core"))
  testImplementation(testFixtures(project(":modules:core")))
  // External module dependencies
  implementation(project(":modules:gpu-bridge"))
  api(project(":modules:app-api"))  // Exposes API types in public interface
  implementation(project(":modules:configuration"))
  // Tempdoc 518 Appendix F W2.2 — publish inference generation to span processor.
  implementation(project(":modules:telemetry"))
  // Tempdoc 518 Appendix F W4.1 — shared ObservableNotifier substrate.
  implementation(project(":modules:core-contracts"))

  // JSON parsing for llama-server communication (api: exposed in public signatures)
  api(libs.jackson.databind)

  // Concurrency annotations (@ThreadSafe, @GuardedBy)
  api(libs.jcip.annotations)
  // Logging (internal use only)
  implementation(libs.slf4j.api)
  implementation(libs.opentelemetry.api)
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(testFixtures(project(":modules:configuration")))
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)

        // Mockito for unit tests
        implementation(libs.mockito.core)
        implementation(libs.mockito.junit.jupiter)

        // ArchUnit for module-boundary enforcement (tempdoc 518 P4)
        implementation(libs.archunit.junit5)
      }
      targets {
        all {
          testTask.configure {
            jvmArgs(
              // Enable ByteBuddy experimental mode for JDK 25 support (until ByteBuddy 1.17.5+)
              "-Dnet.bytebuddy.experimental=true"
            )
          }
        }
      }
    }
  }
}
