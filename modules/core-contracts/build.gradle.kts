// Tempdoc 400 §22 Issue A (LR6-a refactor): dep-free module hosting the
// @BuildContract + @AdvisoryContract annotations. Sibling modules that
// cannot take an ipc-common dependency (ort-common, worker-core,
// app-launcher test sources) depend on this module directly.
//
// Tempdoc 402 P2: added SLF4J main-source dep for BootContractRunner.
// Tempdoc 402 P5: added OpenTelemetry API for ContractEmitter span events.
// No network transport. No protobuf. No framework. Pure JDK + SLF4J + OTel API.

plugins {
  `java-library`
  id("conventions.jvm-base")
}

// BootContractRunner is a boot-time validator that invokes System.exit(1) when
// a contract invariant is violated — fail-fast at system startup is the intended
// behaviour, not a J2EE/JEE anti-pattern. Same reasoning as ssot-tools. Point
// pmdMain at the CLI-tooling ruleset which excludes `DoNotTerminateVM`.
tasks.named<org.gradle.api.plugins.quality.Pmd>("pmdMain") {
  ruleSetFiles = files(rootProject.layout.projectDirectory.file("config/pmd/ruleset-cli-tools.xml"))
}

dependencies {
  implementation(libs.slf4j.api)
  // Tempdoc 402 P5: ContractEmitter uses io.opentelemetry.api.trace.Span#addEvent
  // to emit `contract.violation` events consumed by the Python projection
  // `scripts/jseval/jseval/projections/contract_violations.py`.
  // `api` (not `implementation`) because ContractEmitter#emit accepts a Span
  // parameter on its public API — consumers see the OTel type.
  api(libs.opentelemetry.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter.api)
  // Tempdoc 402 P5: ContractEmitterTest uses InMemorySpanExporter + SdkTracerProvider
  // to verify the emitted span event round-trips with the exact name + attrs expected
  // by the Python consumer. Test-only — production stays on plain OTel API.
  testImplementation(libs.opentelemetry.sdk)
  // sdk-trace is used directly by the test (SdkTracerProvider) — not just transitive
  // through opentelemetry-sdk. Declare directly per buildHealth advice.
  testImplementation(libs.opentelemetry.sdk.trace)
  testImplementation(libs.opentelemetry.sdk.testing)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}
