plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

// Lane F (design 3.2) — the composition root. It sits OUTSIDE the three rings and is the only
// module allowed to compose both halves of the Engine: the application half (app-services) and
// the index half (worker-services / worker-core). Stage A item A6 binds the ports here; until
// then the module carries only EngineRoot, its binding site.
//
// Edge direction, and why `implementation` rather than `api` for the composed halves: at A6
// `ui` gains a compile edge to this module. If worker-services were `api`, that edge would put
// `io.justsearch.indexerworker.{server,services,loop}..` on `ui`'s compile classpath
// transitively — exactly what ArchUnit rule 6b (item A2) forbids. Keeping the halves
// `implementation` means the root can reach them and nothing above the root can.
//
// `app-api` and `core` are `api`: they hold the port contracts (IndexingService, SearchPort)
// that EngineRoot's own signatures will carry outward at A6.
dependencies {
  // Port contracts — part of this module's outward surface.
  api(project(":modules:app-api"))
  api(project(":modules:core"))

  // The two halves the root composes, plus the substrates the root needs to build them.
  // Deliberately non-transitive (see the note above).
  implementation(project(":modules:app-services"))
  implementation(project(":modules:worker-services"))
  implementation(project(":modules:worker-core"))
  implementation(project(":modules:configuration"))
  implementation(project(":modules:telemetry"))
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
  }
}
