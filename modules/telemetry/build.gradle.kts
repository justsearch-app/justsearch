import org.gradle.api.tasks.SourceSetContainer

plugins {
  `java-library`
  `java-test-fixtures`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

val sourceSets = the<SourceSetContainer>()

dependencies {
  testImplementation(testFixtures(project(":modules:core")))
  api(project(":modules:core"))
  api(libs.opentelemetry.api)
  implementation(libs.opentelemetry.sdk)
  api(libs.opentelemetry.sdk.metrics)
  implementation("io.opentelemetry:opentelemetry-sdk-common:1.60.1")
  api("io.opentelemetry:opentelemetry-sdk-trace:1.60.1")
  implementation(libs.opentelemetry.exporter.otlp)
  implementation(libs.slf4j.api)
  // Needed at compile time for tests that configure Logback/Logstash programmatically
  testImplementation(libs.logback.classic)
  testImplementation(libs.logback.core)
  testImplementation(libs.logstash.logback.encoder)
  testImplementation(libs.jackson.databind)
  // RRD4J: time-series storage for curated metrics (G1 implementation)
  implementation("org.rrd4j:rrd4j:3.10")
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for telemetry classpaths")
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
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
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

tasks.register<JavaExec>("runWorkflowTraceProbe") {
  group = "verification"
  description = "Emit one workflow-scoped span through the telemetry module."
  classpath = sourceSets.named("main").get().runtimeClasspath
  mainClass.set("io.justsearch.telemetry.WorkflowTraceProbe")
  val probeDataDir = project.findProperty("probeDataDir")?.toString()
  if (!probeDataDir.isNullOrBlank()) {
    args(probeDataDir)
  }
  val probeSpanName = project.findProperty("probeSpanName")?.toString()
  if (!probeSpanName.isNullOrBlank()) {
    args(probeSpanName)
  }
}
