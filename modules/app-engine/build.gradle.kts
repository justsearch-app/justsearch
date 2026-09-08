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
  // Item A6: KnowledgeServer (the index half's own composition) lives here. Rule 6b explicitly
  // allows io.justsearch.app.engine.. -> io.justsearch.indexerworker..; `implementation` keeps it
  // off ui's compile classpath, which is what stops the allowance leaking upward.
  implementation(project(":modules:indexer-worker"))
  implementation(project(":modules:worker-core"))
  implementation(project(":modules:configuration"))
  implementation(project(":modules:telemetry"))
  // Item B2: ShutdownRequest parses the supervisor request file. Declared explicitly rather than
  // leaned on transitively — the composed halves are `implementation`, so their Jackson edge is
  // not on this module's compile classpath by construction.
  implementation(libs.jackson.databind)
  implementation(libs.jackson.core)
}

// Lane F stage B item B1. EngineExitTest reads modules/ui's HeadlessApp.java as TEXT, to pin that
// every System.exit on the boot path names an EngineExit constant rather than a bare integer. The
// dependency edge runs ui -> app-engine, so nothing in this module's test classpath changes when
// HeadlessApp does: without this declaration the task stays UP-TO-DATE and replays its last green
// result. Verified by observing exactly that — the falsification run reported BUILD SUCCESSFUL in
// 542ms against a deliberately reverted call site. A guard that cannot notice the change it exists
// to notice is the defect it was written to prevent.
// Lane F stage B item B7. EngineSupervisionPolicyTest reads governance/supervision-contract.v1.json
// and holds its `engine` row to EngineSupervisionPolicy and EngineExit. The register is outside
// this module, so without the declaration the task stays UP-TO-DATE when only the register moves —
// which is precisely the edit the drift check exists to catch. Same reasoning as the HeadlessApp
// input above, applied to the other file this module's tests read as data rather than as classpath.
tasks.named<ProcessResources>("processResources") {
  from(rootProject.file("governance/retained-state.v1.json")) {
    into("engine")
  }
}

tasks.named<Test>("test") {
  inputs.files(
    rootProject.file("scripts/dev/dev-runner.cjs"),
    rootProject.file("scripts/dev/test-dev-runner-head-java-opts.mjs"),
    rootProject.file("modules/shell/src-tauri/src/lib.rs"),
  ).withPropertyName("resourcePolicyLaunchers").withPathSensitivity(PathSensitivity.RELATIVE)
  inputs
    .file(rootProject.file("modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java"))
    .withPropertyName("headlessAppExitSites")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs
    .file(rootProject.file("governance/supervision-contract.v1.json"))
    .withPropertyName("supervisionContractRegister")
    .withPathSensitivity(PathSensitivity.RELATIVE)
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

        // Lane F stage A item A12 — the format-capability matrix moved here from the retired
        // chaos tier, and it is deterministic-by-fixture: the rows and their expected end states
        // come from worker-services' test fixtures rather than from a corpus checked in beside the
        // test, and the field names it asserts on come from the schema module.
        implementation(testFixtures(project(":modules:worker-services")))
        implementation(project(":modules:indexing"))
        implementation("org.junit.jupiter:junit-jupiter-params:5.14.3")

        // Item A4's ForegroundLoadGateTest pins the gate's nine foreground operations against
        // ForegroundLoadInterceptor.foregroundMethods(), the live producer until item A9 deletes
        // the interceptor — two producers of one gauge may not drift while both exist.
        //
        // The dependency itself is no longer test-only: item A6 made `indexer-worker` a
        // main-source `implementation` edge (EngineRoot composes KnowledgeServer), and
        // `testImplementation` extends `implementation`, so the test configuration inherits it.
        // A9 deletes the drift-pin test, not the edge.
      }
    }
  }
}
