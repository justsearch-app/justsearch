import java.security.MessageDigest

plugins {
  application
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

application {
  mainClass.set("io.justsearch.indexerworker.IndexerWorker")
  applicationName = "indexer-worker"
  applicationDefaultJvmArgs = listOf("--sun-misc-unsafe-memory-access=warn")
}

dependencies {
  implementation(project(":modules:worker-core"))
  implementation(project(":modules:worker-services"))
  implementation(project(":modules:configuration"))
  implementation(project(":modules:ipc-common"))
  // Runtime-scope: IndexerWorker.main calls BootContractRunner.validateAll() (tempdoc 402 P3),
  // so core-contracts classes must be on the installed-distribution runtime classpath.
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
  // Lane F stage A item A9 deleted this module's gRPC server, so neither of these has a source
  // consumer left here (`git grep io.grpc -- modules/indexer-worker/src` is empty). They are kept
  // only until item A13 collapses the standalone distribution, which is what still packages them.
  implementation(libs.grpc.netty.shaded)
  implementation(libs.grpc.stub)
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
  // logback.classic is runtimeOnly above (main code stays decoupled from the Logback API);
  // WorkerLogbackConfigurationTest asserts the effective io.justsearch level from
  // src/main/resources/logback.xml, which needs the Logback classic types at test compile time.
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

// Configure the JAR manifest for direct execution
tasks.jar {
  manifest {
    attributes(
      "Main-Class" to "io.justsearch.indexerworker.IndexerWorker",
      "Implementation-Title" to "JustSearch Knowledge Server",
      "Implementation-Version" to project.version
    )
  }
  // logback.xml is included in the JAR so the spawned Worker process (via installDist)
  // has structured JSON logging. When this module appears on app-launcher's classpath,
  // the app-launcher's own logback.xml takes priority (logback uses the first config found).
}


// Configure distribution to include all runtime dependencies
distributions {
  main {
    distributionBaseName.set("indexer-worker")
  }
}

// Wire installDist into the standard assemble lifecycle so `./gradlew build` always
// produces an up-to-date worker distribution (replaces FatJarConventionsPlugin wiring).
tasks.named("assemble") {
  dependsOn("installDist")
}

// 371: Generate a content-hash build stamp for stale-JVM detection.
// The stamp changes iff the distribution contents change. External tools (jseval, MCP reload)
// read this file to detect whether a running JVM matches the on-disk distribution.
val generateBuildStamp by tasks.registering {
  dependsOn("installDist")
  val libDir = layout.buildDirectory.dir("install/indexer-worker/lib")
  inputs.dir(libDir)
  val stampFile = layout.buildDirectory.file("install/indexer-worker/build-stamp.txt")
  outputs.file(stampFile)

  doLast {
    val dir = libDir.get().asFile
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(8192)
    dir.listFiles()
        ?.filter { it.isFile }
        ?.sortedBy { it.name }
        ?.forEach { f ->
          md.update(f.name.toByteArray(Charsets.UTF_8))
          md.update("\n".toByteArray())
          // Content-hash project JARs (SNAPSHOT) for exact change detection.
          // Third-party JARs are immutable — name+size is sufficient and avoids
          // reading 371MB onnxruntime_gpu on every stamp.
          if (f.name.contains("SNAPSHOT")) {
            f.inputStream().use { stream ->
              var n: Int
              while (stream.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
              }
            }
          } else {
            md.update(f.length().toString().toByteArray(Charsets.UTF_8))
          }
        }
    val hash = md.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }.take(16)
    stampFile.get().asFile.writeText(hash + "\n")
  }
}

// Ensure the stamp is generated whenever installDist runs — covers runHeadless,
// runHeadlessEval, and any other task that triggers installDist directly.
tasks.named("installDist") {
  finalizedBy(generateBuildStamp)
}

// Standalone Worker task — runs the Knowledge Server without the Head process.
// Usage: ./gradlew :modules:indexer-worker:runWorkerStandalone [-PdataDir=...] [-PmodelsDir=...]
//
// This generates a minimal config snapshot and launches IndexerWorker directly.
// It binds no port: lane F stage A item A9 deleted the gRPC server, so this task now starts the
// Knowledge Server's infrastructure and indexing loop and nothing else can call into it.
// The MMF signal bus is safe without a Head (heartbeat defaults to zero → no suicide).
//
// All rootProject / project references are resolved at configuration time (not in doFirst)
// to stay compatible with the Gradle configuration cache.
run {
  // Resolve all project-relative values at configuration time
  val repoRootDir = rootProject.layout.projectDirectory.asFile.absolutePath
  val defaultModelsDir = rootProject.layout.projectDirectory.dir("models").asFile.absolutePath
  val defaultDataDir = layout.buildDirectory.dir("standalone-worker-data").get().asFile.absolutePath
  val logbackFile = rootProject.file("modules/app-launcher/src/main/resources/logback.xml")
  val logbackExists = logbackFile.exists()
  val logbackPath = logbackFile.absolutePath

  // Auto-detect ORT CUDA DLLs at configuration time
  val detectedCudaPath: String? = if (System.getenv("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH") == null) {
    conventions.OrtCudaHelpers.detectOrtCudaPath(rootProject.layout.projectDirectory.asFile)
  } else null

  tasks.register<JavaExec>("runWorkerStandalone") {
    group = "application"
    description = "Run the Worker (Knowledge Server) standalone for debugging — no Head required"
    dependsOn("installDist")
    mainClass.set("io.justsearch.indexerworker.IndexerWorker")
    classpath = sourceSets["main"].runtimeClasspath

    val dataDir = (providers.gradleProperty("dataDir").orNull
      ?: System.getenv("JUSTSEARCH_DATA_DIR")
      ?: defaultDataDir)
    val modelsDir = (providers.gradleProperty("modelsDir").orNull
      ?: System.getenv("JUSTSEARCH_MODELS_DIR")
      ?: defaultModelsDir)
    val collection = providers.gradleProperty("collection").orNull ?: "default"

    // Forward model-related env vars to the Worker
    val envVars = listOf(
      "JUSTSEARCH_EMBED_BACKEND",
      "JUSTSEARCH_EMBED_ONNX_MODEL_PATH",
      "JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH",
      "JUSTSEARCH_SPLADE_GPU_ENABLED",
      "JUSTSEARCH_INDEX_TRACING_LEVEL",
      "JUSTSEARCH_MODELS_DIR"
    )
    envVars.forEach { key ->
      System.getenv(key)?.let { value -> environment(key, value) }
    }
    environment("JUSTSEARCH_MODELS_DIR", modelsDir)

    if (detectedCudaPath != null) {
      environment("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH", detectedCudaPath)
      if (System.getenv("JUSTSEARCH_SPLADE_GPU_ENABLED") == null) {
        environment("JUSTSEARCH_SPLADE_GPU_ENABLED", "true")
      }
    }

    val snapshotFile = layout.buildDirectory.file("standalone-worker/worker-config-snapshot.json")

    doFirst {
      val snapshotDir = snapshotFile.get().asFile.parentFile
      snapshotDir.mkdirs()
      // Minimal config snapshot — ResolvedConfigBuilder provides defaults for most keys.
      // Only path-dependent keys need explicit values.
      val snapshot = linkedMapOf(
        "justsearch.data.dir" to dataDir,
        "justsearch.models.dir" to modelsDir,
        "justsearch.search.collection" to collection,
        "justsearch.repo.root" to repoRootDir,
        "justsearch.home" to dataDir,
        "justsearch.ssot.path" to "$repoRootDir/SSOT"
      )
      val json = snapshot.entries.joinToString(",\n  ", "{\n  ", "\n}") {
        "\"${it.key}\": \"${it.value.replace("\\", "\\\\")}\""
      }
      snapshotFile.get().asFile.writeText(json)
      logger.lifecycle("Standalone Worker config snapshot: ${snapshotFile.get().asFile}")
      logger.lifecycle("  dataDir:   $dataDir")
      logger.lifecycle("  modelsDir: $modelsDir")
      logger.lifecycle("  collection: $collection")
    }

    // Tempdoc 730 B3: this debug-only standalone task did not dump on OOM (unlike the production
    // worker, which WorkerSpawner launched with an always-on dump path — that spawner was deleted
    // at lane F stage A item A11). Keep the dump path here under this dataDir's crashes/ dir. 730
    // Increment-4 review: the heap cap (-Xmx) is opt-in ONLY (JUSTSEARCH_WORKER_HEAP) — no default
    // bound is emitted, since a default cap can itself induce an artifact OOM in the exact death
    // scenario this observability exists to diagnose (same principle as buildHeadJavaOpts in
    // scripts/dev/dev-runner.cjs).
    val standaloneHeap = System.getenv("JUSTSEARCH_WORKER_HEAP")
    jvmArgs(
      "-Djustsearch.worker.config_snapshot=${snapshotFile.get().asFile.absolutePath}",
      "-Djustsearch.data.dir=$dataDir",
      "--sun-misc-unsafe-memory-access=warn",
      "-XX:+HeapDumpOnOutOfMemoryError",
      "-XX:HeapDumpPath=$dataDir/crashes/"
    )
    if (!standaloneHeap.isNullOrBlank()) {
      jvmArgs("-Xmx$standaloneHeap")
    }

    // Use production logback config if available. Disable if console output is preferred
    // by passing -Pstandalone.console=true.
    if (logbackExists && providers.gradleProperty("standalone.console").orNull != "true") {
      jvmArgs("-Dlogback.configurationFile=$logbackPath")
    }
  }
}

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
          }
        }
      }
    }
  }
}
