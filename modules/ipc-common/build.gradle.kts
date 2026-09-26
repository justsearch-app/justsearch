import org.gradle.api.plugins.quality.Pmd

plugins {
  `java-library`
  id("conventions.jvm-base")
  alias(libs.plugins.protobuf)
}

tasks.withType<Pmd>().configureEach {
  enabled = false
}

dependencies {
  api(project(":modules:app-api"))
  api(libs.guava)
  api(libs.protobuf.java)
  implementation(libs.slf4j.api)
  implementation(libs.opentelemetry.api)
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
  testRuntimeOnly(libs.logback.classic)
}

// Lane F stage A item A14: protoc only. The three `service` blocks left indexing.proto with the
// wire they described (items A9-A11), and the last gRPC service in this module,
// InfraDiagnosticsService, went with them — so protoc-gen-grpc-java has nothing to generate and is
// no longer resolved. What survives is the message half: these protos are the DTOs at the Engine's
// in-process ports (design section 6, transitional).
val protocVersion = libs.versions.protoc.get()
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val isMac = System.getProperty("os.name").lowercase().contains("mac")
val arch = System.getProperty("os.arch").lowercase()
val osClassifier = when {
  isWindows -> "windows-x86_64"
  isMac -> if (arch == "aarch64") "osx-aarch_64" else "osx-x86_64"
  else -> "linux-x86_64"
}

fun resolveTool(notation: String): java.io.File {
  val configuration =
      configurations.detachedConfiguration(dependencies.create(notation)).apply {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
      }
  val file = configuration.singleFile
  // Maven-cached native tools lack the executable bit; +x for Linux/macOS CI
  // (generateProto java.io.IOException error=13). No-op on Windows. Tempdoc 668.
  file.setExecutable(true)
  return file
}

val protocBinary =
    resolveTool("com.google.protobuf:protoc:${protocVersion}:${osClassifier}@exe")

protobuf {
  protoc {
    path = protocBinary.absolutePath
  }
}

tasks.withType<JacocoReport>().configureEach {
  classDirectories.setFrom(files(classDirectories.files.map {
    fileTree(it) {
      exclude("**/io/justsearch/ipc/**")
      exclude("**/pc/v1/**")
      exclude("**/*Grpc*")
      exclude("**/*OuterClass*")
    }
  }))
}

tasks.withType<JacocoCoverageVerification> {
  enabled = false
}
