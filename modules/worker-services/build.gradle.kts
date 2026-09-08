plugins {
  `java-library`
  `java-test-fixtures`
  id("jvm-test-suite")
  id("conventions.jvm-base")
  id("conventions.mutation")  // PIT scoped to registered seams (governance/logic-seams.v1.json) — tempdoc 555
}

dependencies {
  api(project(":modules:app-api"))
  api(project(":modules:worker-core"))
  api(project(":modules:adapters-lucene"))
  api(project(":modules:ipc-common"))
  api(project(":modules:indexing"))
  api(project(":modules:telemetry"))
  api(project(":modules:configuration"))
  api(project(":modules:ort-common"))
  api(project(":modules:reranker")) {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime")
  }
  // Tempdoc 518 Appendix F W4.3 — shared ObservableNotifier substrate.
  implementation(project(":modules:core-contracts"))
  // Tempdoc 560 §4.3 — the ONE shared contribution composer (same class the Head uses). Pure JDK
  // module (no Lucene), so the Worker reuses the four substrates instead of re-deriving them.
  implementation(project(":modules:extension-substrate"))

  implementation(libs.jackson.databind)
  implementation(libs.commonmark)
  // Tempdoc 847 §2.2 — GFM tables and task lists are structure in the rendered answer (marked
  // parses both), so citation segmentation must see them as structure too. Core commonmark reads a
  // table as one paragraph (fusing a whole table into one citation key) and leaves a literal `[x]`
  // at the head of a task item's key, which no rendered run can match.
  implementation(libs.commonmark.ext.gfm.tables)
  implementation(libs.commonmark.ext.task.list.items)
  api(libs.slf4j.api)
  api(libs.opentelemetry.api)
  implementation(libs.logstash.logback.encoder)

  // Lucene query parser for search orchestrator
  api(libs.lucene.core)
  implementation(libs.lucene.queryparser)
  implementation(libs.lucene.memory)

  // Tika for content extraction
  implementation(libs.tika.core)
  implementation("org.apache.pdfbox:pdfbox:3.0.6")
  // Tempdoc 632 — exclude junrar (UnRar License, field-of-use restricted, non-OSI). RAR content
  // extraction is dropped; Tika core still detects the RAR MIME from magic bytes (used only as a
  // classifier at IndexingDocumentOps.java). This removes the product's only non-OSI dependency.
  runtimeOnly(libs.tika.parsers.standard) {
    exclude(group = "com.github.junrar", module = "junrar")
  }

  // Tempdoc 418 Phase B — Worker-side file watcher (native OS events via Methvin).
  // Replaces the Head-side watcher in modules/app-indexing; Phase C cleanup deletes
  // the Head-side dependency once production has soaked.
  implementation(libs.directory.watcher)

  // testFixtures: TestDocumentBuilder uses indexing types
  testFixturesApi(project(":modules:indexing"))
  testFixturesApi(project(":modules:worker-core"))
  // Construct a valid owned PPTX-with-speaker-notes package, then rewrite it through the fixture
  // factory's deterministic ZIP serializer before capability assertions.
  testFixturesImplementation("org.apache.poi:poi-ooxml:5.4.1") {
    // The complete POI graph is already the Tika parser runtime. Keep this compile-time handle
    // non-transitive so adding the fixture recipe does not create a second dependency authority.
    isTransitive = false
  }
  testFixturesImplementation("org.apache.poi:poi:5.4.1") {
    isTransitive = false
  }
  // Lane F stage A item A12 closure: ChaosExtractionSandboxChild speaks the sandbox's own
  // length-prefixed JSON frame format. Jackson is `implementation` on the main source set, which
  // test fixtures do not inherit, so the fixture declares it for itself.
  testFixturesImplementation(libs.jackson.databind)

  testImplementation("io.opentelemetry:opentelemetry-sdk-common:1.60.1")
  testRuntimeOnly(libs.opentelemetry.sdk.testing)

  // Tempdoc 809 finding 3: BackfillSchedulerSlowBatchTest asserts the tempdoc-798 budget-trip WARN
  // is still emitted, which needs a real SLF4J binding to attach a ListAppender to. Same pair
  // app-services already uses for its log-assertion tests.
  testImplementation(libs.logback.classic)
  testImplementation(libs.logback.core)
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "com.fasterxml.jackson" && requested.name == "jackson-bom") {
      useVersion("2.18.6")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-core") {
      useVersion("2.18.6")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-databind") {
      useVersion("2.18.6")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
      useVersion("2.21")
      because("Jackson 3 requires jackson-annotations 2.21+ (JsonSerializeAs)")
    }
    if (requested.group == "com.fasterxml.jackson.dataformat" && requested.name == "jackson-dataformat-yaml") {
      useVersion("2.18.6")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for worker classpaths")
    }
  }
}

