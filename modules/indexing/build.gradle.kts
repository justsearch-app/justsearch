plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

extra["coverage.enforce"] = "true"

dependencies {
  runtimeOnly(libs.jackson.databind)
  runtimeOnly(libs.jackson.core)
  implementation(project(":modules:core"))

  // DAP: transitive test dependencies declared directly.
  // Must live in a top-level dependencies block (these configurations do not
  // exist inside a JvmTestSuite dependencies {} block).
  testImplementation("net.jqwik:jqwik-api:1.9.3")
  // tempdoc 749: without the engine, @Property methods compile but are silently never
  // collected by the JUnit platform — ChunkTilingPropertyTest was inert green until this.
  testRuntimeOnly("net.jqwik:jqwik-engine:1.9.3")
  testRuntimeOnly("net.jqwik:jqwik-time:1.9.3")
  testRuntimeOnly("net.jqwik:jqwik-web:1.9.3")
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-core") {
      useVersion("3.1.0")
      because("Lock convergence for indexing classpaths")
    }
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-databind") {
      useVersion("3.1.0")
      because("Lock convergence for indexing classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
      useVersion("2.21")
      because("Lock convergence for indexing classpaths")
    }
    if (requested.group == "tools.jackson.dataformat" && requested.name == "jackson-dataformat-yaml") {
      useVersion("3.1.0")
      because("Lock convergence for indexing classpaths")
    }
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for indexing classpaths")
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
        implementation(project(":modules:adapters-lucene"))
        implementation(libs.lucene.core)
        runtimeOnly(libs.lucene.analysis.common)
        implementation(libs.logback.classic)
        implementation(libs.logback.core)
        implementation(libs.slf4j.api)
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
