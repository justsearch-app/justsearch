plugins {
  `java-library`
  `java-test-fixtures`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  api(project(":modules:adapters-lucene"))
  implementation(project(":modules:indexing"))
  api(project(":modules:configuration"))
  api(project(":modules:ort-common"))
  api(project(":modules:telemetry"))
  api(project(":modules:ai-backend"))

  api(libs.djl.tokenizers)
  api(libs.djl.api)
  runtimeOnly(libs.onnxruntime.gpu)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.core)
  implementation(libs.jackson.annotations)
  implementation(libs.slf4j.api)
  implementation(libs.commons.text)
  implementation(libs.commons.codec)
  api(libs.opentelemetry.api)
  implementation(libs.hdrhistogram)

  // SQLite for EntityClusterStore (disambiguation) and queue DB health
  runtimeOnly(libs.sqlite.jdbc)

}

// Tempdoc 575 §17 Face A: LivenessWindows.java is GENERATED from the register
// (scripts/codegen/gen-liveness-constants.mjs). Exclude it from Spotless so the
// generator's output is the single authority — a formatter must never reshape a
// generated file (and google-java-format is JDK-incompatible here anyway). The
// regen-idempotency + constant-single-authority gates enforce it instead.
spotless {
  java {
    targetExclude("src/main/java/io/justsearch/indexerworker/liveness/LivenessWindows.java")
  }
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "com.fasterxml.jackson" && requested.name == "jackson-bom") {
      useVersion("2.18.6")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-core") {
      useVersion("2.18.6")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-databind") {
      useVersion("2.18.6")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
      useVersion("2.21")
      because("Jackson 3 requires jackson-annotations 2.21+ (JsonSerializeAs)")
    }
    if (requested.group == "com.fasterxml.jackson.dataformat" && requested.name == "jackson-dataformat-yaml") {
      useVersion("2.18.6")
      because("Lock convergence for worker classpaths")
    }
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for worker classpaths")
    }
  }
}

// ---------------------------------------------------------------------------
// verifyModel task — loads an ONNX model with production session options
// ---------------------------------------------------------------------------

tasks.register<JavaExec>("verifyModel") {
  group = "verification"
  description = "Load an ONNX model with production ORT session options and run a dummy inference"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("io.justsearch.indexerworker.ort.ModelVerifier")
  workingDir = rootProject.layout.projectDirectory.asFile

  // Pass model path and flags from Gradle project properties:
  //   ./gradlew.bat :modules:worker-core:verifyModel -Pmodel=<path> [-Pgpu=true]
  //     [-PgpuDeviceId=0] [-PgpuMemMb=4096] [-PnativePath=<path>]
  val modelProp = providers.gradleProperty("model")
  val gpuProp = providers.gradleProperty("gpu")
  val gpuDeviceIdProp = providers.gradleProperty("gpuDeviceId")
  val gpuMemMbProp = providers.gradleProperty("gpuMemMb")
  val nativePathProp = providers.gradleProperty("nativePath")

  doFirst {
    if (!modelProp.isPresent) {
      throw GradleException(
        "Required property 'model' not set. Usage:\n" +
        "  ./gradlew.bat :modules:worker-core:verifyModel -Pmodel=models/splade/naver-splade-v3/model.onnx\n" +
        "  ./gradlew.bat :modules:worker-core:verifyModel -Pmodel=<path> -Pgpu=true"
      )
    }
    val argList = mutableListOf(modelProp.get())
    if (gpuProp.getOrElse("false").toBoolean()) {
      argList.add("--gpu")
    }
    if (gpuDeviceIdProp.isPresent) {
      argList.addAll(listOf("--device", gpuDeviceIdProp.get()))
    }
    if (gpuMemMbProp.isPresent) {
      argList.addAll(listOf("--mem-mb", gpuMemMbProp.get()))
    }
    if (nativePathProp.isPresent) {
      argList.addAll(listOf("--native-path", nativePathProp.get()))
    }
    args = argList
  }

  // Auto-detect ORT CUDA DLLs when not explicitly set
  if (System.getenv("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH") == null) {
    val detected = conventions.OrtCudaHelpers.detectOrtCudaPath(rootProject.layout.projectDirectory.asFile)
    if (detected != null) {
      environment("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH", detected)
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
        implementation(testFixtures(project(":modules:configuration")))
        implementation(testFixtures(project(":modules:ort-common"))) // §14.28 U1 helper
        implementation(libs.jackson.dataformat.yaml)
        runtimeOnly(libs.jackson.core)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
      targets {
        all {
          testTask.configure {
            jvmArgs("-Dnet.bytebuddy.experimental=true")
          }
        }
      }
    }
  }
}
