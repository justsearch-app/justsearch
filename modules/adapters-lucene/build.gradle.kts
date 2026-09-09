plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
  id("conventions.mutation")  // PIT scoped to registered seams (governance/logic-seams.v1.json) — tempdoc 555
}

extra["coverage.enforce"] = "true"

dependencies {
  testImplementation(testFixtures(project(":modules:core")))
  api(project(":modules:configuration"))  // Exposes configuration types in public API
  api(project(":modules:indexing"))  // Exposes IndexDocument, FieldDefinition in public API
  api(project(":modules:core"))  // Exposes core DTOs in public API
  // Schema validation + canonical JSON hashing helpers
  implementation(libs.jackson.databind)  // Exposes ObjectMapper in public API
  implementation(libs.jackson.core)
  runtimeOnly(libs.jackson.dataformat.yaml)
  implementation(libs.json.schema.validator)
  // Concurrency annotations (@ThreadSafe, @GuardedBy)
  api(libs.jcip.annotations)
  // Logging API
  implementation(libs.slf4j.api)
  api(libs.lucene.core)
  implementation(libs.lucene.analysis.common)
  implementation(libs.lucene.analysis.icu)
  api(libs.lucene.queryparser)
  // @RecordBuilder for RuntimeSearchFilters (370/366 convention)
  compileOnly(libs.record.builder.core)
  annotationProcessor(libs.record.builder.processor)
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for adapters-lucene classpaths")
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
        implementation("org.junit.jupiter:junit-jupiter-params:5.14.3")
        implementation(libs.archunit.junit5)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
        annotationProcessor(libs.record.builder.processor)
      }
      targets {
        all {
          testTask.configure {
            // Vector API (jdk.incubator.vector) intentionally omitted — see tempdoc 269 §D4a
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
      targets { all { testTask.configure { shouldRunAfter(tasks.named("test")) } } }
    }
  }
}

tasks.named("check") { dependsOn(tasks.named("integrationTest")) }

// Tempdoc 393 § 3.6: the classpath copy of SSOT/catalogs/ is derived from the
// repo-root SSOT, not maintained by hand. Hooking into processResources keeps
// the two copies byte-identical; deleting a file from the target and re-running
// processResources restores it.
val syncSsotCatalogs by tasks.registering(Sync::class) {
  group = "build"
  description = "Mirror SSOT/catalogs/ from the repo root into adapters-lucene resources."
  from(rootProject.file("SSOT/catalogs")) {
    include("fields.v1.json", "analyzers.v1.json")
  }
  into(layout.projectDirectory.dir("src/main/resources/SSOT/catalogs"))
}

tasks.named("processResources") { dependsOn(syncSsotCatalogs) }

// Spotless scans src/main/resources, which syncSsotCatalogs may rewrite.
tasks.matching { it.name.startsWith("spotless") }.configureEach {
  mustRunAfter(syncSsotCatalogs)
}
