plugins {
  `java-library`
  `java-test-fixtures`
  id("jvm-test-suite")
  id("conventions.jvm-base")
  // Scoped to the seams registered for this module in governance/logic-seams.v1.json (tempdoc 627:
  // the worker-supervision decision). PIT targets ONLY registered seams — zero blast radius on the
  // rest of app-services; the test-efficacy gate is opt-in CI (ADR-0026), so it never blocks PRs.
  id("conventions.mutation")
}

extra["coverage.enforce"] = "true"

dependencies {
  api(project(":modules:configuration"))
  api(project(":modules:app-api"))
  api(project(":modules:app-agent"))
  api(project(":modules:app-agent-api"))
  api(project(":modules:app-inference"))
  api(project(":modules:app-config"))
  api(project(":modules:app-util"))
  api(project(":modules:app-observability"))
  api(project(":modules:core"))
  implementation(project(":modules:reranker"))
  implementation(project(":modules:indexing"))
  api(project(":modules:ipc-common"))
  api(project(":modules:telemetry"))
  api(project(":modules:infra-core"))
  implementation(project(":modules:ai-backend"))
  api(project(":modules:gpu-bridge"))
  api(libs.jackson.databind)
  implementation(libs.jackson.core)
  implementation(libs.jackson.dataformat.yaml)
  api(libs.opentelemetry.api)
  // Slice 3a-2-c Phase C: JSON Schema validator for OperationDispatcher
  // input-arg validation. Validates argumentsJson against
  // Operation.interface().inputs() before invoking the handler.
  implementation(libs.json.schema.validator)
  api("io.github.metarank:lightgbm4j:4.6.0-2")
  // PDFBox for VDU (Vision Document Understanding) - rendering PDF pages to images
  implementation("org.apache.pdfbox:pdfbox:3.0.6")
  // Tempdoc 629: Argon2id KDF for at-rest key derivation (EncryptionEnvelope). Version-aligned with
  // the worker modules' existing transitive bcprov (1.81.1).
  implementation("org.bouncycastle:bcprov-jdk18on:1.81.1")
  implementation(libs.grpc.stub)
  implementation(libs.grpc.netty.shaded)  // gRPC transport - uses NettyChannelBuilder at compile time
  runtimeOnly(libs.lucene.core)
  runtimeOnly(libs.lucene.analysis.common)
  runtimeOnly(libs.lucene.analysis.icu)
  api(libs.slf4j.api)

  testImplementation(testFixtures(project(":modules:configuration")))
  testImplementation(testFixtures(project(":modules:telemetry")))
  testImplementation(libs.logback.classic)
  testImplementation(libs.logback.core)
  testImplementation(libs.grpc.inprocess)
  testImplementation(libs.mockito.core)
  testImplementation(libs.mockito.junit.jupiter)
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-core") {
      useVersion("3.1.0")
      because("Lock convergence for app-services classpaths")
    }
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-databind") {
      useVersion("3.1.0")
      because("Lock convergence for app-services classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
      useVersion("2.21")
      because("Lock convergence for app-services classpaths (annotations shared between Jackson 2.x/3.x)")
    }
    if (requested.group == "tools.jackson.dataformat" && requested.name == "jackson-dataformat-yaml") {
      useVersion("3.1.0")
      because("Lock convergence for app-services classpaths")
    }
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for app-services classpaths")
    }
  }
}

val sourceSets = the<SourceSetContainer>()
val mainSourceSet = sourceSets["main"]
val testSourceSet = sourceSets["test"]
val searchDumpSourceSet = the<SourceSetContainer>().create("searchDump") {
  java.srcDir("../../scripts/phase12")
  compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
  runtimeClasspath += output + compileClasspath + mainSourceSet.runtimeClasspath
}

configurations.getByName("searchDumpImplementation")
    .extendsFrom(configurations.getByName("implementation"))
configurations.getByName("searchDumpRuntimeOnly")
    .extendsFrom(configurations.getByName("runtimeOnly"))

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        implementation("org.junit.jupiter:junit-jupiter-params:5.14.3")
        implementation(libs.archunit.junit5)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
      targets {
        all {
          testTask.configure {
            // Throttles ONLY this suite's internal fork parallelism. In CI the shared
            // TestGateService already serializes cross-module Test tasks (testParallelism=1,
            // see JvmBaseConventionsPlugin), so this does not guard against cross-module
            // contention. app-services lives in the measured critical-path shard (app-ui,
            // slowest in 12/15 sampled runs; tempdoc 668). This `1` is a conservative default,
            // not a memory constraint: the hosted runner has 16 GiB (2x384 MB forks + a 1 GB
            // daemon is trivial) and only 4 vCPUs, so the real ceiling is CPU. Raising to 2 is
            // plausibly safe but is an optimization-band change deferred to hosted validation
            // (tempdoc 668 scope boundary), not made here.
            maxParallelForks = 1
            jvmArgs("--enable-native-access=ALL-UNNAMED")
            jvmArgs("-Dnet.bytebuddy.experimental=true")
            // Forward the regen flag to the forked test JVM so the documented
            // `-Dupdate.shapes.fixture=true` (read by ConversationShapeFixtureGenTest) actually works.
            providers.systemProperty("update.shapes.fixture").orNull?.let {
              systemProperty("update.shapes.fixture", it)
            }
          }
        }
      }
    }
    register<JvmTestSuite>("integrationTest") {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
      targets {
        all {
          testTask.configure {
            shouldRunAfter(tasks.named("test"))
            jvmArgs("--enable-native-access=ALL-UNNAMED")
            // Quarantine (2026-09-07): `load-sensitive` tests assert wall-clock latency, so a
            // machine under concurrent-build load fails them for a reason that has nothing to do
            // with the code. They run in `loadSensitiveTest` below, on demand, with their
            // thresholds unchanged — excluding them here is what stops a contended machine
            // reddening `build` (this suite is a `check` dependency).
            useJUnitPlatform { excludeTags("load-sensitive") }
          }
        }
      }
    }
  }
}

