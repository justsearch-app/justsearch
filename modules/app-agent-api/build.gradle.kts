plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  // Lane F C1: explicit EngineContext travels with operation and conversation invocations.
  api(project(":modules:core"))
  // Annotation-only dep: Jackson annotations are wire-contract metadata
  // (per tempdoc 429 §E.5). The new registry substrate (Operation/Resource/Prompt
  // sealed types) requires @JsonTypeInfo/@JsonSubTypes for victools to honor
  // sealed permits. No databind; wire annotations remain metadata.
  api(libs.jackson.annotations)
  // Tempdoc 560 §4.3 — the ONE transactional composer + four shared substrates, reused by both the
  // Head (here) and the Worker (worker-services). Pure JDK module; keeps app-agent-api annotation-light.
  implementation(project(":modules:extension-substrate"))
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

// Bridge `-PupdateSchemas` (project property) to the forked test JVM's system property, so the
// RegistryEnumsTsGenerationTest emitter (tempdoc 560 §4.1/§4.3 anti-drift) writes the FE
// registry-enums.generated.ts in update mode (mirrors app-observability's updateSchemas bridge).
tasks.withType<Test>().configureEach {
  if (project.hasProperty("updateSchemas")) {
    systemProperty("updateSchemas", "true")
  }
}

tasks.register<Test>("updateRegistryEnums") {
  description = "Regenerate the FE registry-enums.generated.ts from the Java enum authority."
  group = "verification"
  val testSS = sourceSets["test"]
  testClassesDirs = testSS.output.classesDirs
  classpath = testSS.runtimeClasspath
  useJUnitPlatform()
  filter { includeTestsMatching("*RegistryEnumsTsGenerationTest*") }
  systemProperty("updateSchemas", "true")
}
