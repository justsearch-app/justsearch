plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  // ORT types and shared infra (OrtSessionFactory, OrtCudaStatus, etc.)
  api(project(":modules:ort-common"))
  api(project(":modules:configuration"))
  implementation(libs.djl.tokenizers)
  implementation(libs.slf4j.api)
  // Tempdoc 400 §22 Issue D / LR2-e.2 (Phase 6 / 6.8): move the
  // `search/rerank` span into CrossEncoderReranker itself so EVERY
  // rerank caller (chunk, search, document) emits the same span shape
  // without duplicating wiring at each caller.
  implementation(libs.opentelemetry.api)
  // Tempdoc 553 Phase D (head): the shared OpenInferenceSpans projector lives in telemetry so the
  // search/rerank span uses the same OpenInference vocabulary as the worker + head spans (no fork).
  implementation(project(":modules:telemetry"))
  compileOnly(libs.logstash.logback.encoder)
  testRuntimeOnly(libs.logstash.logback.encoder)
  testRuntimeOnly(libs.onnxruntime)
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for reranker classpaths")
    }
  }
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(testFixtures(project(":modules:configuration")))
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        implementation(libs.mockito.core)
        implementation("org.junit.jupiter:junit-jupiter-params:5.14.3")
        implementation(libs.archunit.junit5)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
    register<JvmTestSuite>("integrationTest") {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(testFixtures(project(":modules:ort-common"))) // §14.28 U1 helper
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
