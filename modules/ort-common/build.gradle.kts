plugins {
  `java-library`
  `java-test-fixtures`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  // Expose ORT types (e.g. OrtException, OrtSession) without forcing a runtime variant choice.
  // Worker uses GPU ORT (onnxruntime_gpu); app-services keeps CPU ORT at runtime.
  compileOnlyApi(libs.onnxruntime)

  // OrtCudaHelper uses ConfigPrecedence, EnvRegistry, ConfigStore
  api(project(":modules:configuration"))
  implementation(libs.slf4j.api)
  // ModelManifest reads model_manifest.json
  implementation(libs.jackson.databind)
  // Tempdoc 400 LR2-b: lease.acquire span emission inside NativeSessionHandle.
  implementation(libs.opentelemetry.api)
  // Tempdoc 402 Phase P1: unlocks @BuildContract annotations on ort-common
  // sites (NativeSessionHandle Builder construction, InferenceCompositionRoot
  // call-site analogs). core-contracts is dep-free so no circularity risk.
  // Annotation-only dependency — compileOnly suffices (annotation TYPE not needed at runtime).
  compileOnly(project(":modules:core-contracts"))

  // §14.28 U1: testFixtures helper for integration tests + benchmarks that need a
  // SessionHandle from a model directory without a full ResolvedConfig. Mirrors the shape of
  // production InferenceCompositionRoot.compose but reachable from test scopes across the
  // module graph (avoids a circular dep on worker-core).
  testFixturesApi(project(":modules:configuration"))
  testFixturesCompileOnly(libs.onnxruntime)
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        implementation(libs.mockito.core)
        // TestResolvedConfigHelper for constructing canonical ResolvedConfig instances
        // in RuntimePolicyResolverTest / ModelSessionPolicyResolverTest / assembler tests
        // (tempdoc 397 §12 P1 prerequisite).
        implementation(testFixtures(project(":modules:configuration")))
        runtimeOnly(libs.onnxruntime)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
  }
}
