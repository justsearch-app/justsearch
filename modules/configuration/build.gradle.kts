plugins {
  `java-library`
  `java-test-fixtures`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  api(libs.jackson.databind)
  implementation(libs.jackson.core)
  implementation(libs.jackson.dataformat.yaml)
  api(libs.slf4j.api)
  // Tempdoc 518 Appendix F W4.1 — shared ObservableNotifier substrate.
  implementation(project(":modules:core-contracts"))
  testFixturesRuntimeOnly(libs.jackson.core)
  testFixturesRuntimeOnly(libs.jackson.databind)
  testFixturesRuntimeOnly(libs.jackson.dataformat.yaml)
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(testFixtures(project()))
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
  }
}

tasks.named<Test>("test") {
  // ConfigApplyRegisterTest reads this governed input outside the Java source tree.
  inputs.file(rootProject.layout.projectDirectory.file("governance/config-apply.v1.json"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
