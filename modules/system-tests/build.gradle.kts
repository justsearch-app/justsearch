import java.time.Duration

plugins {
  id("conventions.jvm-base")
}

description = "JustSearch System Tests - Chaos, Relevance, and Integration Testing"

fun flagEnabled(gradleProp: String, envVar: String): Boolean {
  val propValue = findProperty(gradleProp)?.toString()?.trim()?.toBooleanStrictOrNull()
  if (propValue != null) return propValue
  val envValue = System.getenv(envVar)?.trim()?.toBooleanStrictOrNull()
  if (envValue != null) return envValue
  return false
}

fun intOverride(gradleProp: String, envVar: String, defaultValue: Int): Int {
  val propValue = findProperty(gradleProp)?.toString()?.trim()
  val parsedProp = propValue?.toIntOrNull()
  if (parsedProp != null && parsedProp > 0) return parsedProp

  val envValue = System.getenv(envVar)?.trim()
  val parsedEnv = envValue?.toIntOrNull()
  if (parsedEnv != null && parsedEnv > 0) return parsedEnv

  return defaultValue
}

val includeSystemTests = flagEnabled("includeSystemTests", "JUSTSEARCH_INCLUDE_SYSTEM_TESTS")
val includeAiTests = flagEnabled("includeAiTests", "JUSTSEARCH_INCLUDE_AI_TESTS")
val includeAgentTests = flagEnabled("includeAgentTests", "JUSTSEARCH_INCLUDE_AGENT_TESTS")
val ragEvalTimeoutMinutes = intOverride(
  "ragEvalTimeoutMinutes",
  "JUSTSEARCH_RAG_EVAL_TIMEOUT_MINUTES",
  30
)

dependencies {
  // Internal dependencies
  testImplementation(project(":modules:app-services"))
  testImplementation(project(":modules:worker-services"))
  testImplementation(project(":modules:indexer-worker"))
  api(project(":modules:ipc-common"))
  runtimeOnly(project(":modules:adapters-lucene"))
  api(project(":modules:ai-backend"))

  // gRPC for IPC
  implementation(libs.grpc.stub)
  runtimeOnly(libs.grpc.netty.shaded)

  // Jackson for JSON manifests
  api(libs.jackson.databind)
  implementation(libs.jackson.core)
  runtimeOnly(libs.jackson.dataformat.yaml)

  // Logging
  implementation(libs.slf4j.api)
  runtimeOnly(libs.logback.classic)

  // Testing
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-core") {
      useVersion("3.1.0")
      because("Lock convergence for system test tiers")
    }
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-databind") {
      useVersion("3.1.0")
      because("Lock convergence for system test tiers")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
      useVersion("2.21")
      because("Lock convergence for system test tiers")
    }
    if (requested.group == "tools.jackson.dataformat" && requested.name == "jackson-dataformat-yaml") {
      useVersion("3.1.0")
      because("Lock convergence for system test tiers")
    }
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for system test tiers")
    }
  }
}

