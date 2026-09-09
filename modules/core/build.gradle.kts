

plugins {
  `java-library`
  `java-test-fixtures`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

extra["coverage.enforce"] = "true"

dependencies {
  api(libs.jackson.annotations)
  testImplementation(libs.json.schema.validator)
  testImplementation(libs.jackson.databind)
  testRuntimeOnly(libs.jackson.core)

  // Direct declarations for transitive test dependencies (per dependency-analysis advice).
  testImplementation("net.jqwik:jqwik-api:1.10.1")
  testRuntimeOnly("net.jqwik:jqwik-time:1.10.1")
  testRuntimeOnly("net.jqwik:jqwik-web:1.10.1")
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for core classpaths")
    }
  }
}

testing {
  suites {
    // Configure the default unit test suite (JUnit Jupiter)
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        implementation(libs.archunit.junit5)
        implementation(libs.jackson.databind)
        implementation(libs.json.schema.validator)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }

    // Add an integrationTest suite
    register<JvmTestSuite>("integrationTest") {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
        // DAP: integrationTest transitive runtime deps (declared directly).
        runtimeOnly("net.jqwik:jqwik-time:1.10.1")
        runtimeOnly("net.jqwik:jqwik-web:1.10.1")
      }
      targets { all { testTask.configure { shouldRunAfter(tasks.named("test")) } } }
    }
  }
}

tasks.named("check") { dependsOn(tasks.named("integrationTest")) }

// Ensure integrationTest inherits unit test dependencies (e.g., Jackson used by tests)
configurations.named("integrationTestImplementation") {
  extendsFrom(configurations.named("testImplementation").get())
}
configurations.named("integrationTestRuntimeOnly") {
  extendsFrom(configurations.named("testRuntimeOnly").get())
}
