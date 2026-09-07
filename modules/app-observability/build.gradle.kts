plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  // Configuration - app-config exposes types in public API
  implementation(project(":modules:configuration"))
  api(project(":modules:app-config"))
  implementation(project(":modules:app-util"))

  // Tempdoc 430 Phase 1: HealthResourceCatalog implements ResourceCatalog from
  // app-agent-api; HealthEvent's discriminator annotations (jackson-annotations) come
  // transitively via app-agent-api (annotations-only API per its build.gradle.kts).
  api(project(":modules:app-agent-api"))

  // Slice 436 Phase 1: SseEnvelope / SseFrameKind / StreamId wire-format types live in
  // app-api. The per-stream state types here (FrameHistoryRingBuffer, ResumeTokenCodec)
  // wrap the wire-format types.
  api(project(":modules:app-api"))

  // AI/Pipeline
  implementation(project(":modules:prompt-support"))
  // Infrastructure - exposed in public API
  api(project(":modules:infra-core"))
  api(project(":modules:ipc-common"))

  // Logging
  api(libs.slf4j.api)
  // Slice 448 phase 3: DiagnosticChannelAppender extends UnsynchronizedAppenderBase
  // (logback-core) and consumes ILoggingEvent (logback-classic). Logback is already on
  // the runtime classpath transitively via the entry-point modules; declaring here
  // promotes it to a compile-classpath dep so the appender compiles cleanly.
  api(libs.logback.classic)
  api(libs.logback.core)

  // JSON serialization
  api(libs.jackson.core)
  api(libs.jackson.databind)

  // gRPC

}

// Direct declarations for transitive test dependencies (per dependency-analysis advice).
dependencies {
  testImplementation("com.fasterxml:classmate:1.7.2")
  testImplementation("net.jqwik:jqwik-api:1.10.1")
  testRuntimeOnly("net.jqwik:jqwik-time:1.10.1")
  testRuntimeOnly("net.jqwik:jqwik-web:1.10.1")
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

        // Logback for MDC tests
        implementation(libs.logstash.logback.encoder)
        implementation(libs.json.schema.validator)
        // Tempdoc 430 Phase 1: HealthEventSchemaTest mirrors SubstrateSchemaGenTest
        // (capture-or-verify schema generation with victools + Jackson 3 ObjectMapper).
        implementation(libs.jsonschema.generator)
        implementation(libs.jsonschema.module.jackson)
        // Tempdoc 564 Phase 4: the parallel `wire-types.ts` (typescript-generator record→TS) path is
        // retired — every FE wire type is now the generated record→JSON-Schema→{TS,Zod} projection
        // (schema-types). WireTypesTsGenerationTest + the typescript-generator dependency are removed.

        // Slice 448 closure-pass D5: ArchUnit governance test enforcing the
        // delivery-internal marker import discipline within the diagnostic
        // package. See DiagnosticChannelArchUnitTest.
        implementation(libs.archunit.junit5)
      }
    }
  }
}

// Regenerate the schema baselines (capture-or-verify). Mirrors the
// `:modules:app-api:updateSchemas` pattern — runs with `-DupdateSchemas=true`, switching the test's
// branch from "verify" to "rewrite baseline." Default `test` runs in verify mode (CI gate).
tasks.withType<Test>().configureEach {
  // Tempdoc 696 follow-up: check the actual -P properties, NOT project.hasProperty("updateSchemas"),
  // which is ALWAYS true here because the `updateSchemas` task (registered below) shadows the property
  // name — that silently forced every test run into regenerate mode instead of compare, defeating the
  // schema-drift guard. startParameter.projectProperties is the -P map only, immune to task-name shadowing.
  if (gradle.startParameter.projectProperties.containsKey("updateSchemas")) {
    systemProperty("updateSchemas", "true")
  }
}

tasks.register<Test>("updateSchemas") {
  description = "Regenerate schema baselines (HealthEvent)"
  group = "verification"
  val testSS = sourceSets["test"]
  testClassesDirs = testSS.output.classesDirs
  classpath = testSS.runtimeClasspath
  useJUnitPlatform()
  filter {
    includeTestsMatching("*HealthEventSchemaTest*")
  }
  systemProperty("updateSchemas", "true")
}
