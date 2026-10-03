plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

// Tempdoc 638 — whole-program closed-world dead-code gate.
//
// This module's ONLY purpose is to host an ArchUnit analysis whose classpath is the union of
// every production module, so cross-process callers (Head <-> Worker) are visible — the blind
// spot that makes the process-scoped UnreferencedCodeTest miss public/cross-module dead code.
// It is a dependency SINK (nothing depends on it -> no cycles) and produces no production code.
//
// The deps are deliberately on EVERY module that carries production io.justsearch classes, as
// `testImplementation` (the analysis runs as a test). app-api-tck is omitted (no src/main).
dependencies {
  val auditedModules =
      listOf(
          "adapters-lucene",
          "ai-backend",
          "api-contract-projection-java",
          "app-agent",
          "app-agent-api",
          "app-api",
          "app-config",
          "app-engine",
          "app-inference",
          "app-launcher",
          "app-observability",
          "app-services",
          "app-util",
          "benchmarks",
          "configuration",
          "core",
          "core-contracts",
          "extension-substrate",
          "gpu-bridge",
          "indexer-worker",
          "indexing",
          "infra-core",
          "ipc-common",
          "ort-common",
          "prompt-support",
          "reranker",
          "ssot-tools",
          "telemetry",
          "ui",
          "worker-core",
          "worker-services",
      )
  for (m in auditedModules) {
    testImplementation(project(":modules:$m"))
  }
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        implementation(libs.archunit.junit5)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
  }
}

// Whole-program import holds ~1,300 classes + their members in memory; give the test room.
tasks.named<Test>("test") {
  maxHeapSize = "2g"
  // Tempdoc 930 chunk F. WholeProgramDeadCodeTest used to write tmp/dead-code-jvm-report.json for
  // the `dead-code-jvm` kernel gate to ratchet; both the report and the gate are gone. The ratchet
  // is now ArchUnit's own FreezingArchRule and its committed violation store below, so the accepted
  // set lives next to the rule that produces it and is enforced by this test task rather than by a
  // separate CI step reading a filesystem side effect.
  //
  // ArchUnit resolves a relative store path against the JVM working directory, so pass the absolute
  // one (system properties prefixed `archunit.` override src/test/resources/archunit.properties).
  val archunitStore = layout.projectDirectory.dir("archunit_store")
  systemProperty("archunit.freeze.store.default.path", archunitStore.asFile.absolutePath)
  // The store is a checked-in ratchet OUTSIDE any source set — same reason the sysaccess allowlist
  // below is declared: without this, editing the accepted set leaves the task up to date and the
  // ratchet silently stops running.
  inputs
      .dir(archunitStore)
      .withPropertyName("deadCodeArchUnitStore")
      .withPathSensitivity(PathSensitivity.RELATIVE)

  // SystemAccessFunnelTest ratchets against a checked-in file OUTSIDE any source set. Without
  // declaring it as an input, Gradle considers the task up to date after the allowlist changes and
  // the ratchet silently stops running locally — measured while building it (a deliberately bogus
  // entry produced BUILD SUCCESSFUL in 750ms because nothing re-ran). Passing the resolved path as
  // a property additionally frees the test from having to guess the repo root from its working
  // directory.
  val sysaccessAllowlist =
      rootProject.layout.projectDirectory.file("gates/config-surface/sysaccess-allowlist.txt")
  inputs
      .file(sysaccessAllowlist)
      .withPropertyName("sysaccessAllowlist")
      .withPathSensitivity(PathSensitivity.RELATIVE)
  systemProperty("sysaccess.allowlistPath", sysaccessAllowlist.asFile.absolutePath)
}
