plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  runtimeOnly(libs.jackson.databind)
  runtimeOnly(libs.jackson.core)
  runtimeOnly(libs.jackson.dataformat.yaml)
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for infra-core classpaths")
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
        runtimeOnly(libs.logback.classic)
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
        runtimeOnly(libs.jackson.databind)
        runtimeOnly(libs.jackson.dataformat.yaml)
      }
      targets { all { testTask.configure { shouldRunAfter(tasks.named("test")) } } }
    }
  }
}

tasks.named("check") { dependsOn(tasks.named("integrationTest")) }

// Ensure integrationTest inherits unit test dependencies (e.g., logging/JSON helpers)
configurations.named("integrationTestImplementation") {
  extendsFrom(configurations.named("testImplementation").get())
}
configurations.named("integrationTestRuntimeOnly") {
  extendsFrom(configurations.named("testRuntimeOnly").get())
}