// The other half of the quarantine: the only task that RUNS the load-sensitive tag. Deliberately
// NOT wired into `check` or any CI lane — this repo has no perf-ratchet lane today (checked
// 2026-09-07: `.github/workflows` has no perf or benchmark job), so wiring it into hosted CI would
// reproduce the flake there instead of here. Run it on an idle machine when a latency claim matters:
//   ./gradlew.bat :modules:app-services:loadSensitiveTest
// Resolved OUTSIDE the configuration block on purpose: inside it, `the<SourceSetContainer>()`
// resolves against the Test task's extensions, not the project's, and fails at configuration time.
val integrationTestSourceSet = the<SourceSetContainer>().named("integrationTest")

tasks.register<Test>("loadSensitiveTest") {
  group = "verification"
  description = "Runs the load-sensitive latency gates that integrationTest excludes."
  testClassesDirs = integrationTestSourceSet.get().output.classesDirs
  classpath = integrationTestSourceSet.get().runtimeClasspath
  useJUnitPlatform { includeTags("load-sensitive") }
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  shouldRunAfter(tasks.named("test"))
}

dependencies {
  add("integrationTestImplementation", libs.grpc.stub)
  add("integrationTestImplementation", libs.protobuf.java.util)

  api("dev.cel:common:0.12.0")
  api("dev.cel:runtime:0.12.0")
  api(project(":modules:api-contract-projection-java"))
  api(project(":modules:ort-common"))
  implementation("dev.cel:compiler:0.12.0")
  add("integrationTestRuntimeOnly", "org.threeten:threeten-extra:1.8.0")
  runtimeOnly("org.threeten:threeten-extra:1.8.0")
  add("searchDumpRuntimeOnly", "org.threeten:threeten-extra:1.8.0")
}

tasks.named("check") { dependsOn(tasks.named("integrationTest")) }

// Ensure worker JAR is built before integration tests run
// Ensure worker JAR is built before integration tests run
tasks.named("integrationTest") {
  dependsOn(project(":modules:indexer-worker").tasks.named("installDist"))
}

tasks.register<JavaExec>("runSearchDump") {
  group = "phase13"
  description = "Run the SearchDump helper CLI (scripts/phase12/SearchDump.java)."
  classpath = searchDumpSourceSet.runtimeClasspath
  mainClass.set("SearchDump")
  doFirst {
    val argsFile = project.findProperty("searchDumpArgsFile") as String?
    val argsValue = project.findProperty("searchDumpArgs") as String?
    val parsedArgs =
        when {
          argsFile != null -> file(argsFile).readLines().filter { it.isNotBlank() }
          !argsValue.isNullOrBlank() -> argsValue.split('\u0000')
          else -> emptyList()
        }
    if (parsedArgs.isEmpty()) {
      throw GradleException("No SearchDump arguments provided. Supply -PsearchDumpArgsFile=<path> or -PsearchDumpArgs.")
    }
    args(parsedArgs)
  }
}

// Registry snapshot for the consumer-presence governance gate (tempdoc 560 §5/§6). Always-run
// (no up-to-date caching) so the snapshot the Node gate reads is never stale; mirrors the
// module-deps gate's tmp/*.json generation step. Run before `node scripts/governance/run.mjs
// --gate consumer-presence`.
tasks.register<JavaExec>("exportRegistrySnapshot") {
  group = "verification"
  description = "Export the registry snapshot for the consumer-presence governance gate (tempdoc 560)."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("io.justsearch.app.services.registry.snapshot.RegistrySnapshotExporter")
  workingDir = rootProject.projectDir
  outputs.upToDateWhen { false }
}

// Ensure integrationTest inherits unit test dependencies to stay within lock state
configurations.named("integrationTestImplementation") {
  extendsFrom(configurations.named("testImplementation").get())
}
configurations.named("integrationTestRuntimeOnly") {
  extendsFrom(configurations.named("testRuntimeOnly").get())
}

tasks.register<Test>("redactionLintTest") {
  group = "verification"
  description = "Runs targeted redaction golden tests to ensure SensitiveString/RedactionLogger compliance."
  testClassesDirs = testSourceSet.output.classesDirs
  classpath = testSourceSet.runtimeClasspath
  useJUnitPlatform()
  filter {
    includeTestsMatching("*Redaction*")
  }
  shouldRunAfter(tasks.named("test"))
  reports.html.required.set(true)
  reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/redactionLint"))
  reports.junitXml.required.set(true)
  reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/redactionLint"))
}