// Handle duplicate resources
tasks.withType<Copy>().configureEach {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Separate source sets for different test tiers
sourceSets {
  // Standard unit tests
  test {
    java.srcDir("src/test/java")
    resources.srcDir("src/test/resources")
  }

  // Integration tests (Golden Corpus)
  create("integrationTest") {
    java.srcDir("src/integrationTest/java")
    resources.srcDir("src/integrationTest/resources")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
  }

  // System/Chaos tests
  create("systemTest") {
    java.srcDir("src/systemTest/java")
    resources.srcDir("src/systemTest/resources")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
  }
}

// Configuration for test tiers
val integrationTestImplementation by configurations.getting {
  extendsFrom(configurations.testImplementation.get())
  extendsFrom(configurations.implementation.get())
}
val integrationTestRuntimeOnly by configurations.getting {
  extendsFrom(configurations.testRuntimeOnly.get())
  extendsFrom(configurations.runtimeOnly.get())
}

val systemTestImplementation by configurations.getting {
  extendsFrom(configurations.testImplementation.get())
}
val systemTestRuntimeOnly by configurations.getting {
  extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
  add("integrationTestImplementation", project(":modules:adapters-lucene"))
  add("integrationTestImplementation", libs.lucene.core)
  add("integrationTestImplementation", project(":modules:configuration"))
  add("integrationTestImplementation", project(":modules:indexing"))
  add("integrationTestImplementation", project(":modules:reranker"))
  // Tempdoc 847 S5 — the RAG faithfulness metric scores the answer's SENTENCES, so it uses the
  // production splitter (`AnswerSegmentation`) rather than a copy that drifts from it.
  add("integrationTestImplementation", project(":modules:worker-services"))
  add("integrationTestImplementation", testFixtures(project(":modules:ort-common"))) // §14.28 U1 helper
  add("integrationTestImplementation", project(":modules:app-agent"))
  add("integrationTestImplementation", project(":modules:app-agent-api"))
  add("integrationTestImplementation", project(":modules:app-api"))
  // IsolatedBackendFixture spawns io.justsearch.ui.HeadlessApp in a child JVM using the test
  // JVM's own java.class.path, so the Head must be ON that classpath. Without this the whole
  // fixture-based tier dies at @BeforeAll with ClassNotFoundException: HeadlessApp.
  add("integrationTestRuntimeOnly", project(":modules:ui"))
  add("integrationTestImplementation", "org.junit.jupiter:junit-jupiter-params:5.14.3")
  add("systemTestImplementation", project(":modules:indexing"))
  add("systemTestImplementation", project(":modules:gpu-bridge"))
  // Lane F stage A item A12 — the surviving system tests compose the Engine's index half
  // IN PROCESS (EngineRoot) instead of spawning a Worker and dialling a port. `configuration`
  // is what publishes the resolved config EngineRoot reads through WorkerConfig.load().
  add("systemTestImplementation", project(":modules:app-engine"))
  add("systemTestImplementation", project(":modules:configuration"))
  add("systemTestImplementation", testFixtures(project(":modules:worker-services")))
  add("systemTestImplementation", "org.junit.jupiter:junit-jupiter-params:5.14.3")
}

// Integration test task (Golden Corpus, Relevance)
val integrationTest = tasks.register<Test>("integrationTest") {
  description = "Runs integration tests (Golden Corpus, Relevance)."
  group = "verification"

  // Tempdoc 419 / T6.2 — IsolatedBackendFixture spawns HeadlessApp, which spawns the Worker
  // subprocess from modules/indexer-worker/build/install/indexer-worker. Without this,
  // fresh-checkout runs see Head boot fine while Worker spawn silently fails (the fixture
  // would then time out in awaitDocumentSearchable instead of failing fast). Mirrors the
  // same dependency on :modules:ui:runHeadless (modules/ui/build.gradle.kts:1844).
  dependsOn(":modules:indexer-worker:installDist")

  testClassesDirs = sourceSets["integrationTest"].output.classesDirs
  classpath = sourceSets["integrationTest"].runtimeClasspath

  useJUnitPlatform {
    if (!includeAiTests && !includeAgentTests) {
      excludeTags("ai")
    }
  }

  // Time limit: 30 minutes default, 30 for agent tests, configurable for AI runs.
  //
  // Tempdoc 821 P4 — this task timeout, not the job budget (ci.yml:529), is the binding
  // ceiling, and Gradle enforces it by killing the forked test JVM. That destroys the fixture's
  // diagnostics exactly like the 30s @BeforeAll cap below did, so it has to clear the
  // retry-amplified worst case rather than the happy-path wall.
  //
  // Arithmetic: Develocity sets maxRetries=2 in CI (JvmBaseConventionsPlugin.kt:135), so a class
  // whose @BeforeAll stalls is booted 3 times. That is not hypothetical — OperationPreviewE2ETest
  // exhausted all 3 attempts, ~100s apart, in run 31730197618 on 2026-08-13. The fixture worst
  // case per boot is now ~400s (PORT_FILE 60s + HEALTH 240s + WORKER_READY 90s + HttpClient send
  // overshoot), up from ~250s when HEALTH was 90s, so 3 boots cost ~20 min on top of the ~8.5 min
  // this tier takes to run everything else (measured: the Gradle step of run 31716264505 ran
  // 8m28s) = ~28.5 min. 22 min held the old ~250s worst case with ~1 min margin but cannot hold
  // this one; 30 min clears it with ~1.5 min margin.
  //
  // Cost note: the extra budget is spent only when a boot genuinely stalls three times. The
  // common case is unchanged (~8.5 min), and a dead backend still fails in seconds via the
  // fixture's process.isAlive() check rather than burning any of it.
  //
  // The AI and agent branches are already >= this and need no change.
  val integrationTestTimeoutMinutes = when {
    includeAiTests -> ragEvalTimeoutMinutes
    includeAgentTests -> 30
    else -> 30
  }
  timeout.set(Duration.ofMinutes(integrationTestTimeoutMinutes.toLong()))

  testLogging {
    events("passed", "skipped", "failed")
    showStandardStreams = true
  }

  // Tempdoc 821 P4 — hosted-runner init-timeout flakes.
  //
  // `conventions.jvm-base` sets junit.jupiter.execution.timeout.default=30s for every Test
  // task (JvmBaseConventionsPlugin.kt:117). A class-level @Timeout covers *testable* methods
  // only, never lifecycle methods, so every IsolatedBackendFixture @BeforeAll ran under that
  // 30s cap regardless of its class annotation — proven on 2026-08-13 by IngestStarvationE2ETest
  // ("Two-batch ingest starvation"), annotated @Timeout(6, MINUTES) at the class (:52) and dying
  // at t0+30.10s in run 31726924465.
  //
  // That cap is far SHORTER than the fixture's own layered boot budget (PORT_FILE 60s +
  // HEALTH 90s + WORKER_READY 90s, IsolatedBackendFixture.java:65-73), so it always fired
  // first and reported a bare java.util.concurrent.TimeoutException with no message or cause —
  // the fixture never reaches the point of naming the budget it blew. Five such
  // initializationError failures landed in five separate PR runs on 2026-08-13 (31718400614,
  // 31719369201, 31724706479, 31726924465, 31727436857) across four test classes, every one of
  // them passing on the automatic retry — red only because failOnPassedAfterRetry keeps flakes
  // visible.
  //
  // Headroom: measured boot-to-ready on windows-latest is min 6.54s / p50 7.17s / max 15.39s
  // (48 samples across that day's 8 integration-tier runs) — the old cap left under 2x over the
  // observed max.
  //
  // This value must stay ABOVE the fixture's own layered budget, or it fires first and reports a
  // bare TimeoutException with no message or cause, hiding which phase stalled. That budget is
  // now PORT_FILE 60s + HEALTH 240s + WORKER_READY 90s = 390s
  // (IsolatedBackendFixture.java:65-91), plus HttpClient send overshoot; 420s clears it. It moved
  // with HEALTH: 300s cleared the old 250s sum but would now cut the health phase off at ~t0+300s
  // and undo the diagnostics this tier just gained.
  systemProperty("junit.jupiter.execution.timeout.beforeall.method.default", "420s")

  // Tempdoc 821 P4 follow-up — hand the fixture a workspace-relative destination for the boot
  // logs it preserves on failure. It defaulted to java.io.tmpdir, which a hosted runner discards
  // when the job ends, so the 2026-08-13 flakes (runs 31730197618, 31732439890) printed
  // "log preserved at C:\Users\RUNNER~1\AppData\Local\Temp\..." and the logs were unreachable.
  // Under build/reports the existing "Upload integration-test results" step (ci.yml) archives
  // them with the rest of the tier's output.
  systemProperty(
      "isolatedBackend.failureLogDir",
      layout.buildDirectory.dir("reports/isolated-backend").get().asFile.absolutePath)

  // Forward API port system property for HTTP tests
  System.getProperty("justsearch.api.port")?.let { systemProperty("justsearch.api.port", it) }

  // Forward RAG eval context format for agent-style context experiments (tempdoc 213)
  System.getProperty("rag.eval.context.format")?.let { systemProperty("rag.eval.context.format", it) }

  // Tempdoc 419 / T6.2 — IsolatedBackendFixture spawns HeadlessApp in a child JVM whose
  // working directory is this module, not the repo root. Pass the absolute worker lib
  // path through so KnowledgeServerConfig.resolveWorkerLibDir doesn't need to walk
  // relative paths to find the installDist output. Mirrors the pattern systemTest uses
  // for justsearch.worker.dist.dir.
  systemProperty(
      "justsearch.worker.lib.dir",
      project(":modules:indexer-worker").layout.buildDirectory
          .dir("install/indexer-worker/lib").get().asFile.absolutePath)

  // Tempdoc 829 R3 — this lane is advisory (ci.yml `continue-on-error: true`) and absent
  // from `required_status_checks.contexts`, so a self-recovered flake here cannot change
  // mergeability; it only reddens the check and invites a pointless `gh run rerun --failed`
  // (F1: 12 such reruns measured 2026-08-13, every attempt-1 already `success`). The
  // convention plugin sets failOnPassedAfterRetry=true for every Test task, in CI, to keep
  // flakes loud (JvmBaseConventionsPlugin.kt:119-143) — correct for required lanes, wrong
  // here. Override it to false for THIS task only, using the same reflective pattern (the
  // Develocity 4.x testRetry extension type is shaded, so it can't be referenced directly).
  // maxRetries is left untouched, so retry itself still runs. This task-level configuration
  // action is registered after the convention plugin's project-wide
  // `tasks.withType<Test>().configureEach { ... }`, so it applies last and wins — the same
  // ordering this file already relies on for other Test-wide convention overrides (e.g.
  // `maxHeapSize` below for systemTest vs. the convention's 384m default).
  // Flake VISIBILITY does not disappear: it moves to the flaky-test extraction in
  // unit-test attribution (tempdoc 829 R5, same PR). Revisit when this lane joins required
  // contexts (tempdoc 825 §3 is that path).
  val retryExt = extensions.findByName("develocity")?.let { devExt ->
    try {
      devExt.javaClass.getMethod("getTestRetry").invoke(devExt)
    } catch (_: Exception) {
      null
    }
  } ?: extensions.findByName("retry")
  if (retryExt != null) {
    try {
      val failOnPassedProp = retryExt.javaClass.getMethod("getFailOnPassedAfterRetry").invoke(retryExt)
      @Suppress("UNCHECKED_CAST")
      (failOnPassedProp as org.gradle.api.provider.Property<Boolean>).set(false)
    } catch (e: ReflectiveOperationException) {
      logger.warn("Could not configure test retry via reflection: ${e.message}")
    } catch (e: ClassCastException) {
      logger.warn("Test retry extension has unexpected type: ${e.message}")
    }
  } else {
    // Neither the Develocity 4.x `develocity.testRetry` nor the deprecated 3.x `retry`
    // extension was found on this task, so the failOnPassedAfterRetry=false override above
    // is a silent no-op: the convention plugin's project-wide failOnPassedAfterRetry=true
    // (JvmBaseConventionsPlugin.kt:119-143) would still apply, and a self-recovered flake in
    // this advisory lane would redden the job exactly as R3 intended to prevent.
    logger.warn("Test retry extension not found (develocity.testRetry / retry) — R3's " +
        "failOnPassedAfterRetry=false override for integrationTest did not apply")
  }
}

// System test task (Chaos Suite)
val systemTest = tasks.register<Test>("systemTest") {
  description = "Runs system tests (Chaos Suite, Process Coordination)."
  group = "verification"

  // System tests are intentionally opt-in to keep `./gradlew check` runnable in CI/dev by default.
  // Enable with `-PincludeSystemTests=true` or `JUSTSEARCH_INCLUDE_SYSTEM_TESTS=true`.
  enabled = includeSystemTests

  // Ensure all worker artifacts are built before running tests
  // prepareTests builds both shadowJar (for JAR-mode tests) and installDist (for distribution-mode tests)
  dependsOn(rootProject.tasks.named("prepareTests"))

  testClassesDirs = sourceSets["systemTest"].output.classesDirs
  classpath = sourceSets["systemTest"].runtimeClasspath

  useJUnitPlatform {
    // AI system tests require a running external llama-server; exclude by default.
    if (!includeAiTests) {
      excludeTags("ai")
    }
  }

  // Time limit: < 1 hour (Nightly)
  timeout.set(Duration.ofHours(1))

  // System tests may need more heap for process spawning
  maxHeapSize = "1g"

  testLogging {
    events("passed", "skipped", "failed")
    showStandardStreams = true
  }

  // Pass system property for worker distribution directory location
  systemProperty("justsearch.worker.dist.dir", project(":modules:indexer-worker").layout.buildDirectory
      .dir("install/indexer-worker").get().asFile.absolutePath)
}

// Make check depend on unit tests
tasks.named("check") {
  dependsOn(tasks.named("test"))
}

// Disable strict coverage for this module — these are test harnesses, not production code.
tasks.withType<JacocoCoverageVerification>().configureEach {
  isEnabled = false
}

// The blanket `pmd { isIgnoreFailures = true }` that used to sit here is gone (tempdoc 930
// §22.2 follow-up 10). It was the same dormancy hole `modules/benchmarks` carried until
// follow-up 2 removed it: every PMD task in this module reported and then passed. `pmdMain`
// was already clean; the 84 violations it was hiding in `systemTest`/`integrationTest`
// were cleared, and `config/pmd/ruleset-tests.xml` drops the two rules that a system test
// genuinely disproves (`SystemPrintln`, `NonThreadSafeSingleton`) instead of ignoring all of them.

// Generate frozen embeddings for passage-retrieval corpus via llama-server
tasks.register<JavaExec>("generatePassageVectors") {
  description = "Generates frozen embeddings for passage-retrieval corpus via llama-server."
  group = "corpus"
  mainClass.set("io.justsearch.systemtests.corpus.PassageRetrievalVectorGenerator")
  classpath = sourceSets["integrationTest"].runtimeClasspath
  workingDir = rootProject.projectDir

  val serverUrl = findProperty("llamaServerUrl")?.toString()
      ?: "http://127.0.0.1:8081/v1/embeddings"
  systemProperty("llama.server.url", serverUrl)

  // Pass --deterministic if requested
  if (findProperty("deterministic")?.toString()?.toBoolean() == true) {
    args("--deterministic")
  }
}

// Custom task for full test suite (all tiers except soak)
tasks.register("fullTestSuite") {
  description = "Runs all test tiers: unit, integration, and system tests."
  group = "verification"
  dependsOn(tasks.named("test"), integrationTest, systemTest)
}
