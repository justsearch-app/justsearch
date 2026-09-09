// Lane F stage A item A13 — this module is a LIBRARY, not an application. The `application`
// plugin, `mainClass`, `applicationName`, `applicationDefaultJvmArgs`, the `distributions` block,
// the assemble->installDist wiring, the installDist build-stamp and the `runWorkerStandalone`
// JavaExec task all went with `IndexerWorker.main`: there is no second process to spawn and no
// second distribution to ship. The index half now reaches the runtime as jars on the Engine's own
// `modules/ui/build/install/ui/lib`, via `ui -> app-engine -> indexer-worker`.
plugins {
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  testImplementation(testFixtures(project(":modules:adapters-lucene")))
  testImplementation(testFixtures(project(":modules:core")))
  implementation(project(":modules:app-api"))
  implementation(project(":modules:worker-core"))
  implementation(project(":modules:worker-services"))
  implementation(project(":modules:configuration"))
  implementation(project(":modules:ipc-common"))
  // InferenceCompositionRoot carries an @io.justsearch.contracts.BuildContract annotation, which
  // BootContractRunner.validateAll() (tempdoc 402 P3) reads at Engine boot from HeadlessApp.main.
  implementation(project(":modules:core-contracts"))
  implementation(project(":modules:ort-common"))
  runtimeOnly(libs.onnxruntime.gpu)
  implementation(project(":modules:adapters-lucene"))
  implementation(project(":modules:indexing"))
  implementation(project(":modules:telemetry"))

  // AI Bridge for CPU-only embeddings (llama.cpp)
  runtimeOnly(project(":modules:ai-backend"))
  // Reranker for RAG chunk reranking (Phase 5)
  implementation(project(":modules:reranker")) {
    // Worker-side SPLADE uses the GPU ORT runtime directly; keep reranker from
    // reintroducing the CPU ORT artifact on the same classpath.
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime")
  }
  // DJL tokenizer for NER inference (not exposed by reranker module)
  implementation(libs.djl.tokenizers)
  // DJL API for DefaultVocabulary (SPLADE output → token mapping)
  implementation(libs.djl.api)
  // Item A9 deleted this module's gRPC server and item A13 dropped the two gRPC dependencies that
  // outlived it; item A14 finished the job across the repo, so no module declares one now and the
  // note that used to name them here has been removed with them. Naming a retired coordinate in
  // prose is not free: `adr-0049-no-grpc-in-module-builds` is a grep-absent probe over this file,
  // and a comment counts.
  implementation(libs.jackson.databind)
  implementation(libs.slf4j.api)

  // SQLite for job queue persistence. Slice 445 promoted from runtimeOnly to
  // implementation because IndexingJobsChangeStream uses Xerial-specific
  // SQLiteConnection.addUpdateListener / addCommitListener APIs that aren't
  // standard JDBC. The runtime classpath dep is unchanged; only the
  // compile-classpath visibility was added.
  implementation(libs.sqlite.jdbc)

  // Lucene (runtime only — compile-time usage is in worker-services/adapters-lucene)
  runtimeOnly(libs.lucene.core)
  runtimeOnly(libs.lucene.analysis.common)

  // Apache Tika for rich document parsing (PDF, DOCX, etc.)
  runtimeOnly(libs.tika.core)
  // Tempdoc 632 — exclude junrar (UnRar License, field-of-use restricted, non-OSI). See worker-services.
  runtimeOnly(libs.tika.parsers.standard) {
    exclude(group = "com.github.junrar", module = "junrar")
  }

  runtimeOnly(libs.logback.classic)
  runtimeOnly(libs.opentelemetry.logback.mdc)
  // Route Log4j2 API calls (from transitive deps like Tika) to SLF4J -> Logback
  runtimeOnly(libs.log4j.to.slf4j)

  // IDE/JDT friendliness: ensure ArchUnit is on the conventional test classpath as well.
  // (The actual test suite wiring is also defined under `testing.suites` below.)
  implementation(libs.jackson.core)

  testImplementation(libs.archunit.junit5)
  testImplementation(libs.opentelemetry.api) // Direct OTel usage for trace propagation (test-only)
  testImplementation(libs.lucene.core)
  testImplementation(testFixtures(project(":modules:configuration")))
  testImplementation(testFixtures(project(":modules:ort-common"))) // §14.28 U1 helper
  // logback.classic is runtimeOnly above (main code stays decoupled from the Logback API).
  // Item A13 deleted src/main/resources/logback.xml — one JVM has one logging configuration and
  // the Engine's is modules/ui/src/main/resources/logback.xml — so the assertion that used to live
  // here (WorkerLogbackConfigurationTest) moved to modules/ui as EngineLogbackConfigurationTest.
  // src/test/resources/logback-test.xml keeps this module's own test output quiet in its place.
  testImplementation(libs.logback.classic)
}

