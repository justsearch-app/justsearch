plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  // GpuMemoryReader and its sample types (ADR-0050); app-api does not depend on gpu-bridge.
  api(project(":modules:app-api"))
  implementation(project(":modules:configuration"))
  implementation(libs.slf4j.api)
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
        implementation("org.junit.jupiter:junit-jupiter-params:5.14.3")
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
  }
}

tasks.withType<Test>().configureEach {
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  workingDir = rootProject.projectDir
}