// Dev hot-reload: push bytecode + signal the Worker after successful recompilation.
// Chain: compile → HotSwapPush (updates bytecode via JDWP) → MMF signal (restarts services).
// Only fires when JUSTSEARCH_DEV_HOTRELOAD=true and compileJava actually runs (not UP-TO-DATE).
// Usage: JUSTSEARCH_DEV_HOTRELOAD=true ./gradlew -t :modules:worker-services:classes
tasks.named<JavaCompile>("compileJava") {
  // Capture paths at configuration time (configuration-cache safe — Provider values)
  val classesOutput = destinationDirectory
  doLast {
    if (System.getenv("JUSTSEARCH_DEV_HOTRELOAD") != "true") return@doLast

    val classesDir = classesOutput.get().asFile.absolutePath
    val debugPort = System.getenv("JUSTSEARCH_DEV_DEBUG_PORT") ?: "5005"

    // Derive project root from classes dir: .../modules/worker-services/build/classes/java/main
    val projectRoot = File(classesDir).resolve("../../../../../..").canonicalFile

    // Step 1: Push updated bytecode to the running Worker via JDWP (HotSwapPush.java).
    val hotSwapScript = File(projectRoot, "scripts/dev/HotSwapPush.java")
    if (hotSwapScript.exists()) {
      val result = ProcessBuilder(
        "java", "--add-modules", "jdk.jdi",
        hotSwapScript.absolutePath, debugPort, classesDir
      )
        .directory(projectRoot)
        .redirectErrorStream(true)
        .start()
      val output = result.inputStream.bufferedReader().readText().trim()
      val exitCode = result.waitFor()
      if (exitCode == 0) {
        if (output.isNotBlank()) logger.lifecycle("HotSwapPush: $output")
      } else {
        logger.warn("HotSwapPush failed (exit $exitCode): $output")
      }
    } else {
      logger.warn("HotSwapPush.java not found at ${hotSwapScript.absolutePath}, skipping bytecode push")
    }

    // Step 2: Ask the Engine to reconstruct its services.
    //
    // Lane F stage A item A14: this used to seek to byte 29 of the memory-mapped worker signal
    // file (MmfWorkerSignalLayoutV1.OFFSET_RELOAD_SIGNAL) and write a 1. Item A10 deleted that
    // region along with the rest of the memory-mapped bus, so the write went to a file nothing
    // maps and the reload silently did nothing. The trigger is a request FILE now --
    // InProcessWorkerSignalBus.RELOAD_REQUEST_FILENAME under <dataDir>/runtime/ -- whose EXISTENCE
    // is the signal and whose contents are ignored; the Engine's sentinel deletes it when it picks
    // it up (InProcessWorkerSignalBus#isReloadRequested).
    val dataDir = System.getenv("JUSTSEARCH_DATA_DIR")
      ?: (System.getenv("LOCALAPPDATA")?.let { "$it/JustSearch" })
    if (dataDir == null) {
      logger.warn("Cannot determine data dir (set JUSTSEARCH_DATA_DIR, or LOCALAPPDATA)")
      return@doLast
    }
    val runtimeDir = File(dataDir, "runtime")
    if (!runtimeDir.isDirectory) {
      logger.warn("Runtime dir not found: ${runtimeDir.absolutePath} (Engine not running?)")
      return@doLast
    }
    // Written to a sibling then renamed: the sentinel treats existence as the whole signal, so a
    // half-created file would be a half-true signal.
    val request = File(runtimeDir, "dev-reload.request")
    val staging = File(runtimeDir, "dev-reload.request.tmp")
    staging.writeText("")
    if (!staging.renameTo(request)) {
      staging.delete()
      logger.warn("Could not place ${request.absolutePath}")
      return@doLast
    }
    logger.lifecycle("Hot-reload: bytecode pushed + ${request.name} placed")
  }
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(project(":modules:ai-backend"))
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        implementation("org.junit.jupiter:junit-jupiter-params:5.14.3")
        implementation(libs.archunit.junit5)
        implementation(libs.mockito.core)
        implementation(libs.mockito.junit.jupiter)
        // Tempdoc 517 — OTel SDK testing for span-topology assertions
        // (SearchExecutorOtelTopologyTest). Mirrors the telemetry module's pattern.
        implementation(libs.opentelemetry.sdk)
        implementation(libs.opentelemetry.sdk.trace)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
        // Tempdoc 417 F8: TestMetricRegistry from telemetry's testFixtures.
        implementation(testFixtures(project(":modules:telemetry")))
        // Phase 3b: MetricSurfaceContractTest reflectively validates surfacedAt fieldNames
        // against API records in app-api.
        implementation(project(":modules:app-api"))
      }
      targets {
        all {
          testTask.configure {
            jvmArgs("-Dnet.bytebuddy.experimental=true")
            // Tempdoc 408 Tier 2: shard worker-services tests across 2 JVM forks.
            // worker-services:test was the slowest single-task in the profile
            // (27s, single JVM). Tests are fork-safe: no static singleton mutation,
            // no shared file paths (all use @TempDir), no port bindings, and the
            // System.setProperty("justsearch.config", ...) usage is per-test with
            // save/restore — each fork gets its own JVM properties.
            // Each fork uses ~384 MB heap (JvmBaseConventionsPlugin default), so
            // 2 forks = ~768 MB total per worker-services:test invocation.
            // Empirically: 1 fork → 27.2s; 2 forks → 21.9s; 3 forks → 26.95s
            // (3-fork JVM-startup overhead overwhelms the parallelism gain on this
            // workload distribution). 2 forks is the local optimum.
            maxParallelForks = 2
          }
        }
      }
    }
  }
}