configurations.configureEach {
  // Tempdoc 632 — junrar (UnRar, non-OSI) off EVERY config incl. testFixturesRuntimeClasspath, where the
  // per-declaration exclude on runtimeOnly(tika.parsers.standard) doesn't reach. Removes the product's only
  // non-OSI dependency from all classpaths for a clean Apache-2.0 posture.
  exclude(group = "com.github.junrar", module = "junrar")
  resolutionStrategy.eachDependency {
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-core") {
      useVersion("3.1.0")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-databind") {
      useVersion("3.1.0")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
      useVersion("2.21")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "tools.jackson.dataformat" && requested.name == "jackson-dataformat-yaml") {
      useVersion("3.1.0")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for worker classpaths")
    }
  }
}

tasks.jar {
  manifest {
    attributes(
      // No "Main-Class": item A13 deleted IndexerWorker.main. This jar is a library on the
      // Engine's classpath, not a runnable artifact.
      "Implementation-Title" to "JustSearch Knowledge Server",
      "Implementation-Version" to project.version
    )
  }
}

// Lane F stage A item A13 removed the `runWorkerStandalone` JavaExec task with the
// `IndexerWorker.main` it launched. There is no Worker to run without a Head: the Knowledge
// Server is composed in-process by `io.justsearch.app.engine.EngineRoot`, so the one way to run
// the index half is to run the Engine (`./gradlew :modules:ui:runHeadless`, or the dev-runner).

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        implementation(libs.archunit.junit5)
        implementation(libs.mockito.core)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
      targets {
        all {
          testTask.configure {
            jvmArgs(
              // Enable ByteBuddy experimental mode for JDK 25 support (until ByteBuddy 1.17.5+)
              "-Dnet.bytebuddy.experimental=true"
            )
            // Tempdoc 408 Tier 2: shard indexer-worker tests across 2 JVM forks.
            // Was the second-slowest single test task (~26s in profile). Tests are
            // fork-safe: ConfigStore.setGlobal usage is per-test with save/restore
            // (each fork is its own JVM, isolated), no shared file paths
            // (@TempDir per test), no port bindings.
            maxParallelForks = 2

            // Lane F stage A item A18. DevReloadManagerTriggerTest reads the dev MCP server's
            // SOURCE to check that the tool WRITING the hot-reload trigger and the Java constant
            // NAMING it still agree — a cross-language pair Gradle cannot see, because a .mjs file
            // is not on any compile or runtime classpath.
            //
            // Without this declaration the pin is worse than useless: renaming the literal in
            // server.mjs changes no declared input, so the test task stays UP-TO-DATE and replays
            // its last green result. Verified by observing exactly that — the falsification run
            // reported BUILD SUCCESSFUL in 596ms against a deliberately broken server.mjs, and only
            // failed once `cleanTest --no-build-cache` forced execution. A guard that cannot notice
            // the change it exists to notice is the defect it was written to prevent.
            inputs
              .file(rootProject.file("scripts/dev/justsearch-dev-mcp/server.mjs"))
              .withPropertyName("devMcpServerSource")
              .withPathSensitivity(PathSensitivity.RELATIVE)
          }
        }
      }
    }
  }
}
